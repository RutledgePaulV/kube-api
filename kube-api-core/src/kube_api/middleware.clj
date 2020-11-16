(ns kube-api.middleware
  (:require [clojure.string :as strings]
            [muuntaja.core :as m]
            [kube-api.utils :as utils])
  (:import [java.io InputStream]
           [clojure.lang IObj]))


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
            (let [final-body (cond->> body
                                      (instance? InputStream body)
                                      (m/decode "application/json"))]
              (if (and (map? response) (instance? IObj final-body))
                (with-meta final-body (meta response))
                response)))]
    (fn prepare-response-handler
      ([request]
       (prepare-response (handler request)))
      ([request respond raise]
       (handler request (comp respond prepare-response) raise)))))