(ns kube-api.controllers.workqueue-test
  (:require [clojure.test :refer :all])
  (:require [kube-api.controllers.workqueue :refer [backoff-seq]]))

(deftest backoff-seq-test
  (is (not-empty (take 20 (backoff-seq 10)))))
