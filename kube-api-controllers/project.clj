(defproject kube-api/kube-api-controllers "0.1.0-SNAPSHOT"

  :description
  "A Kubernetes controller library for Clojure."

  :url
  "https://github.com/rutledgepaulv/kube-api/kube-api-controllers"

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
  [[org.clojure/clojure "1.10.3"]
   [kube-api/kube-api-core "0.1.2-SNAPSHOT"]
   [org.clojure/core.async "1.5.640"]]

  :profiles
  {:dev {:dependencies [[kube-api/kube-api-test "0.1.0-SNAPSHOT"]
                        [org.slf4j/slf4j-simple "1.7.32"]]}}

  :repl-options
  {:init-ns kube-api.controllers.controllers})
