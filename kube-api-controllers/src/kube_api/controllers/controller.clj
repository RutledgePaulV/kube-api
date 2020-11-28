(ns kube-api.controllers.controller
  (:require [clojure.core.async :as async]
            [kube-api.controllers.deltafifo :as df]
            [kube-api.controllers.listwatcher :as lw]
            [kube-api.controllers.utils :as utils]
            [clojure.tools.logging :as log]))

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
            (async/go-loop [[sleep :as backoff] (utils/backoff-seq 300000)]
              (when-some [event (async/<! worker-chan)]
                (let [pending (async/thread
                                (try (on-event event)
                                     :success
                                     (catch Exception e
                                       (log/errorf e "Exception processing controller event. Backing off this resource.")
                                       :timeout)))
                      value   (async/<! pending)]
                  (case value
                    :success
                    (recur (utils/backoff-seq 300000))
                    :timeout
                    (do (async/>!! feedback event)
                        (async/<!! (async/timeout sleep))
                        (recur (rest backoff))))))))
          (if (async/offer! worker-chan event)
            (recur)
            (do (async/>! feedback event)
                (async/<! (async/timeout 50))
                (recur))))
        (run! async/close! (vals @workers))))))

(defn start-controller [client op-selectors+requests on-event]
  (let [novelty-sources (mapv #(lw/list-watch-stream client (first %) (second %)) op-selectors+requests)]
    (process-event-streams novelty-sources on-event)
    (fn [] (run! async/close! novelty-sources))))
