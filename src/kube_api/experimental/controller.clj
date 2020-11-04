(ns kube-api.experimental.controller
  "Implements the informer pattern from the client-go kubernetes client. Useful
   for building robust controller / observer implementations in Clojure."
  (:require [kube-api.core :as kube])
  (:import [java.util.concurrent LinkedBlockingQueue Delayed TimeUnit DelayQueue]
           [clojure.lang IFn]))


(defonce local-cache
  (atom {}))

(defonce delta-queue
  (LinkedBlockingQueue.))

(defonce task-queue
  (DelayQueue.))

(defn reflector [client fq-op-selector namespace]
  (let [list-op (assoc fq-op-selector :action "list")]))

(defn spawn-informer
  ([client op-selector on-added on-removed on-changed]
   ))

(defn spawn-worker [f]
  )


