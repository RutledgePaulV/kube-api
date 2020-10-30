(ns kube-api.swagger.kubernetes
  "Kubernetes specific code for producing operation specifications."
  (:require [kube-api.swagger.swagger :as swagger]
            [kube-api.swagger.malli :as malli]
            [kube-api.utils :as utils]
            [clojure.set :as sets]))


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
  (get-in op [:custom-attributes :x-kubernetes-group-version-kind]))

(defn kubernetes-action [op]
  (get-in op [:custom-attributes :x-kubernetes-action]))

(defn kubernetes-version [op]
  (:version (kubernetes-group-version-kind op)))

(defn kubernetes-group [op]
  (:group (kubernetes-group-version-kind op)))

(defn kubernetes-kind [op]
  (:kind (kubernetes-group-version-kind op)))

(defn well-formed? [op]
  (and (not-empty (kubernetes-group-version-kind op))
       (not-empty (kubernetes-action op))))

(defn most-appropriate-default-version [options]
  (letfn [(version->preference-rank [version]
            (let [re #"^v(?<major>\d+)(?<minor>[A-Za-z]+)?(?<patch>\d+)?$"]
              (-> (utils/match-groups re version)
                  (update :minor #(or % "stable"))
                  (update :patch #(or % "0"))
                  (update :major #(Long/parseLong %))
                  (update :patch #(Long/parseLong %)))))]
    (let [grouped
          (->> (map (juxt version->preference-rank identity) options)
               (sort-by (comp (juxt :major :patch) first) #(compare %2 %1))
               (group-by (comp :minor first)))]
      (second
        (or (first (get grouped "stable"))
            (first (get grouped "beta"))
            (first (get grouped "alpha")))))))

(defn most-appropriate-group [options]
  (if (contains? (set options) "") "" (first (sort options))))

(defn kube-swagger->operation-views
  "Converts the swagger-spec into a set of operations and creates a few lookup
   tables by those attributes that people interacting with kubernetes most
   commonly use to specify an operation."
  [swagger-spec]
  (let [operations (filter well-formed? (swagger/swagger->ops swagger-spec))]
    {:operations (utils/index-by
                   (fn [op]
                     (array-map
                       :kind (kubernetes-kind op)
                       :action (kubernetes-action op)
                       :version (kubernetes-version op)
                       :group (kubernetes-group op)))
                   operations)
     :by-group   (group-by kubernetes-group operations)
     :by-version (group-by kubernetes-version operations)
     :by-kind    (group-by kubernetes-kind operations)
     :by-action  (group-by kubernetes-action operations)}))


(defn get-op [{:keys [by-group by-version by-kind by-action] :as views}
              {:keys [group kind version action] :as op-selector}]
  (let [sets         (cond-> #{}
                       (some? group)
                       (conj (set (get by-group (name group) #{})))
                       (some? kind)
                       (conj (set (get by-kind (name kind) #{})))
                       (some? version)
                       (conj (set (get by-version (name version) #{})))
                       (some? action)
                       (conj (set (get by-action (name action) #{}))))
        remainder    (apply sets/intersection sets)
        r-by-group   (group-by kubernetes-group remainder)
        r-by-version (group-by kubernetes-version remainder)
        r-by-action  (group-by kubernetes-action remainder)
        best-group   (or (some-> group name) (most-appropriate-group (keys r-by-group)))
        best-version (or (some-> version name) (most-appropriate-default-version (keys r-by-version)))
        best-action  (or (some-> action name) "list")
        remainder'   (sets/intersection
                       (set (get r-by-group best-group #{}))
                       (set (get r-by-version best-version #{}))
                       (set (get r-by-action best-action #{})))]
    (first remainder')))


(comment
  (defn invoke [client operation]
    )

  (def client {})

  (invoke client
          {:kind :Deployment}
          )

  (invoke client
          {:apiVersion ""
           :resource   :Deployment
           })

  )