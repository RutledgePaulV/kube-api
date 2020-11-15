[![Build Status](https://travis-ci.com/rutledgepaulv/kube-api.svg?branch=master)](https://travis-ci.com/rutledgepaulv/kube-api)
[![Clojars Project](https://img.shields.io/clojars/v/org.clojars.rutledgepaulv/kube-api.svg)](https://clojars.org/org.clojars.rutledgepaulv/kube-api)
[![Clojars Project](https://img.shields.io/clojars/v/org.clojars.rutledgepaulv/kube-api-core.svg)](https://clojars.org/org.clojars.rutledgepaulv/kube-api-core)
[![Clojars Project](https://img.shields.io/clojars/v/org.clojars.rutledgepaulv/kube-api-controllers.svg)](https://clojars.org/org.clojars.rutledgepaulv/kube-api-controllers)

## Modules

### kube-api

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
(k8s/docs client op-selector)

; what does a sample request look like?
(k8s/gen-request client op-selector)

; now write your actual request
(def request {:path-params {:namespace "kube-system"}})
    
; now perform the request
(def response (k8s/invoke client op-selector request))

; already parsed as data
(def num-deployments-in-kube-system (count (:items response)))

; but what were the response headers and status?
(select-keys (meta response) [:status :headers])

```

### kube-api-controllers

Satisfies the same goals as the tools/cache package from the standard go client. Provides machinery for writing
controllers (aka operators) that manages threads, watches, and state for you so that a user space controller
implementation doesn't have to worry about all those gruesome details.

Inspired by:

- [This awesome 11 part series by Laird Nelson](https://lairdnelson.wordpress.com/2018/01/07/understanding-kubernetes-tools-cache-package-part-0/)

#### Usage

```clojure

```

## License

This project is licensed under [MIT license](http://opensource.org/licenses/MIT).