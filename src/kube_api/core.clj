(ns kube-api.core
  (:require [clj-okhttp.core :as http]
            [kube-api.utils :as utils]
            [kube-api.auth :as auth]
            [kube-api.ssl :as ssl]
            [clojure.string :as strings]
            [kube-api.swagger.kubernetes :as swag]
            [malli.generator :as gen]
            [muuntaja.core :as m])
  (:import [java.io InputStream]))


(defn request*
  ([client url method]
   (request* client url method {}))
  ([client uri method options]
   (let [token    (get-in client [:user :token])
         server   (get-in client [:cluster :server])
         username (get-in client [:user :username])
         password (get-in client [:user :password])
         request  (utils/merge+
                    (cond-> {:url            (utils/mk-url server uri)
                             :request-method (keyword method)}
                      (not (strings/blank? token))
                      (assoc-in [:headers "Authorization"] (str "Bearer " token))
                      (and (not (strings/blank? username)) (not (strings/blank? password)))
                      (assoc :basic-auth [username password]))
                    options)
         response (http/request* (:http-client client) request)]
     (let [body (get response :body)]
       (-> (if (instance? InputStream body)
             (m/decode "application/json" body)
             body)
           (with-meta {:request request :response response}))))))

(defn connect*
  ([client url method]
   (request* client url method {}))
  ([client uri method options]
   (let [token    (get-in client [:user :token])
         server   (get-in client [:cluster :server])
         username (get-in client [:user :username])
         password (get-in client [:user :password])
         request  (utils/merge+
                    (cond-> {:url            (utils/mk-url server uri)
                             :request-method (keyword method)}
                      (not (strings/blank? token))
                      (assoc-in [:headers "Authorization"] (str "Bearer " token))
                      (and (not (strings/blank? username)) (not (strings/blank? password)))
                      (assoc :basic-auth [username password]))
                    options)]
     (http/connect (:http-client client) request options))))


(defn create-http-client [options]
  (let [ca-cert            (get-in options [:cluster :certificate-authority-data])
        client-cert        (get-in options [:cluster :client-certificate-data])
        client-key         (get-in options [:cluster :client-key-data])
        trust-managers     (when-not (strings/blank? ca-cert)
                             (ssl/trust-managers ca-cert))
        key-managers       (when-not (or (strings/blank? client-cert)
                                         (strings/blank? client-key))
                             (ssl/key-managers client-cert client-key))
        ssl-socket-factory (ssl/ssl-socket-factory trust-managers key-managers)
        x509-trust-manager (utils/seek ssl/x509-trust-manager? trust-managers)]
    (http/create-client
      {:ssl-socket-factory ssl-socket-factory
       :x509-trust-manager x509-trust-manager})))


(defn create-client
  "Create a client instance. Optionally provide the name of a kubectl context
   or a map containing all the requisite connection and authentication data.

   context - a string name of the context to select or else a map containing connection information

   "
  ([] (create-client (auth/get-context)))
  ([context]
   (if (map? context)
     (do (utils/validate! "Invalid context." auth/context-schema context)
         (let [full-context (assoc context :http-client (create-http-client context))]
           (with-meta full-context
             (let [swagger    (delay (request* full-context "/openapi/v2" :get))
                   operations (delay (swag/kube-swagger->operation-views (deref swagger)))]
               {:swagger swagger :operations operations}))))
     (recur (auth/select-context context)))))


(defn swagger-specification
  "Returns the raw swagger specification for the server being targeted by the client."
  [client]
  (-> client (meta) :swagger (force)))


