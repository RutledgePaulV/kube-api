(defproject kube-api/kube-api-test "0.1.0-SNAPSHOT"

  :dependencies
  [[org.clojure/clojure "1.10.1"]
   [org.testcontainers/testcontainers "1.15.0"]
   [clj-commons/clj-yaml "0.7.2"]]

  :repl-options
  {:init-ns kube-api.test.core}

  :profiles
  {:dev {:dependencies [[org.slf4j/slf4j-simple "1.7.30"]]}})
