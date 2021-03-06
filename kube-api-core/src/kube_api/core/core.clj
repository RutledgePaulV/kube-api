(ns kube-api.core.core
  "The 'small core' of kube-api. Lets you invoke API operations."
  (:require [clj-okhttp.core :as http]
            [kube-api.core.utils :as utils]
            [kube-api.core.kubeconfig :as kubeconfig]
            [kube-api.core.operations :as operations]
            [kube-api.core.http :as kube-http]
            [kube-api.core.schemas :as schemas]
            [malli.generator :as gen]
            [clojure.tools.logging :as log]
            [kube-api.core.swagger.malli :as malli])
  (:import [okhttp3 Response WebSocket]
           [okio ByteString]))


(defonce validation
  (atom (utils/in-kubernetes?)))


(defn- prepare-invoke-request [client op-selector request]
  (let [validate?             (deref validation)
        {:keys [op-selector-schema] :as views} (-> client (meta) :operations (force))
        defaulted-op-selector (utils/prepare op-selector-schema op-selector)
        _                     (when validate? (utils/validate! "Invalid op selector." op-selector-schema defaulted-op-selector))
        {:keys [uri request-method request-schema]} (operations/get-op views defaulted-op-selector)
        defaulted-request     (utils/prepare request-schema request)
        _                     (when validate? (utils/validate! "Invalid request." request-schema defaulted-request))]
    (let [rendered-uri (utils/render-template-string uri (get-in defaulted-request [:path-params]))]
      (cond-> {:request-method request-method :url rendered-uri}
        (not-empty (:query-params defaulted-request))
        (assoc :query-params (:query-params defaulted-request))
        (not-empty (:body defaulted-request))
        (-> (assoc :body (:body defaulted-request))
            (assoc-in [:headers "Content-Type"] "application/json"))))))


(defn set-validation!
  "Enable or disable client-side validation of requests per the server's
   json schema prior to submitting to the remote API. Defaults to off if
   the process is believed to be running in kubernetes, on otherwise."
  [true-or-false]
  (reset! validation true-or-false))

(defn contexts
  "Lists the available kubernetes contexts from your merged kubeconfig."
  []
  (->> (kubeconfig/get-merged-kubeconfig) (:contexts) (map :name) (into (sorted-set))))

