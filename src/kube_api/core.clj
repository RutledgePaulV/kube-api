(ns kube-api.core
  (:require [clj-http.client :as http]
            [kube-api.utils :as utils]
            [kube-api.auth :as auth]
            [kube-api.ssl :as ssl]))

(defn request*
  ([context url method]
   (request* context url method {}))
  ([context uri method options]
   (let [token    (get-in context [:user :token])
         server   (get-in context [:cluster :server])
         ca-cert  (get-in context [:cluster :certificate-authority-data])
         request  (utils/meta-merge
                    {:headers        {"Authorization" (str "Bearer " token)}
                     :url            (utils/mk-url server uri)
                     :as             :json-strict
                     :content-type   "application/json"
                     :request-method (keyword method)
                     :trust-managers (ssl/trust-managers ca-cert)}
                    options)
         response (http/request request)]
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