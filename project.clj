(defproject org.clojars.rutledgepaulv/kube-api "0.1.0-SNAPSHOT"

  :plugins
  [[lein-sub "0.3.0"]]

  :sub
  ["kube-api-core" "kube-api-controllers"]

  :aliases
  {"test" ["sub" "test"]})
