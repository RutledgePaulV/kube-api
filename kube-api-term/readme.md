
### Usage

```clojure 

(require '[kube-api.term.core :as ktc])
(require '[kube-api.core.core :as kube])

(defonce client (kube/create-client "microk8s"))

(def namespace "default")
(def pod "hello-world-234243-asf32")
(ktc/terminal client namespace pod) ; this line pops open a swing frame with an attached shell

```