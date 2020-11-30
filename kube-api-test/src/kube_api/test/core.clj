(ns kube-api.test.core
  "Uses docker / test containers and kind (https://kind.sigs.k8s.io/) to
   bootstrap kubernetes clusters in docker for testing purposes."
  (:require [clojure.string :as strings]
            [kube-api.test.internals :as internal]
            [clj-yaml.core :as yaml])
  (:import [org.testcontainers.containers GenericContainer]
           [org.testcontainers.images.builder Transferable]
           [org.testcontainers.utility ResourceReaper]))

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

(defn spit-file [^String filepath ^String content]
  (let [container ^GenericContainer (force kind-cli)
        file      (Transferable/of (.getBytes content))]
    (.copyFileToContainer container file filepath)))

(defn delete-cluster [cluster-name]
  (kind "delete" "cluster" "--name" cluster-name))

(defn delete-cluster-on-shutdown [cluster-name]
  (doto (ResourceReaper/instance)
    (.registerFilterForCleanup (seq {"label" (str "io.x-k8s.kind.cluster=" cluster-name)}))))

(defn create-cluster
  ([cluster-name]
   (create-cluster cluster-name nil))
  ([cluster-name cluster-config]
   (if (not-empty cluster-config)
     (let [config-file-path (str "/root/" (name (gensym "kubeconfig")))
           yaml-encoded     (yaml/generate-string cluster-config :dumper-options {:flow-style :block})]
       (spit-file config-file-path yaml-encoded)
       (kind "create" "cluster" "--name" cluster-name "--config" config-file-path))
     (kind "create" "cluster" "--name" cluster-name))))

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

(defonce CLUSTERS (atom {}))

(defn get-or-init-test-cluster
  ([cluster-name]
   (get-or-init-test-cluster cluster-name nil))
  ([cluster-name configuration]
   (let [construct
         (delay (delete-cluster-on-shutdown cluster-name)
                (create-cluster cluster-name configuration)
                (get-kube-config cluster-name))]
     (-> CLUSTERS
         (swap! update cluster-name (fn [old] (or old construct)))
         (get cluster-name)
         (force)))))