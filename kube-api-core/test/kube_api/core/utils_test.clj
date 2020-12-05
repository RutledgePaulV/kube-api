(ns kube-api.core.utils-test
  (:require [clojure.test :refer :all])
  (:require [kube-api.core.utils :refer :all]))

(deftest merge+-test
  (are [expected x y] (= expected (merge+ x y))
    {:x 1} {:x 2} {:x 1}
    {:x 1 :y 2} {:x 1} {:y 2}
    {:x 1 :y 2} {:y 2} {:x 1}
    {:x [1 2]} {:x [1]} {:x [2]}
    {:x [2 1]} {:x [2]} {:x [1]}
    {:x #{1 2}} {:x #{2}} {:x [1]}))
