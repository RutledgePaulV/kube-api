(ns kube-api.term.term
  (:gen-class
    :extends com.jediterm.terminal.ui.AbstractTerminalFrame
    :constructors {[java.util.Map String String] []}
    :state state
    :init init))

(defn -init [client namespace name]
  (println client namespace name)
  [[] {:client client :namespace namespace :name name}])

(defn -createTtyConnector [this]
  (println this)
  ((requiring-resolve 'kube-api.term.core/make-connector)
   (deref (requiring-resolve 'kube-api.term.core/client))
   (deref (requiring-resolve 'kube-api.term.core/namespace))
   (deref (requiring-resolve 'kube-api.term.core/name))))