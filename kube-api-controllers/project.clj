(defproject org.clojars.rutledgepaulv/kube-api-controllers "0.1.0-SNAPSHOT"

  :description
  "A Kubernetes controller library for Clojure."

  :url
  "https://github.com/rutledgepaulv/kube-api"

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
   [org.clojars.rutledgepaulv/kube-api-core "0.1.0-SNAPSHOT"]
   [org.clojure/core.async "1.3.610"]])
