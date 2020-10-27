(ns kube-api.core
  (:require [clj-http.client :as http]
            [kube-api.utils :as utils]
            [kube-api.auth :as auth]
            [kube-api.ssl :as ssl]
            [clojure.string :as strings]))


(defn request*
  ([client url method]
   (request* client url method {}))
  ([client uri method options]
   (let [token       (get-in client [:user :token])
         server      (get-in client [:cluster :server])
         ca-cert     (get-in client [:cluster :certificate-authority-data])
         client-cert (get-in client [:cluster :client-certificate-data])
         client-key  (get-in client [:cluster :client-key-data])
         request     (utils/merge+
                       (cond-> {:headers        {"Authorization" (str "Bearer " token)}
                                :url            (utils/mk-url server uri)
                                :as             :json-strict
                                :content-type   "application/json"
                                :request-method (keyword method)}
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
       {:swagger (delay (request* context "/openapi/v2" :get))})
     (recur (auth/select-context context)))))


(defn ops [client]
  )

(defn docs [client op]
  )

(defn invoke [client]
  )