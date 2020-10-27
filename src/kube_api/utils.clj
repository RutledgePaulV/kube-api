(ns kube-api.utils
  (:require [clojure.string :as strings]))


(defn index-by [f coll]
  (into {} (map (juxt f identity)) coll))


(defn meta-merge
  ([] {})
  ([m1] m1)
  ([m1 m2]
   (letfn [(inner-merge [m1 m2]
             (cond
               (and (map? m1) (map? m2))
               (meta-merge m1 m2)
               (and (sequential? m1) (sequential? m2))
               (vec (concat m1 m2))
               :otherwise m2))]
     (merge-with inner-merge m1 m2)))
  ([m1 m2 & more]
   (reduce meta-merge {} (into [m1 m2] more))))


(defn join-segment [a b]
  (case [(strings/ends-with? a "/")
         (strings/starts-with? b "/")]
    ([true false] [false true]) (str a b)
    [true true] (str a (subs b 1))
    [false false] (str a "/" b)))


(defn mk-url [& segments]
  (reduce join-segment (flatten segments)))


(defn clean-cert [s]
  (-> s
      (strings/replace #".*BEGIN\s+CERTIFICATE.*" "")
      (strings/replace #".*END\s+CERTIFICATE.*" "")
      (strings/trim)
      (strings/split-lines)
      (apply str)))