(ns kube-api.utils
  (:require [clojure.string :as strings]
            [malli.core :as m]
            [malli.error :as me]
            [malli.generator :as gen])
  (:import [java.security SecureRandom]
           [java.io ByteArrayInputStream]
           [java.util.regex Pattern]
           [java.util Base64]))


(defonce random-gen
  (SecureRandom/getInstanceStrong))


(defn map-vals
  [f m]
  (letfn [(f* [agg k v] (assoc! agg k (f v)))]
    (with-meta
      (persistent! (reduce-kv f* (transient (or (empty m) {})) m))
      (meta m))))


(defn index-by [f coll]
  (into {} (map (juxt f identity)) coll))


(def validator-factory
  (memoize (fn [schema] (m/validator schema))))

(def generator-factory
  (memoize (fn [schema] (gen/generator schema))))


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


(defn validation-error [message schema data]
  (let [error     (-> (m/explain schema data)
                      (me/with-spell-checking)
                      (me/humanize))
        as-string (with-out-str (clojure.pprint/pprint error))]
    (throw (ex-info (str message \newline as-string) {:error error}))))


(defn validate! [message schema data]
  (let [validator (validator-factory schema)]
    (when-not (validator data)
      (validation-error message schema data))))


(defmacro defmethodset [symbol dispatch-keys & body]
  `(doseq [dispatch# ~dispatch-keys] (defmethod ~symbol dispatch# ~@body)))


(def ^:private named-groups
  (let [method
        (doto (.getDeclaredMethod Pattern "namedGroups" (into-array Class []))
          (.setAccessible true))]
    (fn [pattern]
      (.invoke method pattern (into-array Object [])))))


(defn match-groups [re s]
  (let [matcher (re-matcher re s)]
    (and (.matches matcher)
         (->> (keys (named-groups re))
              (reduce #(if-some [match (.group matcher ^String %2)]
                         (assoc %1 (keyword %2) match)
                         %1)
                      {})))))
