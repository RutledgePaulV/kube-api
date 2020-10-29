(ns kube-api.swagger.malli
  "Conversion from swagger's json-schema into malli schemas.
   Supports recursive definitions in the swagger schema by mapping
   to local malli registries."
  (:require [kube-api.utils :as utils]))

(def Base64Pattern #"^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$")
(def DateTimePattern #"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z")

(defn dispatch [node context registry]
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

    ; some swagger defs are missing type decls
    ; so we have to discover them using the
    ; shape i guess... RAWR
    (and (map? node) (contains? node :properties))
    "object"))

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

(defmethod swagger->malli* :default [node context registry]
  (throw (ex-info "Undefined conversion! Teach me." {:node node})))

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
         (if description
           [:map-of {:description description} :string 'any?]
           [:map-of :string 'any?])]
        (and (empty? props) (not closed))
        (let [[child-registry child]
              (swagger->malli* (:additionalProperties node) context registry)]
          [child-registry
           (if description
             [:map-of {:description description} :string child]
             [:map-of :string child])])
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
