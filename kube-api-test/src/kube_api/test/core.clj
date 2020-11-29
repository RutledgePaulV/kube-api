(ns kube-api.test.core
  "Uses docker / test containers and kind (https://kind.sigs.k8s.io/) to
   bootstrap kubernetes clusters in docker for testing purposes."
  (:require [clojure.string :as strings]
            [kube-api.test.internals :as internal]
            [clj-yaml.core :as yaml]))

(defonce kind-cli
  (delay (internal/new-kind-cli-container)))

(defn kind [& commands]
  (let [args        (into-array String (into ["kind"] (map name) commands))
        exec-result (.execInContainer (force kind-cli) args)
        data        {:exit   (.getExitCode exec-result)
                     :stdout (.getStdout exec-result)
                     :stderr (.getStderr exec-result)}]
    (if (zero? (.getExitCode exec-result))
      data
      (throw (ex-info "Exception invoking kind command." data)))))

(defn delete-cluster [cluster-name]
  (kind "delete" "cluster" "--name" cluster-name))

(defn create-cluster [cluster-name]
  (internal/register-cluster-for-removal cluster-name)
  (kind "create" "cluster" "--name" cluster-name))

(defn list-clusters []
  (let [{:keys [stdout stderr]} (kind "get" "clusters")]
    (if-not (strings/blank? stdout)
      (set (map strings/trim (strings/split-lines stdout)))
      #{})))

(defn list-nodes [cluster-name]
  (let [{:keys [stdout stderr]} (kind "get" "nodes" "--name" cluster-name)]
    (if-not (strings/blank? stdout)
      (set (map strings/trim (strings/split-lines stdout)))
      #{})))

(defn get-kube-config [cluster-name]
  (let [{:keys [stdout]} (kind "get" "kubeconfig" "--name" cluster-name)]
    (yaml/parse-string (internal/cleanup-yaml-formatting stdout))))