(ns kube-api.experimental.controller
  "https://www.youtube.com/watch?v=PLSDvFjR9HY"
  (:require [kube-api.core :as kube])
  (:import [java.util.concurrent LinkedBlockingQueue]))


(defonce local-cache
  (atom {}))

(defonce delta-queue
  (LinkedBlockingQueue.))

(defonce work-queue
  (LinkedBlockingQueue.))

(defn reflector [client fq-op-selector namespace]
  (let [list-op (assoc fq-op-selector :action "list")]))

(defn spawn-informer
  ([client op-selector on-added on-removed on-changed]
   ))

(defn spawn-worker [f]
  )


