(ns kube-api.term.core
  (:require [kube-api.io.core :as kio]
            [kube-api.core.core :as kube]
            [kube-api.term.ui :as ui]
            [kube-api.io.io :as io])
  (:import [java.io OutputStream InputStreamReader]
           [com.jediterm.terminal TtyConnector]
           [okhttp3 WebSocket]))

(defn make-connector [client namespace name]
  (let [init   (delay (let [streams
                            (kio/exec client
                                      {:kind      "PodExecOptions"
                                       :operation "connectCoreV1GetNamespacedPodExec"
                                       :action    "connect"}
                                      {:path-params  {:namespace namespace :name name}
                                       :query-params {:command "bash"
                                                      :tty     true
                                                      :stdin   true
                                                      :stdout  true
                                                      :stderr  true}})]
                        (assoc streams :reader (InputStreamReader. (:stdout streams)))))
        closed (promise)]
    (reify TtyConnector
      (init [this question]
        (let [{:keys [^WebSocket socket]} (force init)]
          true))
      (resize [this term-size pixel-size]
        (let [{:keys [^WebSocket socket]} (force init)
              msg (io/command {:Width  (.-width term-size) :Height (.-height term-size)})]
          (.send socket msg)))
      (getName [this]
        (str namespace "/" name))
      (read [this buf offset length]
        (let [{:keys [^InputStreamReader reader]} (force init)
              chars (.read reader buf offset length)]
          (when (= -1 chars)
            (.close this)
            (deliver closed true))
          chars))
      (^void write [this ^bytes bites]
        (let [{:keys [^OutputStream stdin]} (force init)]
          (.write stdin bites)
          (.flush stdin)
          nil))
      (isConnected [this]
        (and (force init) (not (realized? closed))))
      (^void write [this ^String string]
        (.write this (.getBytes string "UTF-8")))
      (waitFor [this]
        (force init)
        0)
      (^void close [this]
        (let [{:keys [^WebSocket socket]} (force init)]
          (.close socket 1000 ""))))))

(defn create-terminal [client namespace pod]
  (ui/create-frame (fn [] (make-connector client namespace pod))))

(comment

  (def client (kube/create-client "do-nyc1-k8s-1-19-3-do-2-nyc1-1604718220356"))

  (create-terminal client "default" "bash")
  )