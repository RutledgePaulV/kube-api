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
  (let [novelty    (last events)
        resource   (:resource novelty)
        incumbents (pop (filterv #(= resource (:resource %)) events))]
    (case (:type novelty)
      ; if the latest event was a deletion, get rid of any intermediate events about the same resource
      "DELETED"
      (conj (vec (remove #(= resource (:resource %)) events)) novelty)
      ; if the latest event was a modification, combine the last string of modifications into just a single event
      "MODIFIED"
      ; if there was a previous event for this resource
      (if-some [original (first incumbents)]
        (cond
          ; if it was an 'added' event then collapse all "MODIFIED" into just a single "ADDED"
          (= "ADDED" (:type original))
          ; note that the order shifts to act as though the resource was ADDED later than it first was
          (conj (vec (remove #(= resource (:resource %)) events)) (assoc original :new (:new novelty)))
          ; if it was a 'modified' event, then collapse all "MODIFIED" into just a single "MODIFIED"
          (= "MODIFIED" (:type original))
          ; note that the order shifts to act as though hte resource was MODIFIED later than it first was.
          (let [repeat-mods (take-while #(= "MODIFIED" (:type %)) (rseq incumbents))]
            (conj (pop events) (assoc novelty :old (:old (last repeat-mods)))))
          ; if it was a 'deleted' event, then there should not have been a new modified event so just ignore it
          (= "DELETED" (:type original))
          (pop events))
        ; if there were no pre-existing events we can't do any better than accept the newest event as is.
        events)
      ; otherwise don't change anything!
      events)))


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
           (when (true? val) (recur state (rest outbox)))
           ; a consumed message was sent back!
           (identical? winner feedback-chan)
           (recur state (compactor (into [val] outbox)))
           ; novelty was received and incorporated
           (let [[new-state proposed-new-puts] (step-one state outbox val)]
             (recur new-state (compactor proposed-new-puts))))))
     return-chan)))