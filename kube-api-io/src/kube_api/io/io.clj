(ns kube-api.io.io
  "Utilities for working with blocking and non-blocking io."
  (:require [clojure.tools.logging :as log]
            [muuntaja.core :as muuntaja])
  (:import [java.io Closeable InputStream OutputStream IOException PipedInputStream PipedOutputStream]
           [java.nio.channels ReadableByteChannel WritableByteChannel]
           [java.util.concurrent.atomic AtomicLong]
           [java.util.concurrent Executors ThreadFactory Future ThreadPoolExecutor]
           [java.nio ByteBuffer]
           [clojure.lang IPending IDeref]
           [okio ByteString]
           [java.nio.charset Charset]
           [okhttp3 Call WebSocket]))


(defmacro try-root
  "Used just like the special form try, except pulls out the root
   cause and catches on that instead of whatever exception might
   wrap it. Useful for catching exceptions surrounding futures and
   the like."
  [& body]
  (letfn [(form? [form]
            (and (or (seq? form) (list? form)) (symbol? (first form))))
          (catch-form? [form]
            (and (form? form) (= 'catch (first form))))
          (finally-form? [form]
            (and (form? form) (= 'finally (first form))))
          (tail? [form]
            (or (catch-form? form) (finally-form? form)))]
    (let [[inner-body remainder]
          (split-with (complement tail?) body)]
      `(try
         (try ~@inner-body
              (catch Throwable e#
                (throw (stack/root-cause e#))))
         ~@remainder))))

(defprotocol Close
  (close [this]))

(extend-protocol Close
  WebSocket
  (close [^WebSocket this]
    (.close this 1000 ""))
  Call
  (close [^Call this]
    (.cancel this))
  Closeable
  (close [^Closeable this]
    (.close this))
  Future
  (close [^Future this]
    (.cancel this true))
  Thread
  (close [^Thread this]
    (.interrupt this))
  ThreadPoolExecutor
  (close [^ThreadPoolExecutor this]
    (.shutdown this)))

(defn deferred? [x]
  (and (instance? IDeref x) (instance? IPending x)))

(defn walk-seq [form]
  (letfn [(branch? [form]
            (or (and (not (string? form)) (seqable? form))
                (and (deferred? form) (realized? form))))
          (children [form]
            (if (deferred? form) (list (deref form)) (seq form)))]
    (tree-seq branch? children form)))

(defmacro quietly [& body]
  `(try ~@body (catch Exception e# nil)))

(defn closeable? [x]
  (satisfies? Close x))

(defn close-quietly! [x]
  (when (closeable? x)
    (quietly (close x))))

(defn process? [x]
  (instance? Thread x)
  (instance? Future x))

(defn source? [x]
  (or (instance? InputStream x)
      (instance? ReadableByteChannel x)))

(defn sink? [x]
  (or (instance? OutputStream x)
      (instance? WritableByteChannel x)))

(defn classic-io? [x]
  (or (instance? InputStream x)
      (instance? OutputStream x)))

(defn non-blocking-io? [x]
  (or (instance? ReadableByteChannel x)
      (instance? WritableByteChannel x)))

(defn priority [x]
  (cond
    (process? x) 1
    (source? x) 0
    :otherwise -1))

(defn close! [x]
  (->> (walk-seq x)
       (filter closeable?)
       (sort-by priority #(compare %2 %1))
       (run! close-quietly!)))

(defn piped-pair []
  (let [in (PipedInputStream.)]
    {:in in :out (PipedOutputStream. in)}))

(defn interrupted? []
  (.isInterrupted (Thread/currentThread)))

(defn uninterrupt []
  (Thread/interrupted))

(defonce executor
  (let [counter (AtomicLong.)]
    (Executors/newCachedThreadPool
      (reify ThreadFactory
        (newThread [this runnable]
          (doto (Thread. runnable)
            (.setDaemon true)
            (.setName (format "kube-api-background-task-%d" (.getAndIncrement counter)))))))))


(defn pump-classic-io [^InputStream stream on-bytes on-close {:keys [flags sleep buffer-size]}]
  (loop [buffer (byte-array buffer-size)]
    (if-not (interrupted?)
      (when-some [read (try
                         (.read stream buffer)
                         (catch IOException e
                           (when-not (interrupted?)
                             (log/error e "Unexpected exception while reading from source."))
                           (on-close)
                           nil))]
        (if-not (neg? read)
          (do
            (when (pos? read)
              (let [bites (byte-array (+ read (count flags)))]
                (dotimes [idx (count flags)]
                  (aset bites idx (byte (nth flags idx))))
                (System/arraycopy buffer 0 bites (count flags) read)
                (try
                  (on-bytes bites)
                  (catch Exception e
                    (log/error e "Exception using bytes from source.")))))
            (if (pos? (.available stream))
              (recur buffer)
              (do (Thread/sleep sleep)
                  (recur buffer))))
          (on-close)))
      (on-close))))


(defn pump-non-blocking-io [^ReadableByteChannel source on-bytes on-close {:keys [flags sleep buffer-size]}]
  (loop [buffer (ByteBuffer/allocate buffer-size)]
    (if-not (interrupted?)
      (do (doseq [flag flags] (.put buffer (byte flag)))
          (when-some
            [read (try
                    (.read source buffer)
                    (catch Exception e
                      (when-not (interrupted?)
                        (log/error e "Unexpected exception while reading from source."))
                      (when (interrupted?) (uninterrupt))
                      (on-close)
                      nil))]
            (when (pos? read)
              (.flip buffer)
              (let [bites (byte-array (.remaining buffer))]
                (.get buffer bites)
                (try
                  (on-bytes bites)
                  (catch Exception e
                    (log/error e "Exception using bytes from source.")))))
            (if (neg? read)
              (on-close)
              (do (when (zero? read)
                    (Thread/sleep sleep))
                  (recur (.clear buffer))))))
      (do (uninterrupt) (on-close)))))


(defn pump ^Future [source on-bytes on-close
                    {:keys [flags sleep buffer-size]
                     :or   {flags       []
                            sleep       50
                            buffer-size 4096}
                     :as   options}]
  (let [task
        (fn pump-task []
          (if (non-blocking-io? source)
            (pump-non-blocking-io source on-bytes on-close options)
            (pump-classic-io source on-bytes on-close options)))]
    (.submit executor ^Runnable task)))


(defn copy-to-channel ^bytes [^long channel ^bytes bites]
  (let [it (byte-array (inc (alength bites)))]
    (aset it 0 (byte channel))
    (System/arraycopy bites 0 it 1 (alength bites))
    it))

(defn command ^ByteString [message]
  (let [encoded (slurp (muuntaja/encode "application/json" message))
        bites   (.getBytes encoded (Charset/forName "UTF-8"))]
    (ByteString/of (copy-to-channel 4 bites))))
