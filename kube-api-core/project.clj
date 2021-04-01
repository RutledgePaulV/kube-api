(defproject kube-api/kube-api-core "0.1.1"

  :description
  "A Kubernetes API client for Clojure."

  :url
  "https://github.com/rutledgepaulv/kube-api/kube-api-core"

  :license
  {:name "MIT License" :url "http://opensource.org/licenses/MIT" :year 2020 :key "mit"}

  :scm
  {:dir ".."}

  :pom-addition
  [:developers
   [:developer
    [:name "Paul Rutledge"]
    [:url "https://github.com/rutledgepaulv"]
    [:email "rutledgepaulv@gmail.com"]
    [:timezone "-5"]]]

  :deploy-repositories
  [["releases" :clojars] ["snapshots" :clojars]]

  :dependencies
  [[org.clojure/clojure "1.10.1"]
   [clj-commons/clj-yaml "0.7.2"]
   [metosin/malli "0.3.1"]
   [org.clojure/tools.logging "1.1.0"]
   [clj-okhttp/clj-okhttp "0.1.0"]]

  :repl-options
  {:init-ns kube-api.core.core}

  :profiles
  {:dev {:resource-paths ["testfiles"]
         :dependencies   [[com.gfredericks/test.chuck "0.2.10"]
                          [kube-api/kube-api-test "0.1.0-SNAPSHOT"]
                          [org.slf4j/slf4j-simple "1.7.30"]]}})
