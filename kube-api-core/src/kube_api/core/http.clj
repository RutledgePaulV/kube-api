(ns kube-api.core.http
  (:require [kube-api.core.utils :as utils]
            [clj-okhttp.core :as http]
            [kube-api.core.auth :as auth]
            [clojure.string :as strings])
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

(defn wrap-prepare-request [handler server]
  (letfn [(prepare-request [request]
            (update request :url #(utils/join-segment server %)))]
    (fn prepare-request-handler
      ([request]
       (handler (prepare-request request)))
      ([request respond raise]
       (handler (prepare-request request) respond raise)))))

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
  (let [server    (get-in context [:cluster :server])
        user      (get-in context [:user])
        server-ca (get-in context [:cluster :certificate-authority-data])]
    (cond-> {:middleware [wrap-prepare-response #(wrap-prepare-request % server)]}
      (not (strings/blank? server-ca))
      (assoc :server-certificates [(utils/base64-decode server-ca)])
      :always
      (auth/inject-client-auth user)
      :always
      (http/create-client))))


(defn without-read-timeout [^OkHttpClient client]
  (http/create-client client {:read-timeout 0}))