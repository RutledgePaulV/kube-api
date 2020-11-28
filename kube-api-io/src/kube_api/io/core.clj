(ns kube-api.io.core
  (:require [kube-api.core :as kube]
            [kube-api.io.io :as io])
  (:import [okhttp3 WebSocket]
           [okio ByteString]
           [java.io OutputStream]
           [java.nio.channels ServerSocketChannel]
           [java.net InetSocketAddress]))


(defn exec
  "Builds on 'connect' to implement process<>process communications using byte streams
   multiplexed over a lone websocket connection. Returns a map of input/output streams
   that you can use to communicate with the process spawned in the pod."
  [client op-selector request]
  (let [channels    (cond-> {:meta (io/piped-pair)}
                      (true? (get-in request [:query-params :stdout]))
                      (assoc :out (io/piped-pair))
                      (true? (get-in request [:query-params :stderr]))
                      (assoc :err (io/piped-pair))
                      (true? (get-in request [:query-params :stdin]))
                      (assoc :in (io/piped-pair)))
        pumper-prom (promise)
        callbacks   {:on-open    (fn [^WebSocket socket response]
                                   (when-some [in (get-in channels [:in :in])]
                                     (let [on-bytes (fn on-bytes [^bytes bites]
                                                      (.send socket (ByteString/of bites)))
                                           on-close (fn on-close []
                                                      )]
                                       (deliver pumper-prom (io/pump in on-bytes on-close {:flag (byte 0)})))))
                     :on-bytes   (fn [socket ^ByteString message]
                                   (let [channel (.getByte message 0)
                                         bites   (.toByteArray (.substring message 1))]
                                     (case channel
                                       1 (.write ^OutputStream (get-in channels [:out :out]) bites)
                                       2 (.write ^OutputStream (get-in channels [:err :out]) bites)
                                       3 (.write ^OutputStream (get-in channels [:meta :out]) bites)
                                       (throw (ex-info "Unknown byte stream channel." {})))))
                     :on-closing (fn [socket code reason]
                                   (.close socket code reason))
                     :on-closed  (fn [socket code reason]
                                   (io/close! [pumper-prom channels]))}
        socket      (kube/connect client op-selector request callbacks)]
    (cond-> {:socket socket}
      (contains? channels :out)
      (assoc :stdout (get-in channels [:out :in]))
      (contains? channels :err)
      (assoc :stderr (get-in channels [:err :in]))
      (contains? channels :in)
      (assoc :stdin (get-in channels [:in :out]))
      (contains? channels :meta)
      (assoc :meta (get-in channels [:meta :in])))))



(defn port-forward [client op-selector request {:keys [remote-port local-port bind] :or {bind "127.0.0.1"}}]
  (let [local-server (doto (ServerSocketChannel/open)
                       (.bind (InetSocketAddress. ^String bind (int local-port))))
        receive-loop (future
                       (loop [handles []]
                         (if (not (.isInterrupted (Thread/currentThread)))
                           (let [local-socket (.accept local-server)
                                 pumper       (promise)
                                 callbacks    {:on-open    (fn [websocket response]
                                                             )
                                               :on-text    (fn [websocket message]
                                                             )
                                               :on-bytes   (fn [websocket message]
                                                             )
                                               :on-closing (fn [websocket code reason]
                                                             )
                                               :on-closed  (fn [websocket code reason]
                                                             )
                                               :on-failure (fn [websocket throwable response]
                                                             )}
                                 websocket    (kube/connect client op-selector request callbacks)]
                             (recur (conj handles {:local-socket local-socket :websocket websocket})))
                           (doseq [{:keys [websocket local-socket]} handles]
                             ))))]
    ))




;(defn copy-to-pod [client namespace pod source destination]
;  )
;
;(defn copy-from-pod [client namespace pod source destination]
;  )


(comment



  (port-forward client
                {:operation "connectCoreV1GetNamespacedPodPortforward"}
                {:query-params {:ports remote-port}
                 :path-params  {:namespace namespace :name name}}
                {:remote-port 3000
                 :local-port  3333
                 :bind        "0.0.0.0"})

  )