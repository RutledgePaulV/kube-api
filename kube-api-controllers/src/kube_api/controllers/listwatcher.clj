(ns kube-api.controllers.listwatcher
  (:require [clojure.core.async :as async]
            [kube-api.core :as kube]
            [kube-api.controllers.utils :as utils]
            [clojure.tools.logging :as log])
  (:import [okhttp3 Response WebSocket]))


(defn list-watch-stream
  "Returns a core.async channel of watch events for the given resource / request.
   Automatically retries failed connections with an exponential backoff up to 5
   minutes max between attempts. If you close the returned channel then the watch
   will close itself on the next event."
  [client op-selector request]
  (let [return-chan (async/chan)
        prep-object (fn [object] (update object :kind #(or % (get op-selector :kind))))]
    (letfn [(start-from-list
              ([]
               (start-from-list "0"))
              ([resource-version]
               (start-from-list resource-version (utils/backoff-seq 300000)))
              ([resource-version backoff-seq]
               (let [list-request (-> request
                                      (assoc-in [:query-params :watch] false)
                                      (assoc-in [:query-params :resourceVersion] resource-version))
                     respond      (fn [list-response]
                                    (let [objects (mapv prep-object (get list-response :items []))]
                                      (when (async/>!! return-chan {:type   "SYNC"
                                                                    :kind   (get op-selector :kind)
                                                                    :object objects})
                                        (start-watching-at (utils/resource-version list-response)))))
                     raise        (fn [exception]
                                    (log/error exception "Exception listing resources.")
                                    (let [wait (first backoff-seq)]
                                      (Thread/sleep wait)
                                      (start-from-list resource-version (rest backoff-seq))))]
                 (kube/invoke client op-selector list-request respond raise))))
            (start-watching-at
              ([resource-version]
               (start-watching-at resource-version (take 10 (utils/backoff-seq 300000))))
              ([resource-version backoff-seq]
               (let [watch-request
                     (-> request
                         (assoc-in [:query-params :watch] true)
                         (assoc-in [:query-params :resourceVersion] resource-version)
                         (assoc-in [:query-params :allowWatchBookmarks] true))
                     last-observed-version
                     (volatile! resource-version)
                     callbacks
                     {:on-text
                      (fn [^WebSocket socket {:keys [type object] :as message}]
                        (let [new-resource-version (utils/resource-version object)]
                          (vreset! last-observed-version new-resource-version)
                          (when (contains? #{"ADDED" "MODIFIED" "DELETED"} type)
                            (when-not (async/>!! return-chan (assoc (update message :object prep-object) :kind (get op-selector :kind)))
                              (.close socket 1000 "Normal Closure")))))
                      :on-failure
                      (fn [socket exception ^Response response]
                        (if (#{410} (.code response))
                          (do
                            (log/error exception "Websocket connection failed with status 410, beginning new listing.")
                            (start-from-list))
                          (do
                            (log/errorf exception "Websocket connection failed with response %s. Attempting to reconnect where we left off." (str response))
                            (if-some [wait (first backoff-seq)]
                              (do
                                (Thread/sleep wait)
                                (start-watching-at (deref last-observed-version)))
                              (start-from-list (deref last-observed-version))))))
                      :on-closed
                      (fn [socket code reason]
                        (log/info "Websocket connection was closed. Will attempt to reconnect where we left off.")
                        (if-some [wait (first backoff-seq)]
                          (do (Thread/sleep wait)
                              (start-watching-at (deref last-observed-version)))
                          (start-from-list)))}]
                 (kube/connect client op-selector watch-request callbacks))))]
      (start-from-list)
      return-chan)))
