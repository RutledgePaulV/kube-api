(defproject org.clojars.rutledgepaulv/kube-api-controllers "0.1.0-SNAPSHOT"

  :dependencies
  [[org.clojure/clojure "1.10.1"]
   [org.clojars.rutledgepaulv/kube-api-core "0.1.0-SNAPSHOT"]
   [org.clojure/core.async "1.3.610"]]

  :repl-options
  {:init-ns kube-api.core}

  :profiles
  {:dev {:dependencies [[com.gfredericks/test.chuck "0.2.10"]]}})
