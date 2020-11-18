(ns kube-api.controllers.controller
  "Implements functionality similar to deltafifo from client-go.
   Accumulates events and compacts multiple events that apply to
   the same resource until the message is consumed. Events contain
   an accreted view of the state of all watched resources that is
   guaranteed to be up to date with the event."
  (:require [clojure.core.async :as async]
            [kube-api.controllers.utils :as utils]))

(defmulti compact-events
  (fn [prev next] (mapv :type [prev next])))

(defmethod compact-events :default [prev next]
  [prev next])

(defmethod compact-events ["ADDED" "MODIFIED"] [prev next]
  [(merge prev (select-keys next [:new :index]))])

(defmethod compact-events ["MODIFIED" "MODIFIED"] [prev next]
  [(merge next (select-keys prev [:old]))])

(defmethod compact-events ["MODIFIED" "DELETED"] [prev next]
  [(update next :old #(or % (:new prev)))])

(defmethod compact-events ["ADDED" "DELETED"] [prev next]
  [(update next :old #(or % (:new prev)))])

(defn mk-path [object]
  [(get-in object [:kind])
   (get-in object [:metadata :namespace])
   (get-in object [:metadata :name])])

(defn step-one [state pending-puts {:keys [type object]}]
  (let [path (mk-path object)]
    (case type
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
  (let [indexed
        (vec (map-indexed (fn [i x] (assoc x :index i)) events))
        {:keys [resource]}
        (nth indexed (dec (count indexed)))
        by-resource
        (group-by :resource indexed)
        compaction-candidates
        (get by-resource resource [])]
    (->> (mapcat identity (vals (dissoc by-resource resource)))
         (concat (utils/compact compact-events compaction-candidates))
         (sort-by :index)
         (mapv #(dissoc % :index)))))

(defn controller-data-feed
  "Combines a set of list-watch streams into a single outbound stream suitable for
   consumers that will contain change events with an up to date view of the accumulated
   state thus far. Messages waiting to be consumed by a consumer will be compacted as
   novelty arrives to avoid asking consumers to perform unnecessary intermediary work.

   You can optionally send received messages back using feedback-chan should you fail to
   process a message. By returning the message you're asking for the message to be
   delivered again if it remains relevant, however it might never be redelivered if
   a later piece of novelty compacts it away before you read from the feed again."
  ([resource-streams]
   (controller-data-feed resource-streams (async/chan)))
  ([resource-streams feedback-chan]
   (let [return-chan (async/chan)]
     (async/go-loop [state {} outbox []]
       (let [channel-ops
             (cond-> (into [feedback-chan] resource-streams)
               (not-empty outbox)
               (conj [return-chan (assoc (first outbox) :state state)]))
             [val winner]
             (async/alts! channel-ops :priority true)]
         (cond
           ; the message was consumed, yay!
           (identical? winner return-chan)
           (when (true? val) (recur state (subvec outbox 1)))
           ; a consumed message was sent back!
           (identical? winner feedback-chan)
           (recur state (compactor (into [val] outbox)))
           ; novelty was received and incorporated
           :otherwise
           (let [[new-state proposed-new-puts] (step-one state outbox val)]
             (recur new-state (compactor proposed-new-puts))))))
     return-chan)))