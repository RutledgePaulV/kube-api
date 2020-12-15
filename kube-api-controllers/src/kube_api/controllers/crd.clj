(ns kube-api.controllers.crd
  (:require [kube-api.core.core :as kube]
            [clojure.string :as strings]
            [malli.json-schema :as mjs]))


(defn ensure-installed
  ([client group name schema]
   (ensure-installed client group name schema {}))
  ([client group name schema {:keys [plural version scope short-names] :as options}]
   (letfn [(get-plural []
             (strings/lower-case (or plural (str name "s"))))
           (get-singular []
             (strings/lower-case name))
           (get-fully-qualified-name []
             (str (get-plural) "." group))
           (get-version []
             (or version "v1"))
           (get-scope []
             (or scope "Namespaced"))
           (get-short-names []
             (or short-names []))
           (exists? [response]
             (not= 404 (:code response)))
           (conflicts? [response]
             (= 409 (:code response)))
           (malli->openapi3 [malli-schema]
             (mjs/transform malli-schema))
           (index-by [f coll]
             (into {} (map (juxt f identity)) coll))
           (create-payload []
             {:apiVersion "apiextensions.k8s.io/v1"
              :kind       "CustomResourceDefinition"
              :metadata   {:name (get-fully-qualified-name)}
              :spec       {:group    group
                           :scope    (get-scope)
                           :versions [{:name    (get-version)
                                       :served  true
                                       :storage true
                                       :schema  {:openAPIV3Schema (malli->openapi3 schema)}}]
                           :names    {:plural     (get-plural)
                                      :singular   (get-singular)
                                      :kind       name
                                      :shortNames (get-short-names)}}})
           (update-payload [existing]
             (let [versions     (index-by :name (get-in existing [:spec :versions]))
                   new-version  {:name (get-version) :served true :schema {:openAPIV3Schema (malli->openapi3 schema)}}
                   new-versions (update versions (get-version) (fnil merge {}) new-version)]
               {:apiVersion "apiextensions.k8s.io/v1"
                :kind       "CustomResourceDefinition"
                :metadata   {:name            (get-fully-qualified-name)
                             :resourceVersion (get-in existing [:metadata :resourceVersion])}
                :spec       {:group    group
                             :scope    (get-in existing [:spec :scope])
                             :versions (vec (vals new-versions))
                             :names    {:plural     (get-plural)
                                        :singular   (get-singular)
                                        :kind       name
                                        :shortNames (get-short-names)}}}))
           (get-resource []
             (let [op-selector {:kind "CustomResourceDefinition" :action "get"}
                   request     {:path-params {:name (get-fully-qualified-name)}}]
               (kube/invoke client op-selector request)))
           (create-resource []
             (let [op-selector {:kind "CustomResourceDefinition" :action "create"}
                   request     {:body (create-payload)}]
               (kube/invoke client op-selector request)))
           (update-resource [existing]
             (let [op-selector {:kind "CustomResourceDefinition" :action "update"}
                   request     {:body        (update-payload existing)
                                :path-params {:name (get-in existing [:metadata :name])}}]
               (kube/invoke client op-selector request)))]
     (let [existing (get-resource)
           response (if (exists? existing)
                      (update-resource existing)
                      (create-resource))]
       (if (conflicts? response)
         (ensure-installed client group name schema options)
         response)))))