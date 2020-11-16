(ns kube-api.controllers.buffers
  (:require [clojure.core.async.impl.protocols :as protos]
            [kube-api.controllers.utils :as utils])
  (:import [java.util LinkedList]
           [clojure.lang Counted]))


; https://lairdnelson.wordpress.com/2018/01/07/understanding-kubernetes-tools-cache-package-part-4/

(defn preferred-event [[_ a-object :as a] [_ b-object :as b]]
  (let [winner (if (and a-object b-object)
                 (first (utils/version-descending [a-object b-object]))
                 (or a-object b-object))]
    (if (identical? winner a-object) a b)))

(deftype DeltaBuffer [^LinkedList buf]
  protos/Buffer
  (full? [this]
    false)
  (remove! [this]
    (.removeLast buf))
  (add!* [this item]
    (let [previous (.getFirst item)]
      (cond
        ; prefer deleted event with most information
        (and (utils/deleted? previous) (utils/deleted? item))
        (let [preferred (preferred-event previous item)]
          (when (identical? preferred item)
            (do (.clear buf) (.addFirst buf item))))
        ; ignore additional sync events, the resource was deleted.
        (and (utils/deleted? previous) (utils/sync? item))
        nil
        ;
        (utils/deleted? item)
        (do (.clear buf) (.addFirst buf item))
        (.contains buf item)
        nil
        :otherwise
        (.addFirst buf item)))
    this)
  (close-buf! [this]
    (.clear buf))
  Counted
  (count [this]
    (.size buf)))


(defn delta-buffer []
  (->DeltaBuffer (LinkedList.)))