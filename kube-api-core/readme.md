
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
