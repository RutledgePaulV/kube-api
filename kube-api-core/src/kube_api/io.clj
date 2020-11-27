(ns kube-api.io
  (:require [muuntaja.core :as muuntaja])
  (:import [java.io PipedOutputStream PipedInputStream InputStream IOException]
           [okio ByteString]
           [java.nio.charset Charset]))

(defn piped-pair []
  (let [in (PipedInputStream.)]
    {:in in :out (PipedOutputStream. in)}))

(defn copy-to-channel ^bytes [^long channel ^bytes bites]
  (let [it (byte-array (inc (alength bites)))]
    (aset it 0 (byte channel))
    (System/arraycopy bites 0 it 1 (alength bites))
    it))

(defn pumper
  ([^InputStream stream f]
   (pumper stream nil f))
  ([^InputStream stream flag f]
   (loop [buffer (byte-array 1024)]
     (when-not (.isInterrupted (Thread/currentThread))
       (when-some [length (try
                            (.read stream buffer)
                            (catch IOException e
                              nil))]
         (when-not (neg? length)
           (if (some? flag)
             (let [it (byte-array (inc length))]
               (aset it 0 (byte flag))
               (System/arraycopy buffer 0 it 1 length)
               it)
             (let [it (byte-array length)]
               (System/arraycopy buffer 0 it 0 length)
               (f it)))
           (if (pos? (.available stream))
             (recur buffer)
             (do (Thread/sleep 50)
                 (recur buffer)))))))))

(defn command ^ByteString [message]
  (let [encoded (slurp (muuntaja/encode "application/json" message))
        bites   (.getBytes encoded (Charset/forName "UTF-8"))]
    (ByteString/of (copy-to-channel 4 bites))))
