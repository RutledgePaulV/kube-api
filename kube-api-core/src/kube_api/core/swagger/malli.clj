(ns kube-api.core.swagger.malli
  "Conversion from swagger's json-schema into malli schemas.
   Supports recursive definitions in the swagger schema by mapping
   to local malli registries."
  (:require [kube-api.core.utils :as utils]))

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

    (true? (get node :x-kubernetes-int-or-string))
    "x-kubernetes-int-or-string"

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

(def ^:dynamic *recurse* swagger->malli*)


(defmethod swagger->malli* :default [node context registry]
  (throw (ex-info "Undefined conversion! Teach me." {:node node})))

(defmethod swagger->malli* "$ref" [node context registry]
  (let [pointer    (get node :$ref)
        path       (utils/pointer->path pointer)
        definition (get-in context path)
        identifier (peek path)]
    (if (contains? registry identifier)
      (do (when (utils/promise? (get registry identifier))
            (deliver (get registry identifier) true))
          [registry [:ref identifier]])
      (let [prom           (promise)
            new-registry   (assoc registry identifier prom)
            method         (get-method swagger->malli* identifier)
            default-method (get-method swagger->malli* :default)
            [child-reg child]
            (if-not (identical? method default-method)
              (method identifier context new-registry)
              (*recurse* definition context new-registry))]
        (if (realized? prom)
          [(update child-reg identifier #(if (utils/promise? %1) child (or %1 child))) [:ref identifier]]
          [(if-not (utils/promise? (get child-reg identifier)) child-reg (dissoc child-reg identifier)) child])))))

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
              (*recurse* (:additionalProperties node) context registry)]
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
     "int-or-string" [:or :int [:re #"\d+"]]
     :string)])

(defmethod swagger->malli* "x-kubernetes-int-or-string" [node context registry]
  [registry [:or :int :string]])

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
  (let [[registry schema]
        (binding [*recurse* (memoize swagger->malli*)]
          (swagger->malli* root swagger-spec {}))]
    (if (empty? registry) schema [:schema {:registry registry} schema])))
