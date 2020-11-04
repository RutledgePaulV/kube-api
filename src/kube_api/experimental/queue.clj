(ns kube-api.experimental.queue
  "Exponential back-off queue processing."
  (:require [clojure.tools.logging :as log])
  (:import [java.util.concurrent Delayed TimeUnit DelayQueue Executors ThreadFactory ExecutorService]
           [clojure.lang IFn ISeq]))


(deftype TimeoutTask [^long timestamp ^IFn task ^ISeq backoffs]
  Delayed
  (getDelay [this time-unit]
    (let [remainder (- timestamp (System/currentTimeMillis))]
      (.convert time-unit remainder TimeUnit/MILLISECONDS)))
  (compareTo
    [this other]
    (let [ostamp (.-timestamp ^TimeoutTask other)]
      (if (< timestamp ostamp) -1 (if (= timestamp ostamp) 0 1))))
  IFn
  (invoke [this]
    (task))
  Object
  (hashCode [this]
    (.hashCode task))
  (equals [this that]
    (= (.-task this) (.-task ^TimeoutTask that))))


(defn backoff-seq
  "Returns an infinite seq of exponential back-off timeouts with random jitter."
  [max]
  (->>
    (lazy-cat
      (->> (cons 0 (iterate (partial * 2) 1000))
           (take-while #(< % max)))
      (repeat max))
    (map (fn [x] (+ x (rand-int 1000))))))


(defn create-queue []
  (DelayQueue.))


(defn enqueue [^DelayQueue queue ^IFn f & args]
  (.put queue (TimeoutTask. 0 #(apply f args) nil)))


(defn process! [^DelayQueue queue]
  (loop []
    (let [result ^TimeoutTask (.take queue)]
      (case (try
              ((.-task result))
              ::continue
              (catch InterruptedException e
                ::halt)
              (catch Throwable e
                (let [[backoff & remainder] (or (.-backoffs result) (backoff-seq 300000))
                      current-time (System/currentTimeMillis)
                      retry-time   (+ current-time backoff)
                      retry-task   (TimeoutTask. retry-time (.-task result) remainder)]
                  (log/error e (format "Exception processing task from queue. Will retry in %d milliseconds." backoff))
                  (.put queue retry-task)
                  ::continue)))
        ::continue (recur)
        ::halt true))))


(defn ^ExecutorService new-executor [n]
  (let [factory
        (reify ThreadFactory
          (newThread [this runnable]
            (doto (Thread. runnable)
              (.setDaemon true))))]
    (Executors/newFixedThreadPool n factory)))


(defn worker-pool [n]
  (let [queue    (create-queue)
        executor (new-executor n)
        callback (reify Callable
                   (call [this]
                     (process! queue)))
        start    (delay
                   (dotimes [_ n]
                     (.submit executor callback)))
        stop     (delay (.shutdownNow executor))]
    {:queue    queue
     :start    (fn [] (force start))
     :stop     (fn [] (force stop) nil)
     :executor executor}))