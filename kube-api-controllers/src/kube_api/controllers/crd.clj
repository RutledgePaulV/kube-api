(ns kube-api.controllers.crd
  (:require [kube-api.core.core :as kube]
            [clojure.string :as strings]
            [malli.json-schema :as mjs]
            [kube-api.core.utils :as utils]
            [clojure.walk :as walk]
            [malli.core :as m]))

(def ^:dynamic *patched* false)

(remove-method mjs/accept :ref)
(remove-method mjs/accept :or)

(defmethod mjs/accept :ref [name _schema _children _options]
  (if *patched*
    (let [dereffed (m/deref _schema)]
      (if (identical? dereffed _schema)
        (mjs/-ref (m/-ref _schema))
        (mjs/-transform dereffed _options)))
    (mjs/-ref (m/-ref _schema))))

(defmethod mjs/accept :or [name _schema _children _options]
  (if (and *patched*
           (let [props (m/properties _schema)]
             (true? (:x-kubernetes-int-or-string props))))
    {:x-kubernetes-int-or-string true}
    {:anyOf _children}))

(defn malli->openapi3 [malli]
  (let [transformed (binding [*patched* true]
                      (mjs/transform malli))]
    (walk/postwalk
      (fn [form]
        (if (and (map? form) (= {} (get form :additionalProperties)))
          (assoc form :additionalProperties true)
          form))
      transformed)))

(defn find-refs [schema]
  (let [findings (atom #{})]
    (clojure.walk/postwalk
      (fn [form]
        (when (and (vector? form) (= :ref (first form)))
          (if (string? (second form))
            (swap! findings conj (second form))))
        form)
      schema)
    @findings))


(defn with-required-registry [client schema]
  (loop [old-registry {}
         new-registry (kube/malli-schemas client (find-refs schema))]
    (if (= old-registry new-registry)
      [:schema {:registry new-registry} schema]
      (recur new-registry (merge new-registry (kube/malli-schemas client (find-refs new-registry)))))))

(defn create-crd-definition [group name schema {:keys [plural version scope short-names] :as options}]
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
          (get-group []
            (strings/lower-case group))
          (get-kind []
            name)]
    {:apiVersion "apiextensions.k8s.io/v1"
     :kind       "CustomResourceDefinition"
     :metadata   {:name (get-fully-qualified-name)}
     :spec       {:group    (get-group)
                  :scope    (get-scope)
                  :versions [{:name    (get-version)
                              :served  true
                              :storage true
                              :schema  {:openAPIV3Schema (malli->openapi3 schema)}}]
                  :names    {:plural     (get-plural)
                             :singular   (get-singular)
                             :kind       (get-kind)
                             :shortNames (get-short-names)}}}))


(defn update-crd-definition [existing group name schema {:keys [plural version scope short-names] :as options}]
  (letfn [(get-plural []
            (strings/lower-case (or plural (str name "s"))))
          (get-group []
            (strings/lower-case group))
          (get-kind []
            name)
          (get-singular []
            (strings/lower-case name))
          (get-fully-qualified-name []
            (str (get-plural) "." (get-group)))
          (get-version []
            (or version "v1"))
          (get-short-names []
            (or short-names []))]
    (let [versions     (utils/index-by :name (get-in existing [:spec :versions]))
          new-version  {:name (get-version) :served true :schema {:openAPIV3Schema (malli->openapi3 schema)}}
          new-versions (update versions (get-version) (fnil merge {}) new-version)]
      {:apiVersion "apiextensions.k8s.io/v1"
       :kind       "CustomResourceDefinition"
       :metadata   {:name            (get-fully-qualified-name)
                    :resourceVersion (get-in existing [:metadata :resourceVersion])}
       :spec       {:group    (get-group)
                    :scope    (get-in existing [:spec :scope])
                    :versions (vec (vals new-versions))
                    :names    {:plural     (get-plural)
                               :singular   (get-singular)
                               :kind       (get-kind)
                               :shortNames (get-short-names)}}})))


(defn ensure-installed
  "Given a kubernetes client, an api group name, a crd name, and a malli schema describing
   the resource (only need to specify one top level key `spec` because metadata is specified
   by kubernetes itself), then install and/or update the resource definition in the cluster
   and return the response.

   (def friend-schema [:map [:spec [:favoriteColor [:enum \"Blue\" \"Green\"]]]])
   (ensure-installed client \"shippy.io\" \"Friend\" friend-schema)

   Intended for use by kubernetes controllers / operators that install their own schemas.
   "
  ([client group name schema]
   (ensure-installed client group name schema {}))
  ([client group name schema {:keys [plural version scope short-names] :as options}]
   (letfn [(exists? [response]
             (not= 404 (:code response)))
           (conflicts? [response]
             (= 409 (:code response)))
           (get-resource [name]
             (let [op-selector {:kind "CustomResourceDefinition" :action "get"}
                   request     {:path-params {:name name}}]
               (kube/invoke client op-selector request)))
           (create-resource [create-payload]
             (let [op-selector {:kind "CustomResourceDefinition" :action "create"}
                   request     {:body create-payload}]
               (kube/invoke client op-selector request)))
           (update-resource [update-payload]
             (let [op-selector {:kind "CustomResourceDefinition" :action "update"}
                   request     {:body        update-payload
                                :path-params {:name (get-in update-payload [:metadata :name])}}]
               (kube/invoke client op-selector request)))]
     (let [final-schema   (with-required-registry client schema)
           create-payload (create-crd-definition group name final-schema options)
           existing       (get-resource (get-in create-payload [:metadata :name]))
           response       (if (exists? existing)
                            (update-resource (update-crd-definition existing group name final-schema options))
                            (create-resource create-payload))]
       (if (conflicts? response)
         (recur client group name final-schema options)
         response)))))