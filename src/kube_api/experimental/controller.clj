(ns kube-api.experimental.controller
  "Implements the informer pattern from the client-go kubernetes client. Useful
   for building robust controller / observer implementations in Clojure."
  (:require [kube-api.core :as kube]))


