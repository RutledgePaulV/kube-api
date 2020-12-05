(ns kube-api.core.core-test
  (:require [clojure.test :refer :all]
            [kube-api.core.core :as kube]
            [kube-api.test.core :as test]
            [malli.core :as m]
            [clojure.set :as sets]))

(defonce test-kubeconfig
  (delay (test/get-or-init-test-cluster "kube-api-core")))

(defonce test-client
  (delay (kube/create-client (force test-kubeconfig))))

(defn random-op-selector [operations]
  (let [unique-keys (disj (set (mapcat keys operations)) :operation)]
    (into {} (for [k (random-sample 0.5 unique-keys)]
               [k (get (rand-nth operations) k)]))))

(deftest ops-test
  (testing "operation IDs are distinct"
    (let [operations (kube/ops (force test-client))]
      (is (not-empty operations))
      (is (distinct? (map :operation operations)))))

  (testing "all operations conform to op schema"
    (let [operations    (kube/ops (force test-client))
          expected-keys #{:kind :operation :action :version :group}]
      (is (not-empty operations))
      (is (every? #(= expected-keys (set (keys %))) operations))))

  (testing "filtered operations are always supermaps of the selector"
    (let [operations (kube/ops (force test-client))]
      (dotimes [x 1000]
        (let [selector (random-op-selector operations)]
          (is (every?
                (fn [op] (sets/subset? (set (seq selector)) (set (seq op))))
                (kube/ops (force test-client) selector))))))))

(deftest schema-test
  (testing "service listing conforms to expected response schema"
    (let [op-selector    {:operation "listCoreV1ServiceForAllNamespaces"}
          spec           (kube/spec (force test-client) op-selector)
          results        (kube/invoke (force test-client) op-selector {})
          success-schema (get-in spec [:response-schemas :200])]
      (is (not-empty (:items results)))
      (is (m/validate success-schema results)))))

