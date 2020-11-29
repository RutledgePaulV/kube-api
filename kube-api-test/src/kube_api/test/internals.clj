(ns kube-api.test.internals
  (:require [clojure.java.io :as io]
            [clojure.string :as strings])
  (:import [java.util Properties AbstractMap$SimpleEntry]
           [org.testcontainers.containers GenericContainer]
           [org.testcontainers.images.builder ImageFromDockerfile]
           [org.testcontainers.utility ResourceReaper]))


(def get-jar-version
  (memoize
    (fn [dep]
      (let [segment0 "META-INF/maven"
            segment1 (or (namespace dep) (name dep))
            segment2 (name dep)
            segment3 "pom.properties"
            path     (strings/join "/" [segment0 segment1 segment2 segment3])
            props    (io/resource path)]
        (when props
          (with-open [stream (io/input-stream props)]
            (let [props (doto (Properties.) (.load stream))]
              (.getProperty props "version"))))))))

(defn kind-image-name []
  (let [version (get-jar-version 'kube-api/kube-api-test)]
    (str "kube-api-test/kind-cli:" version)))

(defn new-kind-cli-container []
  (doto (GenericContainer.
          (doto (ImageFromDockerfile. (kind-image-name) false)
            (.withFileFromClasspath "Dockerfile" "kube_api/test/Dockerfile")))
    (.setCommand ^"[Ljava.lang.String;" (into-array String ["tail" "-f" "/dev/null"]))
    (.withFileSystemBind "/var/run/docker.sock" "/var/run/docker.sock")
    (.setPrivilegedMode true)
    (.start)))

(defn register-cluster-for-removal [cluster-name]
  (doto (ResourceReaper/instance)
    (.registerFilterForCleanup
      [(AbstractMap$SimpleEntry. "label" (str "io.x-k8s.kind.cluster=" cluster-name))])))

(defn cleanup-yaml-formatting [s]
  (letfn [(replace [match]
            (str (strings/replace (first match) "\n" "") "\n"))]
    (strings/replace s #":\s+([^:]+\n)+" replace)))