(ns kube-api.core.auth
  (:require [kube-api.core.schemas :as schemas]
            [kube-api.core.utils :as utils]
            [clojure.java.shell :as sh]
            [kube-api.core.kubeconfig :as kubeconfig]
            [muuntaja.core :as muun]
            [clojure.string :as strings])
  (:import [java.io File InputStream]
           [java.time Instant]))

(defn dispatch-fn [client-opts user]
  (utils/dispatch-key schemas/user-schema user))

(defmulti inject-client-auth #'dispatch-fn)

(defn kubeconfig-dir []
  (when-some [file ^File (first (kubeconfig/kubeconfig-files))]
    (.getParentFile file)))

(defn should-retry? [request response]
  (and (= 401 (:status response))
       (not (instance? InputStream (:body request)))))

(defn retry-middleware
  "Given a function of two arguments to 'prepare' authentication on a request to execute,
   return http middleware that will force new authentication in the event of a failure."
  [prepare-request]
  (fn [handler]
    (fn retry-handler
      ([request]
       (let [[forced prepared]
             (try
               [false (prepare-request request false)]
               (catch Exception e
                 [true (prepare-request request true)]))]
         (if forced
           (handler prepared)
           (let [response (handler prepared)]
             (if (should-retry? request response)
               (handler (prepare-request request true))
               response)))))
      ([request respond raise]
       (let [[forced prepared]
             (try
               [false (prepare-request request false)]
               (catch Exception e
                 (try
                   [true (prepare-request request true)]
                   (catch Exception e
                     (raise e)))))]
         (if forced
           (handler prepared respond raise)
           (handler prepared
                    (fn [response]
                      (if (should-retry? request response)
                        (handler (prepare-request request true) respond raise)
                        (respond response)))
                    raise)))))))

(defn normal-middleware [prepare-request]
  (fn [handler]
    (fn normal-handler
      ([request] (handler (prepare-request request)))
      ([request respond raise] (handler (prepare-request request) respond raise)))))

(defmethod inject-client-auth :basic-auth [client-opts {:keys [username password]}]
  (letfn [(prepare-request [request]
            (assoc request :basic-auth [username password]))]
    (update client-opts :middleware (fnil conj []) (normal-middleware prepare-request))))

(defmethod inject-client-auth :client-key-auth [client-opts {:keys [client-certificate-data client-key-data]}]
  (-> client-opts
      (assoc :client-certificate (utils/base64-decode client-certificate-data))
      (assoc :client-key (utils/base64-decode client-key-data))))

; https://kubernetes.io/docs/reference/access-authn-authz/authentication/#input-and-output-formats
(defmethod inject-client-auth :exec-auth
  [client-opts {{:keys [command args env installHint] :or {args [] env []}} :exec}]
  (let [state (atom nil)]
    (letfn [(run []
              (let [full-command (into [command] args)
                    directory    (kubeconfig-dir)
                    sh-arguments (cond-> full-command
                                   (some? directory) (conj :dir directory)
                                   :always
                                   (conj :out-enc :bytes)
                                   :always
                                   (conj :env (into {} (map (juxt :name :value)) env)))
                    {:keys [out err exit]} (apply sh/sh sh-arguments)]
                (if (zero? exit)
                  (let [data (muun/decode "application/json" out)
                        case (utils/dispatch-key schemas/exec-response data)]
                    [case data])
                  (throw (ex-info
                           (format "Error code '%d' returned when obtaining token using command '%s'.\n%s\n%s"
                                   exit
                                   (strings/join " " full-command)
                                   err
                                   (if-not (strings/blank? installHint)
                                     (str "Install hint:" \newline installHint)
                                     ""))
                           {:exit exit :err err :installHint installHint})))))
            (stale? [state]
              (or (empty? state)
                  (and (contains? state :expirationTimestamp)
                       (not (pos? (compare (:expirationTimestamp state) (str (Instant/now))))))))
            (gen-new-request [request force-new]
              (let [new-delay (delay (run))
                    swap-fn   (if force-new
                                (fn [old]
                                  (if (and (some? old) (not (realized? old)))
                                    old
                                    new-delay))
                                (fn [old]
                                  (cond
                                    (nil? old) new-delay
                                    (not (realized? old)) old
                                    (try
                                      (stale? (force old))
                                      (catch Exception e true)) new-delay
                                    :otherwise old)))
                    [kind data] (force (swap! state swap-fn))]
                (case kind
                  :exec-token-response
                  (assoc-in request [:headers "Authorization"] (str "Bearer " (get-in data [:status :token])))
                  :exec-client-key-response
                  (throw (ex-info "Modifying socket server from middleware isn't supported yet." {})))))]
      (update client-opts :middleware (fnil conj []) (retry-middleware gen-new-request)))))

(defmethod inject-client-auth :token-auth [client-opts {:keys [token]}]
  (letfn [(prepare-request [request]
            (assoc-in request [:headers "Authorization"] (str "Bearer " token)))]
    (update client-opts :middleware (fnil conj []) (normal-middleware prepare-request))))

(defmethod inject-client-auth :token-file-auth [client-opts {:keys [tokenFile]}]
  (let [state (atom nil)]
    (letfn [(gen-new-request [request force-new]
              (let [new-delay (delay (slurp tokenFile))
                    swap-fn   (if force-new
                                ; swap it out unless a new one is already pending
                                (fn [old]
                                  (if (and (some? old) (not (realized? old)))
                                    old
                                    new-delay))
                                ; swap it out only if it's not been set yet
                                #(or % new-delay))
                    new-token (force (swap! state swap-fn))]
                (assoc-in request [:headers "Authorization"] (str "Bearer " new-token))))]
      (update client-opts :middleware (fnil conj []) (retry-middleware gen-new-request)))))


(defmethod inject-client-auth :gcp-provider [client-opts user]
  (let [{:keys [access-token cmd-args cmd-path expiry expiry-key]} (get-in user [:auth-provider :config])]
    (letfn [(gen-new-request [request force-new]
              )]
      (update client-opts :middleware (fnil conj []) (retry-middleware gen-new-request)))))


(defmethod inject-client-auth :oidc-provider [client-opts user]
  (let [{:keys [client-id client-secret id-token idp-certificate-authority idp-issuer-url refresh-token]} (get-in user [:auth-provider :config])]
    (letfn [(gen-new-request [request force-new]
              )]
      (update client-opts :middleware (fnil conj []) (retry-middleware gen-new-request)))))

(comment

  (((first (:middleware
             (inject-client-auth
               {}
               {:username "paul"
                :password "hehehe"})))
    (fn [request] request)) {})

  )
