(ns kube-api.io
  (:require [muuntaja.core :as muuntaja])
  (:import [java.io PipedOutputStream PipedInputStream InputStream]
           [okio ByteString]
           [java.nio.charset Charset]))

(defn piped-pair []
  (let [in (PipedInputStream.)]
    {:in in :out (PipedOutputStream. in)}))

(defn pump [^InputStream stream flag f]
  (loop [buffer (byte-array 1024)]
    (when-not (.isInterrupted (Thread/currentThread))
      (when-some [length (.read stream buffer)]
        (when-not (neg? length)
          (let [immutable (byte-array (inc length))]
            (aset immutable 0 (byte flag))
            (System/arraycopy buffer 0 immutable 1 length)
            (f immutable))
          (if (pos? (.available stream))
            (recur buffer)
            (do (Thread/sleep 50)
                (recur buffer))))))))

(defn command ^ByteString [message]
  (let [encoded (slurp (muuntaja/encode "application/json" message))
        bites   (.getBytes encoded (Charset/forName "UTF-8"))
        frame   (byte-array (inc (alength bites)))]
    ; channel 4 is the command channel
    (aset frame 0 (byte 4))
    (System/arraycopy bites 0 frame 1 (alength bites))
    (ByteString/of bites)))
