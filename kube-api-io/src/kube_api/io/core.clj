(ns kube-api.io.core
  (:require [kube-api.core.core :as kube]
            [clojure.tools.logging :as log]
            [kube-api.io.io :as io])
  (:import [okhttp3 WebSocket]
           [okio ByteString]
           [java.io OutputStream IOException]
           [java.nio.channels ServerSocketChannel]
           [java.net InetSocketAddress]
           [java.nio.charset StandardCharsets]
           [java.nio ByteBuffer]))


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
                                                      (try
                                                        (.send socket (ByteString/of bites))
                                                        (catch IOException e
                                                          (log/error e "Exception writing bytes to remote socket."))))
                                           on-close (fn on-close []
                                                      )]
                                       (deliver pumper-prom (io/pump in on-bytes on-close
                                                                     {:flags       [(byte 0)]
                                                                      :sleep       0
                                                                      :buffer-size 1024})))))
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



(defn port-forward [client op-selector request {:keys [local-port bind] :or {bind "127.0.0.1"}}]
  (let [local-server (doto (ServerSocketChannel/open)
                       (.bind (InetSocketAddress. ^String bind (int local-port))))
        receive-loop (fn socket-receive-loop []
                       (loop [handles []]
                         (if (not (.isInterrupted (Thread/currentThread)))
                           (let [local-socket    (.accept local-server)
                                 _               (log/info "Accepted new local connection to port-forward.")
                                 message-count   (atom 2)
                                 close-both-ways (fn [websocket status reason]
                                                   (.close websocket status reason)
                                                   (.close local-socket))
                                 pumper          (promise)
                                 on-message      (fn [socket ^ByteBuffer buffer]
                                                   (log/info "Got message from server on websocket for port-forward.")
                                                   (when (neg? (swap! message-count dec))
                                                     (when-not (.hasRemaining buffer)
                                                       (log/error "No bytes in buffer!")
                                                       (close-both-ways socket 1002 "Protocol error"))
                                                     (let [channel (.get buffer)]
                                                       (println channel)
                                                       (case channel
                                                         ; data
                                                         0 (try
                                                             (.write local-socket buffer)
                                                             (catch IOException e
                                                               (log/error e "Error writing to local socket.")
                                                               (close-both-ways 1002 "Protocol error.")))
                                                         ; error
                                                         1 (do
                                                             (log/error "Error returned from remote.")
                                                             (.close local-socket))
                                                         (close-both-ways socket 1002 "Protocol error")))))
                                 callbacks       {:on-open    (fn [^WebSocket websocket response]
                                                                (log/info "Established new remote connection for port-forward socket.")
                                                                (deliver pumper
                                                                         (io/pump
                                                                           local-socket
                                                                           (fn on-bytes [^bytes bites]
                                                                             (try
                                                                               (.send websocket (ByteString/of bites))
                                                                               (catch IOException e
                                                                                 (log/error e "Exception writing to remote socket.")
                                                                                 (close-both-ways 1000 ""))))
                                                                           (fn on-close []
                                                                             (close-both-ways 1000 ""))
                                                                           {:buffer-size 4096 :flags [0]})))
                                                  :on-text    (fn [^WebSocket websocket message]
                                                                (let [bites (.getBytes ^String message StandardCharsets/UTF_8)]
                                                                  (on-message websocket (ByteBuffer/wrap bites))))
                                                  :on-bytes   (fn [^WebSocket websocket ^ByteString message]
                                                                (on-message websocket (.asByteBuffer message)))
                                                  :on-closing (fn [websocket code reason]
                                                                (.close local-socket))
                                                  :on-closed  (fn [websocket code reason]
                                                                (.close local-socket))
                                                  :on-failure (fn [websocket throwable response]
                                                                (.close local-socket))}
                                 websocket       (kube/connect client op-selector request callbacks)]
                             (recur (conj handles {:local-socket local-socket :pumper (deref pumper) :websocket websocket})))
                           (do (doseq [{:keys [websocket local-socket]} handles]
                                 (.close websocket 1000 "")
                                 (.close local-socket))
                               (.close local-server)))))]
    {:server local-server
     :port   local-port
     :bind   bind
     :future (future (receive-loop))}))




;(defn copy-to-pod [client namespace pod source destination]
;  )
;
;(defn copy-from-pod [client namespace pod source destination]
;  )


(comment

  (def client (kube/create-client "do-nyc1-k8s-1-19-3-do-2-nyc1-1604718220356"))

  (def forward
    (port-forward
      client
      {:operation "connectCoreV1GetNamespacedPodPortforward"}
      {:query-params {:ports 80}
       :path-params  {:namespace "default" :name "my-nginx-6b74b79f57-z5dmk"}}
      {:local-port 3333 :bind "0.0.0.0"}))

  )