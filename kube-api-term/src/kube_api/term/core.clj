(ns kube-api.term.core
  (:require [kube-api.core :as kube]
            [kube-api.term.ui :as ui]
            [kube-api.io :as io])
  (:import [java.io OutputStream BufferedReader]
           [com.jediterm.terminal TtyConnector]
           [okhttp3 WebSocket]))

(defn make-connector [client namespace name]
  (let [init (delay (let [streams
                          (kube/exec client
                                     {:kind "PodExecOptions" :action "connect"}
                                     {:path-params  {:namespace namespace :name name}
                                      :query-params {:command "sh"
                                                     :tty     true
                                                     :stdin   true
                                                     :stdout  true
                                                     :stderr  true}})]
                      (assoc streams :reader (clojure.java.io/reader (:stdout streams)))))]
    (reify TtyConnector
      (init [this question]
        (let [{:keys [^WebSocket socket]} (force init)]
          true))
      (resize [this term-size pixel-size]
        (let [{:keys [^WebSocket socket]} (force init)
              msg (io/command {:Width  (.-width term-size)
                               :Height (.-height term-size)})]
          (.send socket msg)))
      (getName [this]
        (str namespace "/" name))
      (read [this buf offset length]
        (let [{:keys [^BufferedReader reader]} (force init)]
          (.read reader buf offset length)))
      (^void write [this ^bytes bites]
        (let [{:keys [^OutputStream stdin]} (force init)]
          (.write stdin bites)
          (.flush stdin)
          nil))
      (isConnected [this]
        (some? (force init)))
      (^void write [this ^String string]
        (let [{:keys [^OutputStream stdin]} (force init)]
          (.write stdin (.getBytes string "UTF-8"))
          (.flush stdin)
          nil))
      (waitFor [this]
        (force init)
        0)
      (^void close [this]
        (let [{:keys [^WebSocket socket]} (force init)]
          (.close socket 1000 ""))))))

(defn create-terminal [client namespace pod]
  (ui/create-frame (fn [] (make-connector client namespace pod))))

(comment
  (do
    (def client (kube/create-client "do-nyc1-k8s-1-19-3-do-2-nyc1-1604718220356"))
    (def namespace "default")
    (def name "sh")
    (create-terminal client namespace name))
  )