(ns kube-api.controllers.store)

(defn- identifier [item]
  [(get-in item [:metadata :namespace]) (get-in item [:metadata :name])])

(defonce store (atom {}))

(defn add-event [cluster item]
  )

(defn update-event [cluster item]
  )

(defn remove-event [cluster item]
  )