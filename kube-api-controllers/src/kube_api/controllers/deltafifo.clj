(ns kube-api.controllers.deltafifo
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

(defmethod compact-events ["SYNC" "SYNC"] [prev next]
  [next])

(defmethod compact-events ["ADDED" "SYNC"] [prev next]
  [next])

(defmethod compact-events ["MODIFIED" "SYNC"] [prev next]
  [next])

(defmethod compact-events ["DELETED" "SYNC"] [prev next]
  [next])

(defmethod compact-events ["ADDED" "MODIFIED"] [prev next]
  [(merge prev (select-keys next [:new :index]))])

(defmethod compact-events ["MODIFIED" "MODIFIED"] [prev next]
  [(merge next (select-keys prev [:old]))])

(defmethod compact-events ["MODIFIED" "DELETED"] [prev next]
  [(update next :old #(or % (:new prev)))])

(defmethod compact-events ["ADDED" "DELETED"] [prev next]
  [(update next :old #(or % (:new prev)))])

(defn state->objects [kind->namespace->name->object]
  (for [[kind namespace->name->object]
        kind->namespace->name->object
        [namespace name->object]
        namespace->name->object
        [name object]
        name->object]
    [[kind namespace name] object]))

(defn step-one [state pending-puts {:keys [kind type object]}]
  (case type
    ("ADDED" "MODIFIED")
    (let [path      (utils/mk-path object)
          new-state (assoc-in state path object)]
      (if-some [old-value (get-in state path)]
        (if (= old-value object)
          [new-state pending-puts]
          [new-state
           (cond-> pending-puts
             (not= old-value object)
             (conj {:resource path :type "MODIFIED" :old old-value :new object}))])
        [new-state
         (cond-> pending-puts
           (some? object)
           (conj {:resource path :type "ADDED" :old nil :new object}))]))
    "SYNC"
    (let [indexed
          (utils/index-by utils/mk-path object)
          [state' events']
          (letfn [(reduction [[state' events'] [path old-object]]
                    (if (or (contains? indexed path) (not= kind (first path)))
                      [state' events']
                      [(utils/dissoc-in state' path) (conj events' {:resource path :type "DELETED" :old old-object :new nil})]))]
            (reduce reduction [state pending-puts] (state->objects state)))]
      (letfn [(reduction [[state'' events''] k v]
                (let [new-state (assoc-in state'' k v)]
                  (if-some [old-object (get state'' k)]
                    [new-state
                     (cond-> events''
                       (not= old-object v)
                       (conj {:resource k :type "MODIFIED" :old old-object :new v}))]
                    [new-state
                     (cond-> events''
                       (some? v)
                       (conj {:resource k :type "ADDED" :old nil :new v}))])))]
        (reduce-kv reduction [state' events'] indexed)))
    "DELETED"
    (let [path (utils/mk-path object)]
      (if-some [old-value (get-in state path)]
        [(utils/dissoc-in state path) (conj pending-puts {:resource path :type "DELETED" :old old-value :new nil})]
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

(defn deltafifo
  "Combines a set of list-watch streams into a single outbound stream suitable for
   consumers that will contain change events with an up to date view of the accumulated
   state thus far. Messages waiting to be consumed by a consumer will be compacted as
   novelty arrives to avoid asking consumers to perform unnecessary intermediary work.

   You can optionally send received messages back using feedback-chan should you fail to
   process a message. By returning the message you're asking for the message to be
   delivered again if it remains relevant, however it might never be redelivered if
   a later piece of novelty compacts it away before you read from the feed again."
  ([resource-streams]
   (deltafifo resource-streams (async/chan)))
  ([resource-streams feedback-chan]
   (let [return-chan (async/chan)]
     (async/go-loop [streams (set resource-streams) state {} outbox []]
       (let [channel-ops
             (cond-> (into [feedback-chan] streams)
               (not-empty outbox)
               (conj [return-chan (assoc (first outbox) :state state)]))
             [val winner]
             (async/alts! channel-ops :priority true)]
         (cond
           (and (empty? streams) (empty? outbox))
           nil
           ; the message was consumed, yay!
           (identical? winner return-chan)
           (when (true? val) (recur streams state (subvec outbox 1)))
           ; a consumed message was sent back!
           (identical? winner feedback-chan)
           (when (some? val) (recur streams state (compactor (into [val] outbox))))
           ; novelty was received and incorporated
           (some? val)
           (let [[new-state proposed-new-puts] (step-one state outbox val)]
             (recur streams new-state (compactor proposed-new-puts)))

           (nil? val)
           (recur (disj streams winner) state outbox))))
     return-chan)))