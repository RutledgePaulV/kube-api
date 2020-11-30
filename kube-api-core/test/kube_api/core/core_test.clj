(ns kube-api.core.core-test
  (:require [clojure.test :refer :all]
            [kube-api.core.core :as kube]
            [kube-api.test.core :as test]
            [malli.core :as m]))

(defonce test-kubeconfig
  (delay (test/get-or-init-test-cluster "kube-api-core")))

(defonce test-client
  (delay (kube/create-client (force test-kubeconfig))))

(deftest ops-test
  (testing "operation IDs are distinct"
    (let [operations (kube/ops (force test-client))]
      (is (distinct? (map :operation operations)))))

  (testing "all operations contain kind, operation, action, version, group"
    (let [operations    (kube/ops (force test-client))
          expected-keys #{:kind :operation :action :version :group}]
      (is (every? #(= expected-keys (set (keys %))) operations)))))

(deftest schema-test
  (testing "service listing conforms to expected response schema"
    (let [op-selector    {:operation "listCoreV1ServiceForAllNamespaces"}
          spec           (kube/spec (force test-client) op-selector)
          results        (kube/invoke (force test-client) op-selector {})
          success-schema (get-in spec [:response-schemas :200])]
      (is (m/validate success-schema results)))))