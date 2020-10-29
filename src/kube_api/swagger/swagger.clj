(ns kube-api.swagger.swagger
  "For turning a swagger specification into a set of operations
   described as data using malli schemas for requests and responses."
  (:require [kube-api.swagger.malli :as malli]
            [clojure.string :as strings]
            [kube-api.utils :as utils]))


(defn compile-request-schema [swagger-spec params]
  (letfn [(compile-one [param]
            [(keyword (:name param))
             (cond-> {:optional (not (:required param))}
               (not (strings/blank? (:description param)))
               (assoc :description (:description param)))
             (cond
               (contains? param :schema)
               (malli/swagger->malli swagger-spec (get param :schema))
               (contains? param :type)
               (malli/swagger->malli swagger-spec (select-keys param [:type]))
               :otherwise
               (throw (ex-info "IDK" {:param param})))])]
    (let [grouped (group-by :in params)
          query   (get grouped "query" [])
          path    (get grouped "path" [])
          body    (get grouped "body" [])]
      ; todo: should I merge all the various param maps into just one command map? what if keys collide across maps?
      (cond-> [:map]
        (not-empty query)
        (conj [:query-params {:optional (every? (complement :required) query)}
               (into [:map] (map compile-one) query)])
        (not-empty body)
        (conj [:body-params {:optional (every? (complement :required) body)}
               (into [:map] (map compile-one) body)])
        (not-empty path)
        (conj [:path-params {:optional (every? (complement :required) path)}
               (into [:map] (map compile-one) path)])))))


(defn compile-response-schema [swagger-spec responses]
  (utils/map-vals
    (fn [response-map]
      (if-some [schema (get response-map :schema)]
        (malli/swagger->malli swagger-spec schema)
        'any?))
    responses))

(defn swagger->ops
  "Turns a swagger spec into a big set of individual operations that can be
   performed with expanded malli request and response schemas. Intended to"
  [swagger-spec]
  (for [[endpoint methods] (:paths swagger-spec)
        :let [shared-params    (:parameters methods [])
              shared-responses (:responses methods {})]
        [verb method] (select-keys methods [:put :post :delete :get :options :head :patch :watch])
        :let [method-params    (:parameters method [])
              method-responses (:responses method {})]]
    {:uri               (name endpoint)
     :request-method    verb
     :operationId       (get method :operationId)
     :tags              (set (get method :tags #{}))
     :custom-attributes (into {}
                              (filter
                                (fn [[k _]]
                                  (strings/starts-with? (name k) "x-")))
                              (seq method))
     :request-schema    (->> (concat shared-params method-params)
                             (distinct)
                             (sort-by :name)
                             (compile-request-schema swagger-spec))
     :response-schemas  (->> (merge shared-responses method-responses)
                             (compile-response-schema swagger-spec))}))
