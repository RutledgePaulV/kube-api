(ns kube-api.ring.core
  (:require [kube-api.controllers.controller :as controller]
            [ring-firewall-middleware.core :as fw]
            [kube-api.core.core :as kube]))

(def default-client
  (delay (kube/create-client)))

(defn index-by [f coll]
  (into {} (map (juxt f identity)) coll))

(defn pod-ip [pod]
  (get-in pod [:status :podIP]))

(defn wrap-pod-authentication
  "Ring middleware that implements source IP authentication of kubernetes pods.
   The pod that made the request is added to the request map before invoking
   handler at the key :kube-api/source. Up-to-date access list is maintained
   via a perpetual kubernetes watch."
  [handler {:keys [client deny-handler]
            :or   {client       default-client
                   deny-handler fw/default-forbidden-handler}}]
  (let [current-state (atom {})
        op-selector   {:operation "listCoreV1PodForAllNamespaces"}
        request       {}
        streams       [[op-selector request]]
        synced        (promise)
        callback      (fn [{:keys [state]}]
                        (reset! current-state (index-by pod-ip (mapcat vals (vals (get state "Pod")))))
                        (deliver synced true))
        controller    (delay (controller/start-controller (force client) streams callback))
        get-state     (fn await-state []
                        (force controller)
                        (deref synced)
                        (deref current-state))]
    (fn pod-authentication-handler
      ([request]
       (let [pods-by-ip (get-state)
             allow-list (set (keys pods-by-ip))]
         ((fw/wrap-allow-ips
            (fn [request]
              (let [pod (get pods-by-ip (get request :remote-addr))]
                (handler (assoc request :kube-api/source pod))))
            {:allow-list   allow-list
             :deny-handler deny-handler})
          request)))
      ([request respond raise]
       (let [pods-by-ip (get-state)
             allow-list (set (keys pods-by-ip))]
         ((fw/wrap-allow-ips
            (fn [request respond raise]
              (let [pod (get pods-by-ip (get request :remote-addr))]
                (handler (assoc request :kube-api/source pod) respond raise)))
            {:allow-list   allow-list
             :deny-handler deny-handler})
          request respond raise))))))

(comment

  (def client (kube/create-client "do-nyc1-k8s-1-19-3-do-2-nyc1-1604718220356"))

  (def handler
    (fn [request]
      {:status 200
       :body   (get-in request [:kube-api/source :metadata])}))

  (def wrapped (wrap-pod-authentication handler {:client client}))

  (wrapped {:remote-addr "10.244.0.54"})

  )