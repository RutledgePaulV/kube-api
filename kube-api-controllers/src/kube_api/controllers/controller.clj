(ns kube-api.controllers.controller
  (:require [clojure.core.async :as async]
            [kube-api.controllers.utils :as utils]))

(defn mk-path [object]
  [(get-in object [:kind]) (get-in object [:metadata :namespace]) (get-in object [:metadata :name])])

(defn step-one [state pending-puts [event object]]
  (let [path (mk-path object)]
    (case event
      ("ADDED" "MODIFIED")
      (let [new-state (assoc-in state path object)]
        (if-some [old-value (get-in state path)]
          (if (= old-value object)
            [new-state pending-puts]
            [new-state (conj pending-puts {:resource path :type "MODIFIED" :old old-value :new object})])
          [new-state (conj pending-puts {:resource path :type "ADDED" :old nil :new object})]))
      "DELETED"
      (if-some [old-value (get-in state path)]
        (let [new-state (utils/dissoc-in state path)]
          [new-state (conj pending-puts {:resource path :type "DELETED" :old old-value :new nil})])
        [state pending-puts]))))


(defn compactor [events]
  (let [novelty     (last events)
        by-resource (group-by :resource events)
        incumbents  (butlast (get by-resource (:resource novelty) []))]
    ; TODO
    ))


(defn controller-feed
  "Combines a set of list-watch streams into a single outbound stream suitable for
   consumers that will contain change events with an up to date view of the accumulated
   state thus far. Messages waiting to be consumed by a consumer will be compressed /
   deduplicated / and marked obsolete as necessary as additional changes come in so
   as to avoid asking consumers to do unnecessary work once the consumer is ready
   for the next task."
  [resource-streams]
  (let [return-chan (async/chan)]
    (async/go-loop [state {} outbox []]
      (let [channel-ops
            (cond-> resource-streams
              (not-empty outbox)
              (conj [return-chan (assoc (first outbox) :state state)]))
            [val winner]
            (async/alts! channel-ops :priority true)]
        (if (identical? winner return-chan)
          (when (true? val) (recur state (rest outbox)))
          (let [[new-state proposed-new-puts] (step-one state outbox val)]
            (recur new-state (compactor proposed-new-puts))))))
    return-chan))