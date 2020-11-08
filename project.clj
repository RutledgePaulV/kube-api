(defproject org.clojars.rutledgepaulv/kube-api "0.1.0-SNAPSHOT"

  :dependencies
  [[org.clojure/clojure "1.10.1"]
   [clj-commons/clj-yaml "0.7.2"]
   [metosin/malli "0.2.1"]
   [org.clojure/tools.logging "1.1.0"]
   [org.clojars.rutledgepaulv/clj-okhttp "0.1.0-SNAPSHOT"]]

  :repl-options
  {:init-ns kube-api.core}

  :profiles
  {:dev {:dependencies [[com.gfredericks/test.chuck "0.2.10"]]}})
