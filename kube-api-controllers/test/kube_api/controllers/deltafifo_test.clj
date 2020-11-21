(ns kube-api.controllers.deltafifo-test
  (:require [clojure.test :refer :all])
  (:require [kube-api.controllers.deltafifo :refer :all]
            [clojure.core.async :as async]))

(deftest compactor-test
  (testing "Modifications are collapsed into the addition"
    (let [event1    {:type     "ADDED"
                     :resource ["Deployment" "kube-system" "coredns"]
                     :old      nil
                     :new      {:version 1}}
          event2    {:type     "MODIFIED"
                     :resource ["Deployment" "kube-system" "coredns"]
                     :old      {:version 1}
                     :new      {:version 2}}
          compacted (compactor [event1 event2])]
      (is (= 1 (count compacted)))
      (is (= (assoc event1 :new (:new event2)) (first compacted)))))
  (testing "Modifications are collapsed together"
    (let [event1    {:type     "MODIFIED"
                     :resource ["Deployment" "kube-system" "coredns"]
                     :old      {:version 0}
                     :new      {:version 1}}
          event2    {:type     "MODIFIED"
                     :resource ["Deployment" "kube-system" "coredns"]
                     :old      {:version 1}
                     :new      {:version 2}}
          compacted (compactor [event1 event2])]
      (is (= 1 (count compacted)))
      (is (= (assoc event1 :new (:new event2)) (first compacted)))))) /

(defn resource [kind namespace name spec]
  {:kind kind :metadata {:name name :namespace namespace} :spec spec})

(defn deployment [namespace name spec]
  (resource "Deployment" name namespace spec))

(defn event [type object]
  {:type type :object object})

(deftest controller-data-feed-test
  (let [inbound     (async/chan 100)
        feedback    (async/chan)
        outbound    (deltafifo [inbound] feedback)
        object-1-v1 (deployment "kube-system" "core-dns" {:version 1})
        object-1-v2 (deployment "kube-system" "core-dns" {:version 2})
        object-2-v1 (deployment "istio" "citadel" {:version 1})]
    (async/>!! inbound (event "ADDED" object-1-v1))
    (async/>!! inbound (event "MODIFIED" object-1-v2))
    (async/>!! inbound (event "ADDED" object-2-v1))
    (let [{:keys [type old new state]} (async/<!! outbound)]
      (is (= type "ADDED"))
      (is (= old nil))
      (is (= new object-1-v2)))
    (let [{:keys [type old new state] :as msg} (async/<!! outbound)]
      (is (= type "ADDED"))
      (is (= old nil))
      (is (= new object-2-v1))
      ; demonstrate a 'failed' message being placed back onto the 'queue'
      (async/>!! feedback msg))
    ; no new novelty compacted the message so it's immediately replayed
    (let [{:keys [type old new state] :as msg} (async/<!! outbound)]
      (is (= type "ADDED"))
      (is (= old nil))
      (is (= new object-2-v1))
      ; new novelty that will compact the message being placed back onto the queue
      (async/>!! inbound (event "DELETED" object-2-v1))
      ; demonstrate a 'failed' message being placed back onto the 'queue'
      (async/>!! feedback msg))
    ; show that the failed message was compacted
    (let [{:keys [type old new state] :as msg} (async/<!! outbound)]
      (is (= type "DELETED"))
      (is (= old object-2-v1))
      (is (= new nil)))))
