(ns kube-api.core.http
  (:require [clojure.string :as strings]
            [kube-api.core.utils :as utils]
            [clj-okhttp.core :as http])
  (:import [okhttp3 OkHttpClient]
           [java.io FilterInputStream InputStream]
           [clojure.lang IObj]))

(defprotocol IntoIObj
  (into-i-obj [this] "Turns object into something that supports metadata."))

(extend-protocol IntoIObj
  IObj
  (into-i-obj [^IObj this] this)

  InputStream
  (into-i-obj [^InputStream this]
    (let [metadata (volatile! {})]
      (proxy [FilterInputStream IObj] [this]
        (meta []
          (deref metadata))
        (withMeta [meta]
          (vreset! metadata meta)
          this)))))


(defn wrap-prepare-request [handler auth-config]
  (let [token    (get-in auth-config [:user :token])
        server   (get-in auth-config [:cluster :server])
        username (get-in auth-config [:user :username])
        password (get-in auth-config [:user :password])]
    (letfn [(prepare-request [request]
              (cond-> request
                (not (strings/blank? token))
                (assoc-in [:headers "Authorization"] (str "Bearer " token))
                (and (not (strings/blank? username)) (not (strings/blank? password)))
                (assoc :basic-auth [username password])
                :always
                (update :url #(utils/join-segment server %))))]
      (fn prepare-request-handler
        ([request]
         (handler (prepare-request request)))
        ([request respond raise]
         (handler (prepare-request request) respond raise))))))

(defn wrap-prepare-response [handler]
  (letfn [(prepare-response [{:keys [body] :as response}]
            (let [metadata (meta response)]
              (if (satisfies? IntoIObj body)
                (with-meta (into-i-obj body) metadata)
                response)))]
    (fn prepare-response-handler
      ([request] (prepare-response (handler request)))
      ([request respond raise]
       (handler request (comp respond prepare-response) raise)))))

(defn make-http-client
  "Creates a new http client prepared to make authenticated requests to the selected cluster."
  [context]
  (http/create-client
    {:server-certificates (get-in context [:cluster :certificate-authority-data])
     :client-certificate  (get-in context [:cluster :client-certificate-data])
     :client-key          (get-in context [:cluster :client-key-data])
     :middleware          [wrap-prepare-response #(wrap-prepare-request % context)]}))


(defn without-read-timeout [^OkHttpClient client]
  (http/create-client client {:read-timeout 0}))