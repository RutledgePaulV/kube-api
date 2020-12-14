(ns kube-api.core.core-test
  (:require [clojure.test :refer :all]
            [kube-api.core.core :as kube]
            [kube-api.test.core :as test]
            [malli.core :as m]
            [clojure.set :as sets]))

(defonce test-kubeconfig
  (delay (test/get-or-init-test-cluster "kube-api-core")))

(def test-client
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

(deftest valid-crd-schemas
  (testing "json schemas for json schema translated okay to malli"
    (doseq [op (kube/ops (force test-client) {:kind "CustomResourceDefinition"})]
      (let [spec (kube/spec (force test-client) op)]
        (is (m/schema (:request-schema spec)))
        (doseq [[_ response-schema] (:response-schemas spec)]
          (is (m/schema response-schema)))))))

(deftest valid-other-schemas
  (testing "every generated schema is a valid malli schema"
    (doseq [op (kube/ops (force test-client))
            :when (not= "CustomResourceDefinition" (:kind op))]
      (let [spec (kube/spec (force test-client) op)]
        (is (m/schema (:request-schema spec)))
        (doseq [[_ response-schema] (:response-schemas spec)]
          (is (m/schema response-schema)))))))

(deftest creation-test
  (testing "i can create a namespace"
    (let [op-selector {:action "create" :kind "Namespace"}
          ns-name     (name (gensym "test-namespace"))
          request     {:body
                       {:apiVersion "v1"
                        :kind       "Namespace"
                        :metadata   {:name ns-name}}}
          response    (kube/invoke (force test-client) op-selector request)]
      (is (= 201 (-> response meta :response :status)))
      (is (= ns-name (get-in response [:metadata :name]))))))