[![Build Status](https://travis-ci.com/rutledgepaulv/kube-api.svg?branch=master)](https://travis-ci.com/rutledgepaulv/kube-api)

## What

A set of Clojure libraries for interacting with Kubernetes from a Clojure application / repl. 
Composed of a core kubernetes client + some higher level constructs built on top of the client
(usually with some added dependencies). 

## Why

Some Clojure Kubernetes libraries already exist, but they're not very comprehensive. I want
something robust enough that I can write production cluster operators in Clojure. The fabric8
client for java is robust and comprehensive but it's somewhat painful to use from Clojure and
any attempts to layer over it would likely result in something similar to amazonica, and anyone 
who has used cognitect's aws-api knows how much nicer it is to have a data driven API.


## Modules

[![Clojars Project](https://img.shields.io/clojars/v/org.clojars.rutledgepaulv/kube-api.svg)](https://clojars.org/org.clojars.rutledgepaulv/kube-api)

This module bundles all modules (described below) for ease of use.

---

[![Clojars Project](https://img.shields.io/clojars/v/org.clojars.rutledgepaulv/kube-api-core.svg)](https://clojars.org/org.clojars.rutledgepaulv/kube-api-core)

[View code examples](./kube-api-core)

This implements the basic REST / websocket client code to communicate with the Kubernetes API. It defines the available
operations using the swagger specification served from the remote Kubernetes cluster. You can use this to CRUD on
Kubernetes resources and to just explore the available operations.

Inspired by:

- [Cognitect's aws-api library](https://github.com/cognitect-labs/aws-api)
- [Nubank's k8s-api library](https://github.com/nubank/k8s-api)

Leverages:

- [malli](https://github.com/metosin/malli)
- [clj-okhttp](https://github.com/rutledgepaulv/clj-okhttp)

---

[![Clojars Project](https://img.shields.io/clojars/v/org.clojars.rutledgepaulv/kube-api-term.svg)](https://clojars.org/org.clojars.rutledgepaulv/kube-api-term)

[View code examples](./kube-api-term)

This adapts the byte streams of a Kubernetes "exec" call into a terminal emulator so you can display an interactive
shell into the selected Kubernetes pod.

Leverages:

- [jediterm](https://github.com/JetBrains/jediterm)

---

[![Clojars Project](https://img.shields.io/clojars/v/org.clojars.rutledgepaulv/kube-api-controllers.svg)](https://clojars.org/org.clojars.rutledgepaulv/kube-api-controllers)

[View code examples](./kube-api-controllers)

This satisfies the same goals as the tools/cache package from the standard go client. Provides machinery for writing
controllers (aka operators) that manages threads, watches, retries, and state for you so that your user space controller
implementation doesn't have to worry about so many details.

Inspired by:

- [This awesome 11 part series on tools/cache by Laird Nelson](https://lairdnelson.wordpress.com/2018/01/07/understanding-kubernetes-tools-cache-package-part-0/)

Leverages:

- [core.async](https://github.com/clojure/core.async)

--- 

## License

This project is licensed under [MIT license](http://opensource.org/licenses/MIT).