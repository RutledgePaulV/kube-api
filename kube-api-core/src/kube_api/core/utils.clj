(ns kube-api.core.utils
  (:require [clojure.string :as strings]
            [malli.core :as m]
            [malli.error :as me]
            [malli.generator :as gen]
            [clojure.pprint :as pprint]
            [malli.util :as mu]
            [malli.transform :as mt])
  (:import [clojure.lang IPending IObj]
           [java.util Base64]))


(defn map-vals
  [f m]
  (letfn [(f* [agg k v] (assoc! agg k (f v)))]
    (with-meta
      (persistent! (reduce-kv f* (transient (or (empty m) {})) m))
      (meta m))))

(defn index-by [f coll]
  (into {} (map (juxt f identity)) coll))

(def validator-factory
  (memoize (fn [schema] (m/validator (mu/closed-schema schema)))))

(def generator-factory
  (memoize (fn [schema] (gen/generator (mu/closed-schema schema)))))

(defn in-kubernetes? []
  (not (strings/blank? (System/getenv "KUBERNETES_SERVICE_HOST"))))

(defn merge+
  ([] {})
  ([m1] m1)
  ([m1 m2]
   (letfn [(inner-merge [m1 m2]
             (cond
               (and (map? m1) (map? m2))
               (merge+ m1 m2)
               (and (sequential? m1) (sequential? m2))
               (into m1 m2)
               (and (set? m1) (or (set? m2) (sequential? m2)))
               (into m1 m2)
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


(defn promise? [x]
  (instance? IPending x))

(defn seek
  ([pred coll]
   (seek pred coll nil))
  ([pred coll not-found]
   (reduce (fn [nf x] (if (pred x) (reduced x) nf)) not-found coll)))

(def default-collection-transformer
  (mt/default-value-transformer {:defaults {:map (constantly {})}}))

(defn validation-error [message schema data]
  (let [error     (-> schema
                      (m/decode data default-collection-transformer)
                      (mu/closed-schema)
                      (m/explain data)
                      (me/with-spell-checking)
                      (me/humanize))
        as-string (with-out-str (pprint/pprint error))]
    (throw (ex-info (str message \newline as-string) {:error error}))))


(def default-transformer
  (mt/default-value-transformer))

(defn prepare [schema data]
  (m/decode schema data default-transformer))

(defn validate! [message schema data]
  (let [validator (validator-factory schema)]
    (when-not (validator data)
      (validation-error message schema data))))


(defmacro defmethodset [symbol dispatch-keys & body]
  `(doseq [dispatch# ~dispatch-keys] (defmethod ~symbol dispatch# ~@body)))

(defn base64-decode [^String s]
  (try
    (base64-decode
      (when-not (strings/blank? s)
        (String. (.decode (Base64/getDecoder) s))))
    (catch Exception e
      s)))

(def schema-resolver-transformer
  (mt/transformer
    {:default-decoder
     {:compile (fn [schema _]
                 (fn [value]
                   (if (instance? IObj value)
                     (vary-meta value assoc :resolved schema)
                     value)))}}))

(def decoder-factory
  (memoize (fn [schema] (m/decoder schema schema-resolver-transformer))))

(defn resolve-schema [schema value]
  (some->
    (if-some [transform (decoder-factory schema)]
      (some-> value transform meta :resolved)
      value)))

(defn dispatch-key [schema value]
  (let [schema (resolve-schema schema value)]
    (:dispatch-key (m/properties schema))))