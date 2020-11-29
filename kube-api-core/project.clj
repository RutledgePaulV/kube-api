(defproject kube-api/kube-api-core "0.1.0-SNAPSHOT"

  :description
  "A Kubernetes API client for Clojure."

  :url
  "https://github.com/rutledgepaulv/kube-api/kube-api-core"

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
   [clj-commons/clj-yaml "0.7.2"]
   [metosin/malli "753a99933ba4e6d4a257a1b0f9715e68b90fa7f8"]
   [org.clojure/tools.logging "1.1.0"]
   [org.clojars.rutledgepaulv/clj-okhttp "0.1.0-SNAPSHOT"]]

  :plugins
  [[reifyhealth/lein-git-down "0.3.7"]]

  :repositories
  [["public-github" {:url "git://github.com"}]]

  :repl-options
  {:init-ns kube-api.core.core}

  :profiles
  {:dev {:dependencies [[com.gfredericks/test.chuck "0.2.10"]
                        [kube-api/kube-api-test "0.1.0-SNAPSHOT"]]}})
