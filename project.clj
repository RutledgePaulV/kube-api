(defproject org.clojars.rutledgepaulv/kube-api "0.1.0-SNAPSHOT"

  :dependencies
  [[org.clojure/clojure "1.10.1"]
   [clj-commons/clj-yaml "0.7.2"]
   [cheshire "5.10.0"]
   [clj-http "3.10.3"]]

  :repl-options
  {:init-ns kube-api.core})
