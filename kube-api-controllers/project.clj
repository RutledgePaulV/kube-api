(defproject kube-api/kube-api-controllers "0.1.0-SNAPSHOT"

  :description
  "A Kubernetes controller library for Clojure."

  :url
  "https://github.com/rutledgepaulv/kube-api/kube-api-controllers"

  :license
  {:name "MIT License" :url "http://opensource.org/licenses/MIT" :year 2020 :key "mit"}

  :scm
  {:name "git" :url "https://github.com/rutledgepaulv/kube-api"}


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
   [kube-api/kube-api-core "0.1.0-SNAPSHOT"]
   [org.clojure/core.async "1.3.610"]]

  :profiles
  {:dev {:dependencies [[lambdaisland/deep-diff2 "2.0.108"]]}})
