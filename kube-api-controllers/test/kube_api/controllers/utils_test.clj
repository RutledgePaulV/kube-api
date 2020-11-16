(ns kube-api.controllers.utils-test
  (:require [kube-api.controllers.utils :refer :all]
            [clojure.test :refer :all]))

(deftest backoff-seq-test
  (is (not-empty (take 20 (backoff-seq 10)))))
