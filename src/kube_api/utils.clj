(ns kube-api.utils
  (:require [clojure.string :as strings])
  (:import [java.security SecureRandom]
           [java.util Base64]
           [java.io ByteArrayInputStream]))


(defonce random-gen (SecureRandom/getInstanceStrong))


(defn index-by [f coll]
  (into {} (map (juxt f identity)) coll))


(defn merge+
  ([] {})
  ([m1] m1)
  ([m1 m2]
   (letfn [(inner-merge [m1 m2]
             (cond
               (and (map? m1) (map? m2))
               (merge+ m1 m2)
               (and (sequential? m1) (sequential? m2))
               (vec (concat m1 m2))
               :otherwise m2))]
     (merge-with inner-merge m1 m2)))
  ([m1 m2 & more]
   (reduce merge+ {} (into [m1 m2] more))))


(defn join-segment [a b]
  (case [(strings/ends-with? a "/")
         (strings/starts-with? b "/")]
    ([true false] [false true]) (str a b)
    [true true] (str a (subs b 1))
    [false false] (str a "/" b)))


(defn mk-url [& segments]
  (reduce join-segment (flatten segments)))


(defn pem-body [s]
  (strings/join ""
    (-> s
        (strings/replace #".*BEGIN\s+.*" "")
        (strings/replace #".*END\s+.*" "")
        (strings/trim)
        (strings/split-lines))))


(defn random-password ^"[C" [length]
  (let [bites (.nextBytes random-gen (byte-array (* 2 length)))
        chars (char-array length)]
    (doseq [[i [a b]] (map-indexed vector (partition-all 2 bites))]
      (let [c (char (bit-or (bit-shift-left a 8) b))]
        (aset chars i c)))
    chars))


(defmacro defmemo [& defnargs]
  `(doto (defn ~@defnargs)
     (alter-var-root #(with-meta (memoize %) (meta %)))))


(defn base64-string->bytes ^"[B" [^String s]
  (.decode (Base64/getDecoder) s))


(defn base64-string->stream [^String contents]
  (ByteArrayInputStream.
    (base64-string->bytes contents)))


(defn pem-stream [s]
  (base64-string->stream (pem-body s)))


(defn render-template-string [s ctx]
  (letfn [(replace [[_ variable]]
            (if-some [[_ v] (find ctx (keyword variable))]
              (str v)
              (throw (ex-info "Missing template variable." {:variable variable :context ctx}))))]
    (strings/replace s #"\{([^\{\}]+)\}" replace)))


(defn pointer->path [pointer]
  (->> (strings/split pointer #"/")
       (remove #{"#"})
       (map keyword)
       (into [])))

(defn branch? [form]
  (or (and (not (string? form)) (seqable? form))
      (and (delay? form) (realized? form))))

(defn children [form]
  (if (delay? form) (list (force form)) (seq form)))

(defn walk-seq [form]
  (tree-seq branch? children form))

(defn seek
  ([pred coll]
   (seek pred coll nil))
  ([pred coll not-found]
   (reduce (fn [nf x] (if (pred x) (reduced x) nf)) not-found coll)))

(defn dfs
  ([pred form]
   (seek pred (walk-seq form)))
  ([pred form not-found]
   (seek pred (walk-seq form) not-found)))

(defn rand-submap [m]
  (select-keys m (random-sample 0.05 (keys m))))