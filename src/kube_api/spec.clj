(ns kube-api.spec
  (:require [kube-api.utils :as utils]))

(def Base64Pattern #"^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$")
(def DateTimePattern #"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z")

(defn dispatch [node context registry]
  (cond
    (keyword? node)
    node

    (contains? node :$ref)
    "$ref"

    (contains? node :type)
    (get node :type)))

(defmulti
  swagger->malli*
  "A multimethod for converting from a swagger specification into
   a malli schema. Implementations should return a tuple of the new
   malli registry and the malli schema for the requested swagger node."
  #'dispatch)

(defmethod swagger->malli* :default [node context registry]
  (throw (ex-info "Undefined conversion!" {:node node})))

(defmethod swagger->malli* "$ref" [node context registry]
  (let [pointer    (get node :$ref)
        path       (utils/pointer->path pointer)
        definition (get-in context path)
        identifier (peek path)]
    (if (contains? registry identifier)
      [registry [:ref identifier]]
      (let [new-registry   (assoc registry identifier nil)
            method         (get-method swagger->malli* identifier)
            default-method (get-method swagger->malli* :default)
            [child-reg child]
            (if-not (identical? method default-method)
              (method identifier context new-registry)
              (swagger->malli* definition context new-registry))]

        ; this is much faster, but it produces schemas with more registry
        ; indirection than is actually required since it represents an
        ; eager approach to registry registration
        #_[(update child-reg identifier #(or % child)) [:ref identifier]]

        ; this is slow since it backtracks over the generated schema
        ; to see if it actually used the available recursive reference
        ; and if it did not then it removes the definition from the registry
        (if (utils/dfs #(= [:ref identifier] %) child)
          [(update child-reg identifier #(or % child)) [:ref identifier]]
          [(if (get child-reg identifier) child-reg (dissoc child-reg identifier)) child])))))

(defmethod swagger->malli* "array" [node context registry]
  (let [[child-registry child] (swagger->malli* (:items node) context registry)]
    [child-registry [:vector child]]))

(defmethod swagger->malli* "object" [node context registry]
  (let [props       (seq (:properties node {}))
        closed      (not (contains? node :additionalProperties))
        description (get node :description)]
    (let [required (set (map keyword (get-in node [:required])))]
      (cond
        (and (empty? props) closed)
        [registry
         [:map-of (cond-> {} description (assoc :description description))
          :string
          'any?]]
        (and (empty? props) (not closed))
        (let [[child-registry child]
              (swagger->malli* (:additionalProperties node) context registry)]
          [child-registry
           [:map-of (cond-> {} description (assoc :description description))
            :string
            child]])
        (not-empty props)
        (let [children (map (fn [[k v]]
                              (let [[child-registry child] (swagger->malli* v context registry)
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
     "int-or-string" [:or :int [:re #"\d+"]]
     :string)])

(defmethod swagger->malli* "integer" [node context registry]
  [registry :int])

(defmethod swagger->malli* "number" [node context registry]
  [registry
   (case (:format node)
     "double" :double
     :int)])

(defmethod swagger->malli* "boolean" [node context registry]
  [registry :boolean])



(defn swagger->malli
  "How you exchange a swagger specification for a malli schema. Must specify
   the 'root' chunk of swagger spec that you want to convert into a schema."
  [swagger-spec root]
  (let [[registry schema] (swagger->malli* root swagger-spec {})]
    (if (empty? registry) schema [:schema {:registry registry} schema])))



; kubernetes specific extensions because some of their swagger specs are insufficiently described

(defmethod swagger->malli* :io.k8s.apiextensions-apiserver.pkg.apis.apiextensions.v1.JSON [_ context registry]
  [registry [:or :bool :int :double :string [:vector]]])

(defmethod swagger->malli* :io.k8s.apiextensions-apiserver.pkg.apis.apiextensions.v1.JSONSchemaPropsOrBool [_ context registry]
  (let [json-schema-props (get-in context [:definitions :io.k8s.apiextensions-apiserver.pkg.apis.apiextensions.v1.JSONSchemaProps])
        [child-registry child] (swagger->malli* json-schema-props context registry)]
    [child-registry [:or {:default true} :boolean child]]))

(defmethod swagger->malli* :io.k8s.apiextensions-apiserver.pkg.apis.apiextensions.v1.JSONSchemaPropsOrArray [_ context registry]
  (let [json-schema-props (get-in context [:definitions :io.k8s.apiextensions-apiserver.pkg.apis.apiextensions.v1.JSONSchemaProps])
        [child-registry child] (swagger->malli* json-schema-props context registry)]
    [child-registry [:or [:vector child] child]]))

(defmethod swagger->malli* :io.k8s.apiextensions-apiserver.pkg.apis.apiextensions.v1.JSONSchemaPropsOrStringArray [_ context registry]
  (let [json-schema-props (get-in context [:definitions :io.k8s.apiextensions-apiserver.pkg.apis.apiextensions.v1.JSONSchemaProps])
        [child-registry child] (swagger->malli* json-schema-props context registry)]
    [child-registry [:or [:vector :string] child]]))

(defmethod swagger->malli* :io.k8s.apiextensions-apiserver.pkg.apis.apiextensions.v1beta1.JSON [_ context registry]
  (swagger->malli* :io.k8s.apiextensions-apiserver.pkg.apis.apiextensions.v1.JSON context registry))

(defmethod swagger->malli* :io.k8s.apiextensions-apiserver.pkg.apis.apiextensions.v1beta1.JSONSchemaPropsOrBool [_ context registry]
  (swagger->malli* :io.k8s.apiextensions-apiserver.pkg.apis.apiextensions.v1.JSONSchemaPropsOrBool context registry))

(defmethod swagger->malli* :io.k8s.apiextensions-apiserver.pkg.apis.apiextensions.v1beta1.JSONSchemaPropsOrArray [_ context registry]
  (swagger->malli* :io.k8s.apiextensions-apiserver.pkg.apis.apiextensions.v1.JSONSchemaPropsOrArray context registry))

(defmethod swagger->malli* :io.k8s.apiextensions-apiserver.pkg.apis.apiextensions.v1beta1.JSONSchemaPropsOrStringArray [_ context registry]
  (swagger->malli* :io.k8s.apiextensions-apiserver.pkg.apis.apiextensions.v1.JSONSchemaPropsOrStringArray context registry))
