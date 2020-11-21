(ns kube-api.controllers.controller-test
  (:require [kube-api.controllers.controller :as kcc]
            [kube-api.core :as kube]
            [clojure.test :refer :all]
            [lambdaisland.deep-diff2 :as diff]))

(defn dispatch [{:keys [type old new]}]
  [type (or (get-in new [:kind]) (get-in old [:kind]))])

(defmulti on-event #'dispatch)

(defmethod on-event :default [event]
  (locking *out* (println "Ignoring:" (dispatch event))))

(defmethod on-event ["ADDED" "Pod"] [{pod :new}]
  (locking *out* (println "Saw new pod:" (get-in pod [:metadata :name]))))

(defmethod on-event ["MODIFIED" "Pod"] [{old-pod :old new-pod :new}]
  (locking *out* (diff/pretty-print (diff/diff old-pod new-pod))))

(defmethod on-event ["DELETED" "Deployment"] [{old-deployment :old}]
  (locking *out* (println "Saw deployment was deleted" (get-in old-deployment [:metadata :name]))))

(def pod-op-selector {:kind "Pod" :action "list"})
(def pod-request {:path-params {:namespace ""}}) ; "" is how you say 'all namespaces'
(def pod-stream [pod-op-selector pod-request])

(def deployment-op-selector {:kind "Deployment" :action "list"})
(def deployment-request {:path-params {:namespace ""}})  ; "" is how you say 'all namespaces'
(def deployment-stream [deployment-op-selector deployment-request])

(def targets [pod-stream deployment-stream])

(comment

  (defonce client (kube/create-client "do-nyc1-k8s-1-19-3-do-2-nyc1-1604718220356"))

  (def controller
    (kcc/start-controller client targets on-event))

  ; when you're done, stop the controller by calling it
  (controller))

