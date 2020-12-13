(ns kube-api.io.io-test
  (:require [clojure.test :refer :all])
  (:require [kube-api.io.io :refer :all])
  (:import [okio Buffer]
           [java.nio.charset Charset StandardCharsets]))

(deftest pump-test
  (let [channel (doto (Buffer.)
                  (.writeString "Testing" StandardCharsets/UTF_8)
                  (.flush))
        bites   (atom [])
        fut     (pump channel
                      (fn [bs] (swap! bites conj bs))
                      (fn [] (swap! bites conj :close))
                      {})]
    (is (non-blocking-io? channel))
    (Thread/sleep 1000)
    (let [bites @bites]
      (println bites)
      (is (= 1 (count bites)))
      (when-some [bs ^bytes (first bites)]
        (is (= "Testing" (String. bs (Charset/forName "UTF-8"))))))
    (.close channel)
    (Thread/sleep 1000)
    (is (.isDone fut))
    (let [bites @bites]
      (is (= 2 (count bites)))
      (if-some [bs (second bites)]
        (is (= :close bs))
        (is (some? (second bites)))))))
