(ns kube-api.core
  (:require [clj-http.client :as http]
            [kube-api.utils :as utils]
            [kube-api.auth :as auth]
            [kube-api.ssl :as ssl]
            [clojure.string :as strings]
            [kube-api.swagger.kubernetes :as swag]
            [malli.generator :as gen]))


(defn request*
  ([client url method]
   (request* client url method {}))
  ([client uri method options]
   (let [token       (get-in client [:user :token])
         server      (get-in client [:cluster :server])
         ca-cert     (get-in client [:cluster :certificate-authority-data])
         client-cert (get-in client [:cluster :client-certificate-data])
         client-key  (get-in client [:cluster :client-key-data])
         username    (get-in client [:user :username])
         password    (get-in client [:user :password])
         request     (utils/merge+
                       (cond-> {:url                  (utils/mk-url server uri)
                                :as                   :json-strict
                                :content-type         "application/json"
                                :request-method       (keyword method)
                                :socket-timeout       1000
                                :connection-timeout   1000
                                :conn-timeout         1000
                                :throw-exceptions     false
                                :unexceptional-status (constantly true)}
                         (not (strings/blank? token))
                         (assoc-in [:headers "Authorization"] (str "Bearer " token))
                         (and (not (strings/blank? username)) (not (strings/blank? password)))
                         (assoc :basic-auth [username password])
                         (not (strings/blank? ca-cert))
                         (assoc :trust-managers (ssl/trust-managers ca-cert))
                         (and (not (strings/blank? client-cert)) (not (strings/blank? client-key)))
                         (assoc :key-managers (ssl/key-managers client-cert client-key)))
                       options)
         response    (http/request request)]
     (-> (get response :body)
         (with-meta {:request request :response response})))))


(defn create-client
  "Create a client instance. Optionally provide the name of a kubectl context
   or a map containing all the requisite connection and authentication data.

   context - a string name of the context to select or else a map containing connection information

   "
  ([] (create-client (auth/get-context)))
  ([context]
   (if (map? context)
     (do (utils/validate! "Invalid context." auth/context-schema context)
         (with-meta context
           (let [swagger    (delay (request* context "/openapi/v2" :get))
                 operations (delay (swag/kube-swagger->operation-views (deref swagger)))]
             {:swagger swagger :operations operations})))
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
             rendered-uri (utils/render-template-string endpoint (get-in request [:path-params]))
             is-watching  (get-in request [:query-params :watch])]
         (request*
           client rendered-uri method
           (cond-> {}
             (not-empty (:query-params request))
             (assoc :query-params (:query-params request))
             (not-empty (:body-params request))
             (assoc :form-params (:form-params request)))))))))