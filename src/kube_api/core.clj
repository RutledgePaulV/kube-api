(ns kube-api.core
  (:require [clj-http.client :as http]
            [kube-api.utils :as utils]
            [kube-api.auth :as auth]
            [kube-api.ssl :as ssl]
            [clojure.string :as strings]
            [kube-api.swagger.kubernetes :as swag]
            [malli.generator :as gen]
            [malli.error :as me]
            [malli.core :as m]))

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
                       (cond-> {:url                (utils/mk-url server uri)
                                :as                 :json-strict
                                :content-type       "application/json"
                                :request-method     (keyword method)
                                :socket-timeout     1000
                                :connection-timeout 1000}
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
  ([]
   (create-client (auth/get-context)))
  ([context]
   (if (map? context)
     (with-meta context
       (let [swagger    (delay (request* context "/openapi/v2" :get))
             operations (delay (swag/kube-swagger->operation-views (deref swagger)))]
         {:swagger swagger :operations operations}))
     (recur (auth/select-context context)))))

(defn swagger-specification
  "Returns the raw swagger specification."
  [client]
  (-> client (meta) :swagger (force)))

(defn operation-specification
  "Returns the full operation representation used by this library."
  [client]
  (-> client (meta) :operations (force)))

(defn ops [client]
  (keys (operation-specification client)))

(defn docs [client op]
  (get (operation-specification client) (name op)))

(defn validate-request [client op request]
  (let [definition     (get (operation-specification client) (name op))
        request-schema (get definition :request)
        validator      (utils/validator-factory request-schema)]
    (or (validator request)
        (-> (m/explain request-schema request)
            (me/with-spell-checking)
            (me/humanize)))))

(defn example-request [client op]
  (when-some [{:keys [request]} (get (operation-specification client) (name op))]
    (gen/generate request)))

(defn example-response [client op]
  (when-some [{:keys [response]} (get (operation-specification client) (name op))]
    (gen/generate (val (first (into (sorted-map) response))))))

(defn invoke [client op request]
  (let [definition     (get (operation-specification client) (name op))
        request-schema (get definition :request-schema)
        validator      (utils/validator-factory request-schema)]
    (if-not (validator request)
      (-> (m/explain request-schema request)
          (me/with-spell-checking)
          (me/humanize))
      (let [endpoint     (str "/" (:endpoint definition))
            method       (:verb definition)
            rendered-uri (utils/render-template-string endpoint (get-in request [:path-params]))]
        (request*
          client rendered-uri method
          (cond-> {}
            (not-empty (:query-params request))
            (assoc :query-params (:query-params request))
            (not-empty (:body-params request))
            (assoc :form-params (:form-params request))))))))