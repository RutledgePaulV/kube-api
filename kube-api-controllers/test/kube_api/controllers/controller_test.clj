(ns kube-api.controllers.controller-test
  (:require [kube-api.controllers.controller :refer [start-controller]]
            [kube-api.core :as kube]
            [clojure.test :refer :all]
            [lambdaisland.deep-diff2 :as diff]
            [clojure.walk :as walk]))


(defonce client (kube/create-client "do-nyc1-k8s-1-19-3-do-2-nyc1-1604718220356"))

(defn slim [s]
  (walk/postwalk
    (fn [form]
      (if (and (map? form) (contains? form :metadata))
        {:metadata (select-keys (get form :metadata) [:name :namespace :labels])
         :spec     (select-keys (get form :spec) [:replicas])
         :kind     (get form :kind)}
        form))
    s))

(defn dispatch [{:keys [type resource]}]
  [(first resource) type])

(defmulti on-event #'dispatch)

(defmethod on-event :default [{:keys [old new] :or {old {} new {}}}]
  (locking *out* (diff/pretty-print (diff/diff (slim old) (slim new)))))

(defn start []
  (start-controller client [[{:kind "Pod" :action "list"}
                             {:path-params {:namespace "kube-system"}}]] on-event))

(defn stop [controller]
  (controller))


