(ns kube-api.swagger
  (:require [clojure.string :as strings]
            [kube-api.spec :as spec]
            [kube-api.utils :as utils]))


(defn compile-request-schema [specification params]
  (letfn [(compile-one [param]
            [(keyword (:name param))
             (cond-> {:optional (not (:required param))}
               (not (strings/blank? (:description param)))
               (assoc :description (:description param)))
             (cond
               (contains? param :schema)
               (spec/swagger->malli specification (get param :schema))
               (contains? param :type)
               (spec/swagger->malli specification (select-keys param [:type]))
               :otherwise
               (throw (ex-info "IDK" {:param param})))])]
    (let [grouped (group-by :in params)
          query   (get grouped "query" [])
          path    (get grouped "path" [])
          body    (get grouped "body" [])]
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


(defn compile-response-schema [specification responses]
  responses)


(defn swagger->ops [swagger-spec]
  (->> (for [[endpoint methods] (:paths swagger-spec)
             :let [shared-params    (:parameters methods [])
                   shared-responses (:responses methods {})]
             [verb method] (select-keys methods [:put :post :delete :get :options :head :patch :watch])
             :let [method-params    (:parameters method [])
                   method-responses (:responses method {})]
             :when (contains? method :operationId)]
         {:endpoint  (name endpoint)
          :verb      verb
          :action    (get method :x-kubernetes-action)
          :operation (get method :operationId)
          :tags      (get method :tags [])
          :kind      (get method :x-kubernetes-group-version-kind)
          :request   (->> (concat shared-params method-params)
                          (distinct)
                          (sort-by :name)
                          (compile-request-schema swagger-spec))
          :response  (->> (merge shared-responses method-responses)
                          (compile-response-schema swagger-spec))})
       (utils/index-by :operation)))
