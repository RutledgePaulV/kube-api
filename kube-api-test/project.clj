(defproject kube-api/kube-api-test "0.1.0-SNAPSHOT"

  :description
  "A library for testing kubernetes libraries and applications from Clojure."

  :url
  "https://github.com/rutledgepaulv/kube-api/kube-api-test"

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
  [["releases" :clojars]
   ["snapshots" :clojars]]

  :dependencies
  [[org.clojure/clojure "1.10.3"]
   [clj-commons/clj-yaml "0.7.107"]
   [org.testcontainers/testcontainers "1.16.2"]]

  :repl-options
  {:init-ns kube-api.test.core}

  :profiles
  {:dev {:dependencies [[org.slf4j/slf4j-simple "1.7.32"]]}})
