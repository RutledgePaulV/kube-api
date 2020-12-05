(ns kube-api.core.auth
  (:require [kube-api.core.schemas :as schemas]
            [kube-api.core.utils :as utils]))

(defn dispatch-fn [client-opts user]
  (utils/dispatch-key schemas/user-schema user))

(defmulti inject-client-auth #'dispatch-fn)

(defn req-xf->mw [transformer]
  (fn [handler]
    (fn
      ([request] (handler (transformer request)))
      ([request respond raise] (handler (transformer request) respond raise)))))

(defmethod inject-client-auth :basic-auth [client-opts {:keys [username password]}]
  (letfn [(prepare-request [request]
            (assoc request :basic-auth [username password]))]
    (update client-opts :middleware (fnil conj []) (req-xf->mw prepare-request))))

(defmethod inject-client-auth :client-key-auth [client-opts {:keys [client-certificate-data client-key-data]}]
  (-> client-opts
      (assoc :client-certificate (utils/base64-decode client-certificate-data))
      (assoc :client-key (utils/base64-decode client-key-data))))

(defmethod inject-client-auth :exec-auth [client-opts {{:keys [command args env]} :exec}]
  (throw (ex-info "Not implemented yet." {})))

(defmethod inject-client-auth :token-auth [client-opts {:keys [token]}]
  (letfn [(prepare-request [request]
            (assoc-in request [:headers "Authorization"] (str "Bearer " token)))]
    (update client-opts :middleware (fnil conj []) (req-xf->mw prepare-request))))

(defmethod inject-client-auth :token-file-auth [client-opts {:keys [tokenFile]}]
  (letfn [(prepare-request [request]
            (assoc-in request [:headers "Authorization"] (str "Bearer " (slurp tokenFile))))]
    (update client-opts :middleware (fnil conj []) (req-xf->mw prepare-request))))


(comment

  (((first (:middleware
             (inject-client-auth
               {}
               {:username "paul"
                :password "hehehe"})))
    (fn [request] request)) {})

  )
