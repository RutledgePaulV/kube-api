(defproject kube-api/kube-api-ring "0.1.0-SNAPSHOT"

  :description
  "A library for implementing "

  :url
  "https://github.com/rutledgepaulv/kube-api/kube-api-ring"

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
  [["releases" :clojars]
   ["snapshots" :clojars]]

  :dependencies
  [[org.clojure/clojure "1.10.1"]
   [kube-api/kube-api-controllers "0.1.0-SNAPSHOT"]
   [org.clojars.rutledgepaulv/ring-firewall-middleware "0.1.5"]]

  :profiles
  {:dev {:dependencies [[kube-api/kube-api-test "0.1.0-SNAPSHOT"]]}}

  :repl-options
  {:init-ns kube-api.ring.core})
