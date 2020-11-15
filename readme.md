kube-api-core is a comprehensive, idiomatic, and data driven Kubernetes client for Clojure. The available operations
are dynamically constructed from the swagger definition hosted by the Kubernetes cluster you target.

Inspired by:
- [Cognitect's aws-api library](https://github.com/cognitect-labs/aws-api)
- [Nubank's k8s-api library](https://github.com/nubank/k8s-api)
- [Malli](https://github.com/metosin/malli)


kube-api-controllers tries to satisfy the same goals as the tools/cache package from the standard go client. 
Essentially, this is machinery for writing controllers (aka operators) that manages threads, watches, and 
state for you so that a user space controller implementation doesn't have to worry about so many details.

Inspired by:
- [This awesome 11 part series by Laird Nelson](https://lairdnelson.wordpress.com/2018/01/07/understanding-kubernetes-tools-cache-package-part-0/)