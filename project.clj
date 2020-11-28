(defproject kube-api/kube-api "0.1.0-SNAPSHOT"

  :description
  "A set of Kubernetes libraries for Clojure."

  :url
  "https://github.com/rutledgepaulv/kube-api"

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

  :source-paths
  []

  :test-paths
  []

  :dependencies
  [[kube-api/kube-api-core "0.1.0-SNAPSHOT"]
   [kube-api/kube-api-controllers "0.1.0-SNAPSHOT"]
   [kube-api/kube-api-term "0.1.0-SNAPSHOT"]
   [kube-api/kube-api-io "0.1.0-SNAPSHOT"]]

  :plugins
  [[lein-sub "0.3.0"]]

  :sub
  ["kube-api-core" "kube-api-controllers" "kube-api-io" "kube-api-term"]

  :aliases
  {"test"    ["sub" "test"]
   "deploy"  ["sub" "deploy"]
   "install" ["sub" "install"]})
