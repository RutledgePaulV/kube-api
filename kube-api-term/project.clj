(defproject org.clojars.rutledgepaulv/kube-api-term "0.1.0-SNAPSHOT"

  :description
  "A library for opening emulated terminals into kubernetes pods from Clojure."

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

  :repositories
  [["jcenter" {:url "https://jcenter.bintray.com"}]]

  :deploy-repositories
  [["releases" :clojars]
   ["snapshots" :clojars]]

  :aot
  [kube-api.term.term]

  :dependencies
  [[org.clojure/clojure "1.10.1"]
   [org.clojars.rutledgepaulv/kube-api-core "0.1.0-SNAPSHOT"]
   [org.jetbrains.jediterm/jediterm-pty "2.31"]
   [com.google.guava/guava "30.0-jre"]
   [com.formdev/flatlaf "0.44"]
   [log4j/log4j "1.2.17"]])
