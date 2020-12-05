(ns kube-api.core.auth-test
  (:require [clojure.test :refer :all]
            [kube-api.core.auth :refer :all]
            [clojure.java.io :as io]))


(defn exercise-config [config]
  (let [{:keys [middleware]}
        (inject-client-auth {} config)
        handler    (fn [request] {:status 200 :body request})
        handler+mw (reduce #(%2 %1) handler middleware)]
    (handler+mw {:uri "Test"})))

(deftest exec-configuration
  (let [response
        (exercise-config
          {:exec
           {:apiVersion ""
            :command    "cat"
            :args       [(.getAbsolutePath (io/file (io/resource "kube_api/token-response.json")))]}})]
    (is (= "Bearer my-bearer-token" (get-in response [:body :headers "Authorization"])))))

(deftest basic-configuration
  (let [response (exercise-config {:username "paul" :password "hehe"})]
    (is (= ["paul" "hehe"] (get-in response [:body :basic-auth])))))