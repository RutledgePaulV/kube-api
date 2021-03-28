(ns kube-api.core.swagger.malli
  "Conversion from swagger's json-schema into malli schemas.
   Supports recursive definitions in the swagger schema by mapping
   to local malli registries."
  (:require [kube-api.core.utils :as utils]))

(def Base64Pattern #"^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$")
(def DateTimePattern #"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z")

(defn dispatch [node context registry]
  (try
    (cond

      ; this is a hack extension point in the
      ; AST so we can define custom malli
      ; schemas for a handful of swagger defs
      ; that are insufficiently specified (mainly
      ; around places where the k8s spec uses
      ; json-schema to define json-schema.. yay
      ; for meta schemas)
      (keyword? node)
      node

      (contains? node :$ref)
      "$ref"

      (contains? node :type)
      (get node :type)

      (true? (get node :x-kubernetes-int-or-string))
      "x-kubernetes-int-or-string"

      (true? (get node :x-kubernetes-preserve-unknown-fields))
      "x-kubernetes-preserve-unknown-fields"

      ; some swagger defs are missing type decls
      ; so we have to discover them using the
      ; shape i guess... RAWR
      (and (map? node) (contains? node :properties))
      "object")

    (catch Exception e
      (throw (ex-info "Error computing dispatch value." {:node node})))))

(defmulti
  swagger->malli*
  "A multimethod for converting from a swagger specification into
   a malli schema. Implementations should return a tuple of the new
   malli registry and the malli schema for the requested swagger node.

   Note that this is probably insufficient for general use as I've only
   written it to conform to the subset of json-schema / swagger that I
   see being returned by kubernetes api server implementations. Perhaps one
   day I will write a comprehensive version of this transform for json schema
   but I worry json schema is not followed rigorously enough in the real world
   to not require tailoring...
   "
  #'dispatch)

(def ^:dynamic *recurse* swagger->malli*)


(defmethod swagger->malli* :default [node context registry]
  (throw (ex-info "Undefined conversion! Teach me." {:node node})))

(defmethod swagger->malli* "$ref" [node context registry]
  (let [pointer       (get node :$ref)
        path          (utils/pointer->path pointer)
        definition    (get-in context path)
        identifier    (peek path)
        registry-name (name identifier)]
    (if (contains? registry registry-name)
      (do (when (utils/promise? (get registry registry-name))
            (deliver (get registry registry-name) true))
          [registry [:ref registry-name]])
      (let [prom           (promise)
            new-registry   (assoc registry registry-name prom)
            method         (get-method swagger->malli* identifier)
            default-method (get-method swagger->malli* :default)
            [child-reg child]
            (if-not (identical? method default-method)
              (method identifier context new-registry)
              (*recurse* definition context new-registry))]
        (if (realized? prom)
          [(update child-reg registry-name #(if (utils/promise? %1) child (or %1 child))) [:ref registry-name]]
          [(if-not (utils/promise? (get child-reg registry-name)) child-reg (dissoc child-reg registry-name)) child])))))

(defmethod swagger->malli* "array" [node context registry]
  (let [[child-registry child] (*recurse* (:items node) context registry)]
    [child-registry [:vector child]]))

(defmethod swagger->malli* "object" [node context registry]
  (let [props       (seq (:properties node {}))
        closed      (not (contains? node :additionalProperties))
        description (get node :description)]
    (let [required (set (map keyword (get-in node [:required])))]
      (cond
        (and (empty? props) closed)
        [registry
         (if description
           [:map-of {:description description} :string 'any?]
           [:map-of :string 'any?])]
        (and (empty? props) (not closed))
        (let [[child-registry child]
              (if (true? (:additionalProperties node))
                [registry [:map-of :string 'any?]]
                (*recurse* (:additionalProperties node) context registry))]
          [child-registry
           (if description
             [:map-of {:description description} :string child]
             [:map-of :string child])])
        (not-empty props)
        (let [children (map (fn [[k v]]
                              (let [[child-registry child] (*recurse* v context registry)
                                    description' (get v :description)]
                                {:registry child-registry
                                 :schema   [k (cond-> {:optional (not (contains? required k))}
                                                description' (assoc :description description')) child]}))
                            props)]
          [(reduce merge {} (map :registry children))
           (into
             [:map (cond-> {:closed closed}
                     description (assoc :description description))]
             (map :schema children))])))))

(defmethod swagger->malli* "string" [node context registry]
  [registry
   (case (:format node)
     "byte" [:re Base64Pattern]
     "date-time" [:re DateTimePattern]
     "int-or-string" [:or {:x-kubernetes-int-or-string true} :int [:re #"\d+"]]
     :string)])

(defmethod swagger->malli* "x-kubernetes-int-or-string" [node context registry]
  [registry [:or {:x-kubernetes-int-or-string true} :int :string]])

(defmethod swagger->malli* "x-kubernetes-preserve-unknown-fields" [node context registry]
  (swagger->malli* :io.k8s.apiextensions-apiserver.pkg.apis.apiextensions.v1.JSON context registry))

(defmethod swagger->malli* "integer" [node context registry]
  [registry :int])

(defmethod swagger->malli* "number" [node context registry]
  [registry
   (case (:format node)
     "double" :double
     :int)])

(defmethod swagger->malli* "boolean" [node context registry]
  [registry :boolean])

; kubernetes specific extensions because these swagger specs are insufficiently
; described to be automatically interpreted. because specs are best when they're
; only implemented 90% of the way, right?

(defmethod swagger->malli* :io.k8s.apimachinery.pkg.apis.meta.v1.Patch [node context registry]
  (let [definition
        [:or :boolean :int :double :string
         [:vector [:ref ":io.k8s.apimachinery.pkg.apis.meta.v1.Patch"]]
         [:map-of :string [:ref ":io.k8s.apimachinery.pkg.apis.meta.v1.Patch"]]]]
    [(merge registry {":io.k8s.apimachinery.pkg.apis.meta.v1.Patch" definition})
     [:ref ":io.k8s.apimachinery.pkg.apis.meta.v1.Patch"]]))

(utils/defmethodset swagger->malli*
  #{:io.k8s.apiextensions-apiserver.pkg.apis.apiextensions.v1.JSON}
  [_ context registry]
  (let [definition
        [:or :boolean :int :double :string
         [:vector [:ref "io.k8s.apiextensions-apiserver.pkg.apis.apiextensions.v1.JSON"]]
         [:map-of :string [:ref "io.k8s.apiextensions-apiserver.pkg.apis.apiextensions.v1.JSON"]]]]
    [(merge registry {"io.k8s.apiextensions-apiserver.pkg.apis.apiextensions.v1.JSON" definition})
     [:ref "io.k8s.apiextensions-apiserver.pkg.apis.apiextensions.v1.JSON"]]))

(utils/defmethodset swagger->malli*
  #{:io.k8s.apiextensions-apiserver.pkg.apis.apiextensions.v1beta1.JSON}
  [_ context registry]
  (let [definition
        [:or :boolean :int :double :string
         [:vector [:ref "io.k8s.apiextensions-apiserver.pkg.apis.apiextensions.v1beta1.JSON"]]
         [:map-of :string [:ref "io.k8s.apiextensions-apiserver.pkg.apis.apiextensions.v1beta1.JSON"]]]]
    [(merge registry {"io.k8s.apiextensions-apiserver.pkg.apis.apiextensions.v1beta1.JSON" definition})
     [:ref "io.k8s.apiextensions-apiserver.pkg.apis.apiextensions.v1beta1.JSON"]]))

(utils/defmethodset swagger->malli*
  #{:io.k8s.apiextensions-apiserver.pkg.apis.apiextensions.v1.JSONSchemaPropsOrBool}
  [_ context registry]
  (let [json-schema-props (get-in context [:definitions :io.k8s.apiextensions-apiserver.pkg.apis.apiextensions.v1.JSONSchemaProps])
        [child-registry child] (*recurse* json-schema-props context registry)]
    [child-registry [:or {:default true} child :boolean]]))

(utils/defmethodset swagger->malli*
  #{:io.k8s.apiextensions-apiserver.pkg.apis.apiextensions.v1beta1.JSONSchemaPropsOrBool}
  [_ context registry]
  (let [json-schema-props (get-in context [:definitions :io.k8s.apiextensions-apiserver.pkg.apis.apiextensions.v1beta1.JSONSchemaProps])
        [child-registry child] (*recurse* json-schema-props context registry)]
    [child-registry [:or {:default true} child :boolean]]))

(utils/defmethodset swagger->malli*
  #{:io.k8s.apiextensions-apiserver.pkg.apis.apiextensions.v1.JSONSchemaPropsOrArray}
  [_ context registry]
  (let [json-schema-props (get-in context [:definitions :io.k8s.apiextensions-apiserver.pkg.apis.apiextensions.v1.JSONSchemaProps])
        [child-registry child] (*recurse* json-schema-props context registry)]
    [child-registry [:or child [:vector child]]]))

(utils/defmethodset swagger->malli*
  #{:io.k8s.apiextensions-apiserver.pkg.apis.apiextensions.v1beta1.JSONSchemaPropsOrArray}
  [_ context registry]
  (let [json-schema-props (get-in context [:definitions :io.k8s.apiextensions-apiserver.pkg.apis.apiextensions.v1beta1.JSONSchemaProps])
        [child-registry child] (*recurse* json-schema-props context registry)]
    [child-registry [:or child [:vector child]]]))

(utils/defmethodset swagger->malli*
  #{:io.k8s.apiextensions-apiserver.pkg.apis.apiextensions.v1.JSONSchemaPropsOrStringArray}
  [_ context registry]
  (let [json-schema-props (get-in context [:definitions :io.k8s.apiextensions-apiserver.pkg.apis.apiextensions.v1.JSONSchemaProps])
        [child-registry child] (*recurse* json-schema-props context registry)]
    [child-registry [:or child [:vector :string]]]))

(utils/defmethodset swagger->malli*
  #{:io.k8s.apiextensions-apiserver.pkg.apis.apiextensions.v1beta1.JSONSchemaPropsOrStringArray}
  [_ context registry]
  (let [json-schema-props (get-in context [:definitions :io.k8s.apiextensions-apiserver.pkg.apis.apiextensions.v1beta1.JSONSchemaProps])
        [child-registry child] (*recurse* json-schema-props context registry)]
    [child-registry [:or child [:vector :string]]]))

(defn swagger->malli
  "How you exchange a swagger specification for a malli schema. Must specify
   the 'root' chunk of swagger spec that you want to convert into a schema."
  [swagger-spec root]
  (let [[registry schema]
        (binding [*recurse* (memoize swagger->malli*)]
          (swagger->malli* root swagger-spec {}))]
    (if (empty? registry) schema [:schema {:registry registry} schema])))
