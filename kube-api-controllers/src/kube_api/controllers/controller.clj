(ns kube-api.controllers.controller
  (:require [clojure.core.async :as async]
            [kube-api.controllers.deltafifo :as df]
            [kube-api.controllers.listwatcher :as lw]
            [kube-api.controllers.utils :as utils]
            [clojure.tools.logging :as log]
            [kube-api.core :as kube]))

(defn process-event-streams [streams on-event]
  (let [feedback (async/chan)
        unified  (df/deltafifo streams feedback)
        workers  (atom {})]
    (async/go-loop []
      (if-some [event (async/<! unified)]
        (let [object-id
              (:resource event)
              [old-state new-state]
              (swap-vals! workers update object-id #(or % (async/chan)))
              worker-chan
              (get new-state object-id)]
          (when-not (contains? old-state object-id)
            (async/go-loop [backoff (utils/backoff-seq 300000)]
              (when-some [event (async/<! worker-chan)]
                (let [pending (async/thread
                                (try (on-event event)
                                     :success
                                     (catch Exception e
                                       (let [sleep (first backoff)]
                                         (def EXCEPTION e)
                                         (async/>!! feedback event)
                                         (async/<!! (async/timeout sleep))
                                         :timeout))))
                      value   (async/<! pending)]
                  (case value
                    :success
                    (recur (utils/backoff-seq 300000))
                    :timeout
                    (recur (rest backoff)))))))
          (if (async/offer! worker-chan event)
            (recur)
            (do (async/>! feedback event) (recur))))
        (run! async/close! (vals @workers))))))

(defn start-controller [client op-selectors+requests on-event]
  (let [novelty-sources (mapv #(lw/list-watch-stream client (first %) (second %)) op-selectors+requests)]
    (process-event-streams novelty-sources on-event)
    (fn [] (run! async/close! novelty-sources))))



(comment

  (def client (kube/create-client "do-nyc1-k8s-1-19-3-do-2-nyc1-1604718220356"))

  (do
    (defn dispatch [{:keys [type resource]}]
      [(first resource) type])

    (defmulti on-event #'dispatch)

    (defmethod on-event :default [event]
      (locking *out*
        (clojure.pprint/pprint (utils/concise-resource event)))))

  (def controller
    (start-controller
      client
      [[{:kind "Deployment" :action "list"} {:path-params {:namespace "kube-system"}}]
       [{:kind "Pod" :action "list"} {:path-params {:namespace "kube-system"}}]]
      on-event)))