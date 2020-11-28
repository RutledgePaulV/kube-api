#### Usage

```clojure

(require '[kube-api.controllers.controller :as kcc])
(require '[kube-api.core.core :as kube])

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
