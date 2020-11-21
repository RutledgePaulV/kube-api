(ns kube-api.controllers.utils)

(defn mk-path [object]
  [(get-in object [:kind]) (get-in object [:metadata :namespace]) (get-in object [:metadata :name])])

(defn resource-version [object]
  (get-in object [:metadata :resourceVersion]))

(defn index-by [f coll]
  (into {} (map (juxt f identity)) coll))

(defn backoff-seq
  "Returns an infinite seq of exponential back-off timeouts with random jitter."
  [max]
  (->>
    (lazy-cat
      (->> (cons 0 (iterate (partial * 2) 1000))
           (take-while #(< % max)))
      (repeat max))
    (map (fn [x] (+ x (rand-int 1000))))))

(defn dissoc-in [m [k & ks :as keys]]
  (if ks
    (if-let [nextmap (get m k)]
      (let [newmap (dissoc-in nextmap ks)]
        (if (seq newmap)
          (assoc m k newmap)
          (dissoc m k)))
      m)
    (dissoc m k)))

(defn fixed-point [f x]
  (loop [[[x1 x2] :as parts]
         (partition 2 1 (iterate f x))]
    (if (= x1 x2) x1 (recur (rest parts)))))

(defn compact [compactor coll]
  (fixed-point
    (fn [collection]
      (if (= 1 (count collection))
        collection
        (->> (partition 2 1 collection)
             (mapcat (fn [[x y]] (compactor x y)))
             (dedupe))))
    coll))