(defn ops
  "Returns fully qualified op selectors for all available operations.
   Optionally filter the returned selectors by a partial selector.

   client    - a client instance
   op-filter - a partial op selector that will be used as a filter for the results

   "
  ([client]
   (->> (keys (:operations (-> client (meta) :operations (force))))
        (sort-by (juxt :kind :group :version :action))))
  ([client op-filter]
   (filter #(= (select-keys % (keys op-filter)) op-filter) (ops client))))


(defn docs
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
      (swag/get-op views op-selector)
      (utils/validation-error "Invalid op selector." schema op-selector))))


(defn validate
  "Validates a request payload against the spec for the chosen operation identified
   by the op selector. Returns true if the request is considered valid according to
   the schema otherwise throws an exception containing a description of the validation
   failures.

   client      - a client instance
   op-selector - a full or partial operation selector
   request     - a request payload to validate against the operation

   "
  [client op-selector request]
  (let [definition     (docs client op-selector)
        request-schema (get definition :request-schema)
        validator      (utils/validator-factory request-schema)]
    (or (validator request) (utils/validation-error "Invalid request." request-schema request))))


(defn gen-request
  "Returns example request data demonstrating the data shape required to make a request
   for the operation identified by the op selector. Note that this can take quite a while
   because the schemas are so large.

   client      - a client instance
   op-selector - a full or partial operation selector
  "
  [client op-selector]
  (when-some [{:keys [request-schema]} (docs client op-selector)]
    (gen/generate request-schema)))


(defn gen-response
  "Returns example response data demonstrating the data shape returned from
   a successful request. Note that this can take quite a while because the
   schemas are so large.

   client      - a client instance
   op-selector - a full or partial operation selector
  "
  [client op-selector]
  (when-some [{:keys [response-schemas]} (docs client op-selector)]
    (gen/generate (val (first (into (sorted-map) response-schemas))))))


(defn invoke
  "Submits the provided request for the specified operation to the server
  targeted by the client. Returns the body of the response augmented with
  clojure metadata containing the raw http request and the raw http response.

   client      - a client instance
   op-selector - a full or partial operation selector
   request     - a request payload to submit for the operation

  "
  ([client op-selector]
   (invoke client op-selector
           {:body-params  {}
            :query-params {}
            :path-params  {:namespace (or (get client :namespace) "default")}}))
  ([client op-selector request]
   (let [definition     (docs client op-selector)
         request-schema (get definition :request-schema)
         validator      (utils/validator-factory request-schema)]
     (if-not (validator request)
       (utils/validation-error "Invalid request." request-schema request)
       (let [endpoint     (:uri definition)
             method       (:request-method definition)
             rendered-uri (utils/render-template-string endpoint (get-in request [:path-params]))]
         (request*
           client rendered-uri method
           (cond-> {}
             (not-empty (:query-params request))
             (assoc :query-params (:query-params request))
             (not-empty (:body-params request))
             (assoc :form-params (:body-params request)))))))))


(defn watch
  ([client op-selector {:keys [on-event on-error on-closed] :as callbacks}]
   (watch client op-selector {} callbacks))
  ([client op-selector request
    {:keys [on-event on-error on-closed]
     :or   {on-event  (fn [message])
            on-error  (fn [exception])
            on-closed (fn [code reason])}}]
   (let [definition     (docs client (assoc op-selector :action "watch"))
         request-schema (get definition :request-schema)
         validator      (utils/validator-factory request-schema)]
     (if-not (validator request)
       (utils/validation-error "Invalid request." request-schema request)
       (let [endpoint     (:uri definition)
             method       (:request-method definition)
             rendered-uri (utils/render-template-string endpoint (get-in request [:path-params]))]
         (connect*
           client rendered-uri method
           (cond-> {}
             (not-empty (:query-params request))
             (assoc :query-params (:query-params request))
             (not-empty (:body-params request))
             (assoc :form-params (:body-params request))
             :always
             (merge {:on-bytes   (fn [socket message]
                                   (on-event (m/decode "application/json" message)))
                     :on-text    (fn [socket message]
                                   (on-event (m/decode "application/json" message)))
                     :on-closed  (fn [socket code reason]
                                   (on-closed code reason))
                     :on-failure (fn [socket exception response]
                                   (on-error exception))}))))))))



(comment

  (def client (create-client "microk8s"))

  (watch client
         {:action "watch" :kind "Deployment"}
         {:path-params  {:namespace "kube-system"}
          :query-params {:watch true}}
         {:on-event
          (fn [message]
            (println (get-in message [:object :metadata :labels])))})

  )