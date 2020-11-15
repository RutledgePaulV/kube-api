(ns kube-api.controllers.listwatch
  (:require [kube-api.core :as kube])
  (:import [okhttp3 Response]))


; todo, exponential backoff of reconnects
; todo, implement mechanism to stop the watch

(defn list-watch
  "Creates a reliable watch that monitors all updates for matching resources and reconnects
   as necessary. Controller authors should prefer this over the one-shot watch implemented
   in kube-api.core/watch."
  [client op request on-added on-modified on-deleted]
  (letfn [(list-resources []
            (let [list-op       (-> op (assoc-in [:action] "list"))
                  list-request  (-> request
                                    (assoc-in [:query-params :watch] false)
                                    (assoc-in [:query-params :resourceVersion] "0"))
                  list-response (kube/invoke client list-op list-request)]
              (doseq [item (:items list-response)] (on-added item))
              list-response))
          (watch-resources [resource-version]
            (let [watch-op
                  (-> op (assoc-in [:action] "list"))
                  watch-request
                  (-> request
                      (assoc-in [:query-params :watch] true)
                      (assoc-in [:query-params :resourceVersion] resource-version)
                      (assoc-in [:query-params :allowWatchBookmarks] true))
                  last-observed-version
                  (volatile! resource-version)]
              (kube/watch client watch-op watch-request
                          {:on-event
                           (fn [message]
                             (let [object               (get-in message [:object])
                                   new-resource-version (get-in object [:metadata :resourceVersion])]
                               (vreset! last-observed-version new-resource-version)
                               (case (:type message)
                                 "ADDED" (on-added object)
                                 "MODIFIED" (on-modified object)
                                 "DELETED" (on-deleted object)
                                 "BOOKMARK" nil
                                 "ERROR" nil                ; todo
                                 )))
                           :on-error
                           (fn [exception ^Response response]
                             (when (= 410 (.code response))
                               (reinitialize)))
                           :on-closed
                           (fn [code reason]
                             (watch-resources (deref last-observed-version)))})))
          (reinitialize []
            (let [list-response    (list-resources)
                  resource-version (get-in list-response [:metadata :resourceVersion])]
              (watch-resources resource-version)))]
    (reinitialize)))

