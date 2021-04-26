(ns kube-api.core.auth-test
  (:require [clojure.test :refer :all]
            [kube-api.core.auth :refer :all]
            [clojure.java.io :as io]))


(defn exercise-user-config [config]
  (let [{:keys [middleware]}
        (inject-client-auth {} config)
        handler    (fn [request] {:status 200 :body request})
        handler+mw (reduce #(%2 %1) handler middleware)]
    (handler+mw {:uri "Test"})))

(defn testfile-path [filename]
  (.getAbsolutePath (io/file (io/resource (str "kube_api/" filename)))))

(deftest exec-configuration
  (let [response (exercise-user-config {:exec {:apiVersion "" :command "cat" :args [(testfile-path "token-response.json")]}})]
    (is (= "Bearer my-bearer-token" (get-in response [:body :headers "Authorization"])))))

(deftest basic-configuration
  (let [response (exercise-user-config {:username "paul" :password "hehe"})]
    (is (= ["paul" "hehe"] (get-in response [:body :basic-auth])))))

(deftest token-configuration
  (let [response (exercise-user-config {:token "my-bearer-token2"})]
    (is (= "Bearer my-bearer-token2" (get-in response [:body :headers "Authorization"])))))

(deftest token-file-configuration
  (let [response (exercise-user-config {:tokenFile (testfile-path "token.txt")})]
    (is (= "Bearer my-bearer-token3" (get-in response [:body :headers "Authorization"])))))

(deftest gcp-configuration
  (testing "initial auth with no tokens"
    (let [user-config {:auth-provider
                       {:name   "gcp"
                        :config {:cmd-args     (testfile-path "gcloud-response.json")
                                 :cmd-path     "cat"
                                 :expiry-key   "{.credential.token_expiry}"
                                 :token-key    "{.credential.access_token}"}}}
          response    (exercise-user-config user-config)]
      (is (= "Bearer billy.bob" (get-in response [:body :headers "Authorization"])))))

  (testing "initial auth with unexpired token"
    (let [user-config {:auth-provider
                       {:name   "gcp"
                        :config {:access-token "hihihi"
                                 :expiry       "5000-01-02T15:04:05.999999999Z07:00"
                                 :cmd-args     (testfile-path "gcloud-response.json")
                                 :cmd-path     "cat"
                                 :expiry-key   "{.credential.token_expiry}"
                                 :token-key    "{.credential.access_token}"}}}
          response    (exercise-user-config user-config)]
      (is (= "Bearer hihihi" (get-in response [:body :headers "Authorization"])))))

  (testing "initial auth with expired token"
    (let [user-config {:auth-provider
                       {:name   "gcp"
                        :config {:access-token "hihihi"
                                 :expiry       "1999-01-02T15:04:05.999999999Z07:00"
                                 :cmd-args     (testfile-path "gcloud-response.json")
                                 :cmd-path     "cat"
                                 :expiry-key   "{.credential.token_expiry}"
                                 :token-key    "{.credential.access_token}"}}}
          response    (exercise-user-config user-config)]
      (is (= "Bearer billy.bob" (get-in response [:body :headers "Authorization"]))))))