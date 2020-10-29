(ns kube-api.swagger.kubernetes
  "Kubernetes specific code for producing operation specifications."
  (:require [kube-api.swagger.swagger :as swagger]
            [kube-api.swagger.malli :as malli]
            [kube-api.utils :as utils]))


; kubernetes specific extensions because these swagger specs are insufficiently
; described to be automatically interpreted. because specs are best when they're
; only implemented 90% of the way, right?

(utils/defmethodset malli/swagger->malli*
  #{:io.k8s.apiextensions-apiserver.pkg.apis.apiextensions.v1.JSON
    :io.k8s.apiextensions-apiserver.pkg.apis.apiextensions.v1beta1.JSON}
  [_ context registry]
  [registry [:or :bool :int :double :string [:vector]]])

(utils/defmethodset malli/swagger->malli*
  #{:io.k8s.apiextensions-apiserver.pkg.apis.apiextensions.v1.JSONSchemaPropsOrBool
    :io.k8s.apiextensions-apiserver.pkg.apis.apiextensions.v1beta1.JSONSchemaPropsOrBool}
  [_ context registry]
  (let [json-schema-props (get-in context [:definitions :io.k8s.apiextensions-apiserver.pkg.apis.apiextensions.v1.JSONSchemaProps])
        [child-registry child] (malli/swagger->malli* json-schema-props context registry)]
    [child-registry [:or {:default true} :boolean child]]))

(utils/defmethodset malli/swagger->malli*
  #{:io.k8s.apiextensions-apiserver.pkg.apis.apiextensions.v1.JSONSchemaPropsOrArray
    :io.k8s.apiextensions-apiserver.pkg.apis.apiextensions.v1beta1.JSONSchemaPropsOrArray}
  [_ context registry]
  (let [json-schema-props (get-in context [:definitions :io.k8s.apiextensions-apiserver.pkg.apis.apiextensions.v1.JSONSchemaProps])
        [child-registry child] (malli/swagger->malli* json-schema-props context registry)]
    [child-registry [:or [:vector child] child]]))

(utils/defmethodset malli/swagger->malli*
  #{:io.k8s.apiextensions-apiserver.pkg.apis.apiextensions.v1.JSONSchemaPropsOrStringArray
    :io.k8s.apiextensions-apiserver.pkg.apis.apiextensions.v1beta1.JSONSchemaPropsOrStringArray}
  [_ context registry]
  (let [json-schema-props (get-in context [:definitions :io.k8s.apiextensions-apiserver.pkg.apis.apiextensions.v1.JSONSchemaProps])
        [child-registry child] (malli/swagger->malli* json-schema-props context registry)]
    [child-registry [:or [:vector :string] child]]))

(defn kubernetes-group-version-kind [op]
  (get-in op [:custom :x-kubernetes-group-version-kind]))

(defn kubernetes-action [op]
  (get-in op [:custom :x-kubernetes-action]))

(defn well-formed? [op]
  (and (not-empty (kubernetes-group-version-kind op))
       (not-empty (kubernetes-action op))))

(defn kube-swagger->operation-views
  "Converts the swagger-spec into a set of operations and creates a few lookup
   tables by those attributes that people interacting with kubernetes most
   commonly use to specify an operation."
  [swagger-spec]
  (let [operations (filterv well-formed? (swagger/swagger->ops swagger-spec))]
    {:operations         operations
     :by-operation-id    (utils/index-by :operation operations)
     :by-resource-action (utils/index-by (juxt kubernetes-group-version-kind kubernetes-action) operations)
     :by-tags            (utils/groupcat-by :tags operations)}))