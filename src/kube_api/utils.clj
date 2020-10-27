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
        (strings/replace #".*BEGIN.*" "")
        (strings/replace #".*END.*" "")
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


(defn base64-string->bytes [^String s]
  (.decode (Base64/getDecoder) s))


(defn base64-string->stream [^String contents]
  (ByteArrayInputStream. (base64-string->bytes contents)))


(defn pem-stream [s]
  (base64-string->stream (pem-body s)))