(defn create-client
  "Create a client instance. Optionally provide the name of a kubectl context
   or a map containing all the requisite connection and authentication data.

   context - a string name of the context to select or else a map containing connection information

   "
  ([] (create-client (kubeconfig/current-context)))
  ([context]
   (cond
     ; handled an entire kubeconfig
     (and (map? context) (contains? context :contexts))
     (recur (kubeconfig/current-context context))

     ; presumably have a selected context, validate it in case
     (map? context)
     (do (utils/validate! "Invalid context." schemas/resolved-context-schema (dissoc context :http-client))
         (let [{:keys [http-client] :as full-context}
               (update context :http-client #(or % (kube-http/make-http-client context)))]
           (with-meta full-context
             (let [swagger    (delay (let [response (http/get http-client "/openapi/v2" {:as :json})]
                                       (if (http/not-found? (:response (meta response)))
                                         (http/get http-client "swagger.json" {:as :json})
                                         response)))
                   operations (delay (operations/kube-swagger->operation-views (deref swagger)))]
               {:swagger swagger :operations operations}))))

     (or (string? context) (keyword? context))
     (recur (kubeconfig/select-context (name context)))

     :otherwise
     (throw (IllegalArgumentException. "This is not a valid context for building a client.")))))


(defn swagger-specification
  "Returns the raw swagger specification for the server being targeted by the client."
  [client]
  (-> client (meta) :swagger (force)))

(defn malli-schemas
  "Returns a malli registry containing all the definitions from the swagger specification."
  ([client]
   (malli-schemas client (keys (:definitions (swagger-specification client)))))
  ([client keys]
   (let [spec (swagger-specification client)]
     (into {}
           (for [key keys]
             (let [schema {:$ref (utils/keyword->pointer key)}]
               [(name key) (malli/swagger->malli spec schema)]))))))

(defn ops
  "Returns fully qualified op selectors for all available operations.
   Optionally filter the returned selectors by a partial selector.

   client    - a client instance
   op-filter - a partial op selector that will be used as a filter for the results

   "
  ([client]
   (->> (:selectors (-> client (meta) :operations (force)) [])
        (sort-by (juxt :kind :group :version :action :operation))))
  ([client op-filter]
   (filter #(= (select-keys % (keys op-filter)) op-filter) (ops client))))


(defn spec
  "Returns the full specification for a single operation. Includes schemas describing
   the required data to invoke the operation and the data that will be returned in a
   response.

   client      - a client instance
   op-selector - a full or partial operation selector

   "
  [client op-selector]
  (let [views     (-> client (meta) :operations (force))
        schema    (:op-selector-schema views)
        validator (utils/validator-factory schema)]
    (if (validator op-selector)
      (operations/get-op views op-selector)
      (utils/validation-error "Invalid op selector." schema op-selector))))


(defn validate-request
  "Validates a request payload against the spec for the chosen operation identified
   by the op selector. Returns true if the request is considered valid according to
   the schema otherwise throws an exception containing a description of the validation
   failures.

   client      - a client instance
   op-selector - a full or partial operation selector
   request     - a request payload to validate against the operation

   "
  [client op-selector request]
  (let [{:keys [request-schema]} (spec client op-selector)
        defaulted-request (utils/prepare request-schema request)]
    (utils/validate! "Invalid request." request-schema defaulted-request)))


(defn generate-request
  "Returns example request data demonstrating the data shape required to make a request
   for the operation identified by the op selector. Note that this can take quite a while
   because the schemas are so large.

   client      - a client instance
   op-selector - a full or partial operation selector
  "
  [client op-selector]
  (when-some [{:keys [request-schema]} (spec client op-selector)]
    (gen/generate (utils/generator-factory request-schema))))


(defn generate-response
  "Returns example response data demonstrating the data shape returned from
   a successful request. Note that this can take quite a while because the
   schemas are so large.

   client      - a client instance
   op-selector - a full or partial operation selector
  "
  [client op-selector]
  (when-some [{:keys [response-schemas]} (spec client op-selector)]
    (let [schema (val (first (into (sorted-map) response-schemas)))]
      (gen/generate (utils/generator-factory schema)))))


(defn invoke
  "Submits the provided request for the specified operation to the server
  targeted by the client. Returns the body of the response augmented with
  clojure metadata containing the raw http request and the raw http response.

   2 arity:
   client      - a client instance
   op-selector - a full or partial operation selector

   3 arity:
   request     - a request payload to submit for the operation

   5 arity:
   respond     - a callback function if you want to invoke asynchronously.
   raise       - an error callback function if you want to invoke asynchronously.
  "
  ([client op-selector]
   (invoke client op-selector {}))
  ([{:keys [http-client] :as client} op-selector request]
   (let [final-request (prepare-invoke-request client op-selector request)]
     (http/request http-client final-request)))
  ([{:keys [http-client] :as client} op-selector request respond raise]
   (let [final-request (prepare-invoke-request client op-selector request)]
     (http/request http-client final-request respond raise))))


(defn invoke-stream
  "Like invoke, but for streaming responses. For example, you would use this
   to access a pod's logs which are returned as a streaming body"
  ([{:keys [http-client] :as client} op-selector request]
   (let [final-http-client
         (kube-http/without-read-timeout http-client)
         final-request
         (assoc (prepare-invoke-request client op-selector request) :as :stream)]
     (http/request final-http-client final-request)))
  ([{:keys [http-client] :as client} op-selector request respond raise]
   (let [final-client
         (kube-http/without-read-timeout http-client)
         final-request
         (assoc (prepare-invoke-request client op-selector request) :as :stream)]
     (http/request final-client final-request respond raise))))


(defn connect
  "Submits the provided request for the specified operation to the server
   as part of a websocket upgrade request. If the upgrade succeeds then
   messages from the server will invoke the provided callbacks. Returns
   a okhttp3.Websocket instance. Does not automatically reconnect if the
   connection breaks. If you need reliable reconnection behaviors please
   see `listwatcher.clj` in the related kube-api-controllers module."
  (^WebSocket [client op-selector callbacks]
   (connect client op-selector {} callbacks))
  (^WebSocket [{:keys [http-client] :as client} op-selector request callbacks]
   (let [final-callbacks
         (-> {:on-open    (fn default-on-open [^WebSocket socket ^Response response]
                            (log/infof "Websocket connection opened with response %s." (str response)))
              :on-bytes   (fn default-on-bytes [^WebSocket socket ^ByteString message]
                            (log/info "Websocket received byte frame."))
              :on-text    (fn default-on-text [^WebSocket socket ^String message]
                            (log/info "Websocket received text frame."))
              :on-closing (fn default-on-closing [^WebSocket socket ^Long code ^String reason]
                            (log/infof "Websocket connection is closing with code '%d' and reason '%s'." code reason)
                            (.close socket code reason))
              :on-closed  (fn default-on-closed [^WebSocket socket ^Long code ^String reason]
                            (log/infof "Websocket connection closed with code '%d' and reason '%s'." code reason))
              :on-failure (fn default-on-failure [^WebSocket socket exception ^Response response]
                            (let [status  (.code response)
                                  message (.string (.body response))]
                              (log/errorf exception "Connection failure with response code '%d' and message '%s'." status message)))}
             (merge callbacks))
         final-request
         (cond->
           (prepare-invoke-request client op-selector request)
           :always (assoc-in [:request-method] :get)
           (= "connect" (get op-selector :action))
           (assoc-in [:headers "Sec-WebSocket-Protocol"] "v4.channel.k8s.io"))
         final-client
         (kube-http/without-read-timeout http-client)]
     (http/connect final-client final-request final-callbacks))))



(comment

  (def client (create-client "do-nyc1-k8s-1-19-3-do-2-nyc1-1604718220356"))

  ; listing pods

  (invoke client
          {:action "list" :kind "Pod"}
          {:path-params {:namespace "kube-system"}})

  ; watching changes to deployments in the kube-system namespace
  (connect client
           {:action "list" :kind "Deployment"}
           {:path-params  {:namespace "kube-system"}
            :query-params {:watch true}}
           {:on-text
            (fn [socket message]
              (println (get-in message [:object :metadata :labels])))})


  ; getting the logs from a pod
  (def stream (invoke-stream client
                             {:operation "readCoreV1NamespacedPodLog"}
                             {:path-params
                              {:namespace "kube-system"
                               :name      "kube-proxy-p6mm5"}}))

  (slurp stream)

  )