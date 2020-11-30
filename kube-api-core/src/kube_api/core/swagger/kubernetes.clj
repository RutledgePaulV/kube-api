(ns kube-api.core.swagger.kubernetes
  "Kubernetes specific code for producing operation specifications."
  (:require [kube-api.core.swagger.swagger :as swagger]
            [kube-api.core.swagger.malli :as malli]
            [kube-api.core.utils :as utils]
            [clojure.set :as sets]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as strings]))


; kubernetes specific extensions because these swagger specs are insufficiently
; described to be automatically interpreted. because specs are best when they're
; only implemented 90% of the way, right?

(utils/defmethodset malli/swagger->malli*
  #{:io.k8s.apiextensions-apiserver.pkg.apis.apiextensions.v1.JSON
    :io.k8s.apiextensions-apiserver.pkg.apis.apiextensions.v1beta1.JSON}
  [_ context registry]
  (let [definition [:or :bool :int :double :string [:vector [:ref ::json]] [:map-of :string [:ref ::json]]]]
    [(merge registry {:io.k8s.apiextensions-apiserver.pkg.apis.apiextensions.v1.JSON definition})
     [:ref :io.k8s.apiextensions-apiserver.pkg.apis.apiextensions.v1.JSON]]))

(utils/defmethodset malli/swagger->malli*
  #{:io.k8s.apiextensions-apiserver.pkg.apis.apiextensions.v1.JSONSchemaPropsOrBool
    :io.k8s.apiextensions-apiserver.pkg.apis.apiextensions.v1beta1.JSONSchemaPropsOrBool}
  [_ context registry]
  (let [json-schema-props (get-in context [:definitions :io.k8s.apiextensions-apiserver.pkg.apis.apiextensions.v1.JSONSchemaProps])
        [child-registry child] (malli/*recurse* json-schema-props context registry)]
    [child-registry [:or {:default true} child :boolean]]))

(utils/defmethodset malli/swagger->malli*
  #{:io.k8s.apiextensions-apiserver.pkg.apis.apiextensions.v1.JSONSchemaPropsOrArray
    :io.k8s.apiextensions-apiserver.pkg.apis.apiextensions.v1beta1.JSONSchemaPropsOrArray}
  [_ context registry]
  (let [json-schema-props (get-in context [:definitions :io.k8s.apiextensions-apiserver.pkg.apis.apiextensions.v1.JSONSchemaProps])
        [child-registry child] (malli/*recurse* json-schema-props context registry)]
    [child-registry [:or child [:vector child]]]))

(utils/defmethodset malli/swagger->malli*
  #{:io.k8s.apiextensions-apiserver.pkg.apis.apiextensions.v1.JSONSchemaPropsOrStringArray
    :io.k8s.apiextensions-apiserver.pkg.apis.apiextensions.v1beta1.JSONSchemaPropsOrStringArray}
  [_ context registry]
  (let [json-schema-props (get-in context [:definitions :io.k8s.apiextensions-apiserver.pkg.apis.apiextensions.v1.JSONSchemaProps])
        [child-registry child] (malli/*recurse* json-schema-props context registry)]
    [child-registry [:or child [:vector :string]]]))

(defn kubernetes-group-version-kind [op]
  (get-in op [:custom-attributes :x-kubernetes-group-version-kind]))

(defn normalize-action [action]
  (or ({"post" "create" "watchlist" "watch" "put" "update"} action) action))

(defn kubernetes-action [op]
  (get-in op [:custom-attributes :x-kubernetes-action]))

(defn kubernetes-version [op]
  (:version (kubernetes-group-version-kind op)))

(defn kubernetes-group [op]
  (:group (kubernetes-group-version-kind op)))

(defn kubernetes-kind [op]
  (:kind (kubernetes-group-version-kind op)))

(defn kubernetes-operation [op]
  (:operationId op))

(defn well-formed? [op]
  (and (not-empty (kubernetes-group-version-kind op))
       (not-empty (kubernetes-action op))))

(defn normalize [op]
  (update-in op [:custom-attributes :x-kubernetes-action] normalize-action))

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

(def modifications
  (delay (edn/read-string (slurp (io/resource "kube_api/swagger-overlay.edn")))))


(defn op-selector-schema [{:keys [by-operation by-group by-version by-kind by-action] :as views}]
  [:map {:closed true}
   [:operation {:optional true}
    (into [:enum] (sort (keys by-operation)))]
   [:group {:optional true}
    (into [:enum] (sort (keys by-group)))]
   [:version {:optional true}
    (into [:enum] (sort (keys by-version)))]
   [:kind {:optional true}
    (into [:enum] (sort (keys by-kind)))]
   [:action {:optional true}
    (into [:enum] (sort (keys by-action)))]])

(defn index [operations]
  {:by-operation (group-by kubernetes-operation operations)
   :by-group     (group-by kubernetes-group operations)
   :by-version   (group-by kubernetes-version operations)
   :by-kind      (group-by kubernetes-kind operations)
   :by-action    (group-by kubernetes-action operations)})

(defn kube-swagger->operation-views
  "Converts the swagger-spec into a set of operations and creates a few lookup
   tables by those attributes that people interacting with kubernetes most
   commonly use to specify an operation."
  [swagger-spec]
  (let [modified-spec (utils/merge+ swagger-spec (force modifications))
        operations    (->> (swagger/swagger->ops modified-spec)
                           (filter well-formed?)
                           (map normalize))
        selectors     (map
                        (fn [op]
                          (array-map
                            :kind (kubernetes-kind op)
                            :operation (kubernetes-operation op)
                            :action (kubernetes-action op)
                            :version (kubernetes-version op)
                            :group (kubernetes-group op)))
                        operations)
        views         (assoc (index operations) :selectors selectors)
        schema        (op-selector-schema views)]
    (assoc views :op-selector-schema schema)))


(defn select [indexes selector]
  (reduce
    (fn [result [k v]]
      (let [selector-name (keyword (strings/replace-first (name k) "by-" ""))]
        (if-some [selector-value (get selector selector-name)]
          (if (nil? result)
            (set (get v (name selector-value) #{}))
            (sets/intersection result (set (get v (name selector-value) #{}))))
          result)))
    nil
    indexes))

(defn get-op [views selector]
  (let [; perform one select to narrow the available options
        remainder  (select views selector)
        ; index the narrowed results
        views'     (index remainder)
        ; refine the selector by picking the best group/version of the remainder
        ; if group and version were unset
        selector'  (-> selector
                       (update :group #(or % (most-appropriate-group (keys (:by-group views')))))
                       (update :version #(or % (most-appropriate-default-version (keys (:by-version views'))))))
        ; perform another selection
        remainder' (select views' selector')]
    (cond
      (empty? remainder')
      (throw (ex-info "op-selector didn't match an available operation" {:selector selector}))
      (< 1 (count remainder'))
      (throw (ex-info "op-selector not specific enough to identify operation" {:selector selector}))
      :otherwise
      (first remainder'))))
