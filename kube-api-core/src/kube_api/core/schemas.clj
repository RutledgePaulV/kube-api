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
     [:installHint {:optional true} :string]
     [:args {:optional true}
      [:vector :string]]
     [:env {:optional true}
      [:vector
       [:map
        [:name :string]
        [:value :string]]]]]]])

(def gcp-provider-auth
  [:map {:dispatch-key :gcp-provider}
   [:auth-provider
    [:map
     [:config
      [:map
       [:access-token {:optional true} :string]
       [:cmd-args :string]
       [:cmd-path :string]
       [:expiry {:optional true} :number]
       [:expiry-key :string]
       [:token-key :string]]]
     [:name [:= "gcp"]]]]])

(def oidc-provider-auth
  [:map {:dispatch-key :oidc-provider}
   [:auth-provider
    [:map
     [:config
      [:map
       [:client-id :string]
       [:client-secret :string]
       [:id-token {:optional true} :string]
       [:idp-certificate-authority {:optional true} :string]
       [:idp-issuer-url :string]
       [:refresh-token {:optional true} :string]]]
     [:name [:= "oidc"]]]]])

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
   basic-auth
   gcp-provider-auth
   oidc-provider-auth])

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
    [:vector
     [:map
      [:name :string]
      [:cluster cluster-schema]]]]
   [:users
    [:vector
     [:map
      [:name :string]
      [:user user-schema]]]]
   [:contexts
    [:vector
     [:map
      [:name :string]
      [:context context-schema]]]]])

(def resolved-context-schema
  [:map
   [:namespace :string]
   [:user user-schema]
   [:cluster cluster-schema]])

(def exec-token-response
  [:map {:dispatch-key :exec-token-response}
   [:apiVersion :string]
   [:kind [:= "ExecCredential"]]
   [:status [:map
             [:token :string]
             [:expirationTimestamp {:optional true} :string]]]])

(def exec-client-key-response
  [:map {:dispatch-key :exec-client-key-response}
   [:apiVersion :string]
   [:kind [:= "ExecCredential"]]
   [:status [:map
             [:clientKeyData :string]
             [:clientCertificateData :string]
             [:expirationTimestamp {:optional true} :string]]]])

(def exec-response
  [:or exec-token-response exec-client-key-response])

(comment
  (m/validate
    kubeconfig-schema
    (yaml/parse-string
      (slurp (io/file (str (System/getProperty "user.home") "/.kube/config"))))))