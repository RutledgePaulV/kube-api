(ns kube-api.utils
  (:require [clojure.string :as strings]
            [malli.core :as m]
            [malli.error :as me]
            [malli.generator :as gen]
            [clojure.pprint :as pprint])
  (:import [java.util.regex Pattern]
           [clojure.lang IPending]))


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


(defn validation-error [message schema data]
  (let [error     (-> (m/explain schema data)
                      (me/with-spell-checking)
                      (me/humanize))
        as-string (with-out-str (pprint/pprint error))]
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
