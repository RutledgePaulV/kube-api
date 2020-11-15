(ns kube-api.auth
  (:require [clojure.string :as strings]
            [clj-yaml.core :as yaml]
            [clojure.java.io :as io]
            [kube-api.utils :as utils])
  (:import [java.io File]))

(defn readable? [^File f]
  (and (.exists f) (.canRead f)))

(defn kubeconfig-files []
  (let [home (System/getProperty "user.home")
        csv  (or (System/getenv "KUBECONFIG") "~/.kube/config")]
    (->> (strings/split csv #",")
         (remove strings/blank?)
         (map #(strings/replace % #"^~" home))
         (map io/file)
         (filter readable?))))

(defn get-merged-kubeconfig []
  (->> (kubeconfig-files)
       (map slurp)
       (map yaml/parse-string)
       (apply utils/merge+)))

(defn service-account []
  (let [namespace (io/file "/run/secrets/kubernetes.io/serviceaccount/namespace")
        ca-cert   (io/file "/run/secrets/kubernetes.io/serviceaccount/ca.crt")
        token     (io/file "/run/secrets/kubernetes.io/serviceaccount/token")]
    (when (and (readable? namespace) (readable? ca-cert) (readable? token)
               (not (strings/blank? (System/getenv "KUBERNETES_SERVICE_HOST")))
               (not (strings/blank? (System/getenv "KUBERNETES_SERVICE_PORT_HTTPS"))))
      (let [token      (slurp token)
            namespace  (slurp namespace)
            ca-cert    (slurp ca-cert)
            host       (System/getenv "KUBERNETES_SERVICE_HOST")
            port       (System/getenv "KUBERNETES_SERVICE_PORT_HTTPS")
            endpoint   (cond-> (str "https://" host)
                         (not= port "443") (str ":" port))
            clean-cert (utils/pem-body ca-cert)]
        {:user      {:token token}
         :cluster   {:certificate-authority-data clean-cert
                     :server                     endpoint}
         :namespace namespace}))))

(defn select-context
  ([context-name]
   (select-context (get-merged-kubeconfig) context-name))
  ([kubeconfig context-name]
   (let [contexts (utils/index-by :name (:contexts kubeconfig))
         clusters (utils/index-by :name (:clusters kubeconfig))
         users    (utils/index-by :name (:users kubeconfig))]
     (when-some [context (get contexts context-name)]
       (let [cluster-name (get-in context [:context :cluster])
             user-name    (get-in context [:context :user])
             cluster      (get-in clusters [cluster-name :cluster])
             user         (get-in users [user-name :user])
             namespace    (or (get-in context [:context :namespace]) "default")]
         {:user (into {} user) :cluster (into {} cluster) :namespace namespace})))))

(defn current-context
  ([] (current-context (get-merged-kubeconfig)))
  ([kubeconfig]
   (select-context kubeconfig (:current-context kubeconfig))))

(defn get-context []
  (or (service-account) (current-context)))

(def token-user-schema
  [:map
   [:client-key-data {:optional true} :string]
   [:client-certificate-data {:optional true} :string]
   [:token :string]])

(def basic-auth-user-schema
  [:map
   [:client-key-data {:optional true} :string]
   [:client-certificate-data {:optional true} :string]
   [:username :string]
   [:password :string]])

(def user-schema
  [:or
   token-user-schema
   basic-auth-user-schema])

(def cluster-schema
  [:map
   [:server :string]
   [:certificate-authority-data {:optional true} :string]])

(def context-schema
  [:map
   [:user user-schema]
   [:cluster cluster-schema]
   [:namespace {:optional true} :string]])