(ns kube-api.spec
  (:require [clojure.string :as strings]))

(def Base64Pattern #"^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$")
(def DateTimePattern #"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z")

(defn pointer->path [pointer]
  (->> (strings/split pointer #"/")
       (remove #{"#"})
       (map keyword)
       (into [])))

(defn dispatch [node context]
  (cond
    (contains? node :$ref)
    "$ref"

    (contains? node :type)
    (get node :type)))

(defmulti json-schema->malli #'dispatch)

(defmethod json-schema->malli "$ref" [node context]
  (let [pointer    (get node :$ref)
        path       (pointer->path pointer)
        definition (get-in context path)
        expanded   (json-schema->malli definition context)]
    expanded))

(defmethod json-schema->malli "array" [node context]
  [:vector (json-schema->malli (:items node) context)])

(defmethod json-schema->malli "object" [node context]
  (let [props       (seq (:properties node {}))
        closed      (not (contains? node :additionalProperties))
        description (get node :description)]
    (let [required (set (map keyword (get-in node [:required])))]
      (cond
        (and (empty? props) closed)
        [:map-of (cond-> {} description (assoc :description description))
         :string
         'any?]
        (and (empty? props) (not closed))
        [:map-of (cond-> {} description (assoc :description description))
         :string
         (json-schema->malli (:additionalProperties node) context)]
        (not-empty props)
        (into
          [:map (cond-> {:closed closed}
                  description (assoc :description description))]
          (map (fn [[k v]]
                 (let [child        (json-schema->malli v context)
                       description' (get v :description)]
                   [k (cond-> {:optional (not (contains? required k))}
                        description' (assoc :description description')) child])))
          props)))))

(defmethod json-schema->malli "string" [node context]
  (case (:format node)
    "byte" [:re Base64Pattern]
    "date-time" [:re DateTimePattern]
    "int-or-string" [:or :int [:re #"\d+"]]
    :string))

(defmethod json-schema->malli "integer" [node context]
  :int)

(defmethod json-schema->malli "boolean" [node context]
  :boolean)

(defn get-definition [swagger-spec definition]
  (get-in swagger-spec [:definitions definition]))

(defn ->malli [swagger-spec definition]
  (-> (get-definition swagger-spec definition)
      (json-schema->malli swagger-spec)))



(defn compile-params [specification params]
  )

(defn normalized [swagger-spec]
  (for [[endpoint methods] (:paths swagger-spec)
        :let [shared-params (group-by :in (:parameters methods {}))]
        [verb method] (select-keys methods [:put :post :delete :get :options :head :patch :watch])
        :let [method-params (group-by :in (:parameters methods {}))]
        :when (contains? method :operationId)]
    (letfn [(params [kind]
              (->> (distinct
                     (into (get shared-params kind [])
                           (get method-params kind [])))
                   (map #(dissoc % :description))))]
      {:endpoint     (name endpoint)
       :verb         verb
       :operation    (get method :operationId)
       :tags         (get method :tags [])
       :kind         (get method :x-kubernetes-group-version-kind)
       :path-params  (params "path")
       :body-params  (params "body")
       :query-params (params "query")})))


(comment

  (defn exercise [specification]
    (doseq [entrypoint (sort (keys (:definitions specification)))]
      (try
        (->malli specification entrypoint)
        (catch Exception e
          (println entrypoint)))))

  )
