(ns kube-api.controllers.listwatcher-test
  (:require [kube-api.controllers.listwatcher :refer :all]
            [kube-api.core :as kube]
            [lambdaisland.deep-diff2 :as diff]
            [clojure.core.async :as async]
            [clojure.test :refer :all]))


(defn observe-chan [chan]
  (async/go-loop [previous {}]
    (when-some [[kind object] (async/<! chan)]
      (clojure.pprint/pprint
        {:event     kind
         :namespace (get-in object [:metadata :namespace])
         :name      (get-in object [:metadata :name])})
      (diff/pretty-print (diff/diff previous object))
      (recur object))))


(defn observe [client op-selector request]
  (doto (list-watch-stream client op-selector request)
    (observe-chan)))

(defn observer []
  (let [client      (kube/create-client "microk8s")
        op-selector {:kind "Deployment" :action "list"}
        request     {:path-params {:namespace "kube-system"}}]
    (observe client op-selector request)))

(deftest list-watch-stream-test
  )


