[![Build Status](https://travis-ci.com/rutledgepaulv/kube-api.svg?branch=master)](https://travis-ci.com/rutledgepaulv/kube-api)
[![Clojars Project](https://img.shields.io/clojars/v/org.clojars.rutledgepaulv/kube-api.svg)](https://clojars.org/org.clojars.rutledgepaulv/kube-api)
[![Clojars Project](https://img.shields.io/clojars/v/org.clojars.rutledgepaulv/kube-api-core.svg)](https://clojars.org/org.clojars.rutledgepaulv/kube-api-core)
[![Clojars Project](https://img.shields.io/clojars/v/org.clojars.rutledgepaulv/kube-api-controllers.svg)](https://clojars.org/org.clojars.rutledgepaulv/kube-api-controllers)
[![Clojars Project](https://img.shields.io/clojars/v/org.clojars.rutledgepaulv/kube-api-term.svg)](https://clojars.org/org.clojars.rutledgepaulv/kube-api-term)


---

## Modules

### kube-api

This is an uber module that just bundles all the available modules.

- `[org.clojars.rutledgepaulv/kube-api "0.1.0-SNAPSHOT"]`

is equivalent to

- `[org.clojars.rutledgepaulv/kube-api-core "0.1.0-SNAPSHOT"]`
- `[org.clojars.rutledgepaulv/kube-api-controllers "0.1.0-SNAPSHOT"]`
- `[org.clojars.rutledgepaulv/kube-api-term "0.1.0-SNAPSHOT"]`

---

### kube-api-core

A comprehensive, idiomatic, and data driven Kubernetes client for Clojure. The available operations are dynamically
constructed from the swagger definition hosted by the Kubernetes cluster you target so it should always be version
compatible.

Inspired by:

- [Cognitect's aws-api library](https://github.com/cognitect-labs/aws-api)
- [Nubank's k8s-api library](https://github.com/nubank/k8s-api)
- [Malli](https://github.com/metosin/malli)

#### Usage

```clojure 

(require '[kube-api.core :as k8s])

; what cluster do you want to target for local dev?
(def context-name "microk8s")

; this will use your ~/.kube/config file by default,
; or if this code was running inside a pod, it would 
; use the service account auth
(def client (k8s/create-client context-name))

; what ops are available?
(k8s/ops client)

; okay, I want to get deployments (optionally include :version and :group)
(def op-selector {:kind "Deployment" :action "get"})
 
; tell me more about this operation
(k8s/spec client op-selector)

; what does a sample request look like?
(k8s/generate-request client op-selector)

; now write your actual request
(def request {:path-params {:namespace "kube-system"}})
    
; now perform the request
(def response-data (k8s/invoke client op-selector request))

; already parsed as data
(def num-deployments-in-kube-system (count (:items response-data)))

; but what were the response headers and status?
(select-keys (:response (meta response-data)) [:status :headers])

```

---

### kube-api-controllers

Satisfies the same goals as the tools/cache package from the standard go client. Provides machinery for writing
controllers (aka operators) that manages threads, watches, and state for you so that a user space controller
implementation doesn't have to worry about all those gruesome details.

Inspired by:

- [This awesome 11 part series by Laird Nelson](https://lairdnelson.wordpress.com/2018/01/07/understanding-kubernetes-tools-cache-package-part-0/)

#### Usage

```clojure

(require '[kube-api.controllers.controller :as kcc])
(require '[kube-api.core :as kube])

(defonce client (kube/create-client "microk8s"))

(defn dispatch [{:keys [type old new]}]
  [type (or (get-in new [:kind]) (get-in old [:kind]))])

(defmulti on-event #'dispatch)

(defmethod on-event :default [{:keys [state] :as event}]
  (locking *out*
    (println "Ignoring:" (dispatch event))
    ; note that every event also receives a state map that contains the most recent resource
    ; of everything being observed on any of your controller's event streams
    (println "Number of pods in kube-system:" (count (vals (get-in state ["Pod" "kube-system"]))))
    (println "Number of deployments in kube-system:" (count (vals (get-in state ["Deployment" "kube-system"]))))))

(defmethod on-event ["ADDED" "Pod"] [{pod :new}]
  (locking *out* (println "Saw new pod:" (get-in pod [:metadata :name]))))

(defmethod on-event ["MODIFIED" "Pod"] [{old-pod :old new-pod :new}]
  (locking *out* (diff/pretty-print (diff/diff old-pod new-pod))))

(defmethod on-event ["DELETED" "Deployment"] [{old-deployment :old}]
  (locking *out* (println "Saw deployment was deleted" (get-in old-deployment [:metadata :name]))))

(def pod-op-selector {:kind "Pod" :action "list"})
(def pod-request {:path-params {:namespace ""}}) ; "" is how you say 'all namespaces'
(def pod-stream [pod-op-selector pod-request])

(def deployment-op-selector {:kind "Deployment" :action "list"})
(def deployment-request {:path-params {:namespace ""}})  ; "" is how you say 'all namespaces'
(def deployment-stream [deployment-op-selector deployment-request])

(def targets [pod-stream deployment-stream])

(def controller
  (kcc/start-controller client targets on-event))

; when you're done, stop the controller by calling it
(controller)

```

---

## License

This project is licensed under [MIT license](http://opensource.org/licenses/MIT).