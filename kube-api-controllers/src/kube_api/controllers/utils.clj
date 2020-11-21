(ns kube-api.controllers.utils)


(defn mk-path [object]
  [(get-in object [:kind]) (get-in object [:metadata :namespace]) (get-in object [:metadata :name])])

(defn resource-version [object]
  (get-in object [:metadata :resourceVersion]))

(defn index-by [f coll]
  (into {} (map (juxt f identity)) coll))

(defn parse-number [s]
  (if (number? s)
    s
    (try
      (Long/parseLong s)
      (catch NumberFormatException e
        (Double/parseDouble s)))))

(defn version-descending [resources]
  (sort-by (comp parse-number resource-version) #(compare %2 %1) resources))

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

(defn concise-resource [s]
  (clojure.walk/postwalk
    (fn [form]
      (if (and (map? form) (contains? form :metadata))
        {:metadata (select-keys (get form :metadata) [:name :namespace])}
        form))
    s))



(defn compaction [compactor coll]
  (cond
    (empty? coll)
    []
    (= 1 (count coll))
    coll
    (= 2 (count coll))
    (compactor (first coll) (second coll))
    (= 3 (count coll))
    (let [[one two three] coll
          c1                 (set (compactor one two))
          c2                 (set (compactor two three))
          c1-preserved-one   (contains? c1 one)
          c1-preserved-two   (contains? c1 two)
          c1-introduced      (first (disj c1 one two))
          c2-preserved-two   (contains? c2 two)
          c2-preserved-three (contains? c2 three)
          c2-introduced      (first (disj c2 two three))]
      (case [c1-preserved-one c1-preserved-two (boolean c1-introduced)
             c2-preserved-two c2-preserved-three (boolean c2-introduced)]
        [true true false true false false]
        [one two]
        [false true false true true false]
        [two three]
        [true true false true true false]
        [one two three]
        [true true false false true false]
        [one two three]
        [false false true true true false]
        (let [c (compactor c1-introduced three)]
          (if (= 1 (count c)) c [one two three]))
        [true true false false false true]
        (let [c (compactor two c2-introduced)]
          (if (= 1 (count c)) c [one two three]))
        [false false false true true false]
        [two three]
        [true true false false false false]
        [one two]
        [false false true false false true]
        (compactor c1-introduced c2-introduced)
        (throw (IllegalStateException.)))
      )
    ))