(ns kube-api.core.kubeconfig
  (:require [clojure.string :as strings]
            [clj-yaml.core :as yaml]
            [clojure.java.io :as io]
            [kube-api.core.utils :as utils]
            [clojure.walk :as walk])
  (:import [flatland.ordered.map OrderedMap]
           [java.io File]))

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

(defn normalize [x]
  (walk/postwalk
    (fn [form]
      (cond
        (sequential? form) (into [] form)
        (instance? OrderedMap form) (into {} form)
        :otherwise form))
    x))

(defn read-kubeconfig [file]
  (let [data (yaml/parse-string (slurp file))]
    (update data :contexts (fn [contexts] (mapv #(assoc % :file (.getAbsolutePath %)) contexts)))))

(defn get-merged-kubeconfig []
  (->> (kubeconfig-files)
       (map read-kubeconfig)
       (apply utils/merge+)
       (normalize)))

(defn service-account []
  (let [service-host (System/getenv "KUBERNETES_SERVICE_HOST")
        service-port (System/getenv "KUBERNETES_SERVICE_PORT_HTTPS")]
    (when (and (not (strings/blank? service-host)) (not (strings/blank? service-port)))
      (let [endpoint  (cond-> (str "https://" service-host)
                        (not= service-port "443") (str ":" service-port))
            namespace (io/file "/run/secrets/kubernetes.io/serviceaccount/namespace")
            ca-cert   (io/file "/run/secrets/kubernetes.io/serviceaccount/ca.crt")
            token     (io/file "/run/secrets/kubernetes.io/serviceaccount/token")]
        (if (and (readable? namespace) (readable? ca-cert) (readable? token))
          {:user      {:tokenFile "/run/secrets/kubernetes.io/serviceaccount/token"}
           :cluster   {:certificate-authority-data (slurp ca-cert) :server endpoint}
           :namespace (slurp namespace)}
          (throw (ex-info "Believed to be running in kubernetes but could not locate service account files." {})))))))

(defn select-context
  ([context-name]
   (select-context (get-merged-kubeconfig) context-name))
  ([kubeconfig context-name]
   (let [contexts (utils/index-by :name (:contexts kubeconfig))
         clusters (utils/index-by :name (:clusters kubeconfig))
         users    (utils/index-by :name (:users kubeconfig))]
     (if-some [context (get contexts context-name)]
       (let [cluster-name (get-in context [:context :cluster])
             user-name    (get-in context [:context :user])
             cluster      (get-in clusters [cluster-name :cluster])
             user         (get-in users [user-name :user])
             namespace    (or (get-in context [:context :namespace]) "default")]
         {:user user :cluster cluster :namespace namespace})
       (throw (ex-info (format "No kubernetes context could be found with name %s" context-name) {}))))))

(defn current-context
  "Obtains the best matching kubernetes context."
  ([]
   (or (service-account) (current-context (get-merged-kubeconfig))))
  ([kubeconfig]
   (cond
     ; grab the one marked current
     (not (strings/blank? (:current-context kubeconfig)))
     (select-context kubeconfig (:current-context kubeconfig))
     ; otherwise if there's just one, use that
     (= 1 (count (:contexts kubeconfig)))
     (select-context kubeconfig (get-in kubeconfig [:contexts 0 :name]))
     ; otherwise it's ambiguous
     :otherwise
     (throw (ex-info "No default kubernetes context could be identified." {})))))