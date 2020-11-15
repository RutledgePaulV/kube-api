(ns kube-api.controllers.deltaqueue
  (:import [java.util.concurrent LinkedBlockingQueue]))

(defprotocol DeltaFifo
  (push [this item])
  (pop [this]))

(defn create-delta-fifo []
  (let [queue (LinkedBlockingQueue.)]))

