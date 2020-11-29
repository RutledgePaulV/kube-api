Usage

```clojure 

(require '[kube-api.test.core :as ktest])
(require '[kube-api.core.core :as kube])
(require '[kube-api.core.auth :as kauth])

(ktest/create-cluster "testing")

(def config (ktest/get-kube-config "testing"))

(def current-context (kauth/current-context config))

(def client (kube/create-client current-context))



```