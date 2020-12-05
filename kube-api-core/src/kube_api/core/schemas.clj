(ns kube-api.core.schemas
  (:require [malli.core :as m]
            [clj-yaml.core :as yaml]
            [clojure.java.io :as io]))

(def token-file-auth
  [:map {:dispatch-key :token-file-auth}
   [:tokenFile :string]])

(def client-key-auth
  [:map {:dispatch-key :client-key-auth}
   [:client-key-data :string]
   [:client-certificate-data :string]])

(def exec-auth
  [:map {:dispatch-key :exec-auth}
   [:exec
    [:map
     [:apiVersion :string]
     [:command :string]
     [:args {:optional true}
      [:vector :string]]
     [:env {:optional true}
      [:vector
       [:map
        [:name :string]
        [:value :string]]]]]]])

(def token-auth
  [:map {:dispatch-key :token-auth}
   [:token :string]])

(def basic-auth
  [:map {:dispatch-key :basic-auth}
   [:username :string]
   [:password :string]])

(def user-schema
  [:or
   client-key-auth
   exec-auth
   token-file-auth
   token-auth
   basic-auth])

(def cluster-schema
  [:map
   [:server :string]
   [:certificate-authority-data {:optional true} :string]])

(def context-schema
  [:map
   [:user :string]
   [:cluster :string]
   [:namespace {:optional true :default "default"} :string]])

(def kubeconfig-schema
  [:map
   [:apiVersion :string]
   [:kind [:= "Config"]]
   [:current-context :string]
   [:preferences [:map-of :string 'any?]]
   [:clusters
    [:sequential
     [:map
      [:name :string]
      [:cluster cluster-schema]]]]
   [:users
    [:sequential
     [:map
      [:name :string]
      [:user user-schema]]]]
   [:contexts
    [:sequential
     [:map
      [:name :string]
      [:context context-schema]]]]])

(def resolved-context-schema
  [:map
   [:namespace :string]
   [:user user-schema]
   [:cluster cluster-schema]])

(comment
  (m/validate
    kubeconfig-schema
    (yaml/parse-string
      (slurp (io/file (str (System/getProperty "user.home") "/.kube/config"))))))