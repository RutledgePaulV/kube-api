[![Build Status](https://travis-ci.com/rutledgepaulv/kube-api.svg?branch=master)](https://travis-ci.com/rutledgepaulv/kube-api)

## What

A set of Clojure libraries for interacting with Kubernetes from Clojure applications. Composed of a core Kubernetes
client + various modules offering higher level constructs (sometimes with added dependencies).

## Why

Some Clojure Kubernetes libraries already exist, but they're not comprehensive. I want something robust enough that I
can write production cluster integrations (controllers, operators, etc) in Clojure. I really enjoy the data orientation
that you find in other Clojure libraries like cognitect's aws-api and have tried to provide the same here.

The [fabric8.io kubernetes client](https://github.com/fabric8io/kubernetes-client) for java is robust and comprehensive
but it's rather painful to use from Clojure due to its focus on OOP ergonomics. That said, the fabric8 implementation
has been my primary reference when implementing the trickier pieces of IO.

## Modules

### kube-api/kube-api

[![Clojars Project](https://img.shields.io/clojars/v/kube-api/kube-api.svg)](https://clojars.org/kube-api/kube-api)

This module bundles all modules (described below) for ease of use.

---

### kube-api/kube-api-core

[![Clojars Project](https://img.shields.io/clojars/v/kube-api/kube-api-core.svg)](https://clojars.org/kube-api/kube-api-core)

[View code examples](./kube-api-core)

This implements the basic REST and websocket client code to communicate with the Kubernetes API. It defines the
available operations using the swagger specification served from the remote Kubernetes cluster. You can use this to CRUD
on Kubernetes resources and to explore the available operations.

Note that this core is intentionally minimal. All interactions with Kubernetes boil down to either some http calls or
some websocket connections and that's all this module provides. If you're looking for more complex things like
`exec` and `port-forward` have a look at the [kube-api-io](./kube-api-io) module that creates those things from these
blocks.

Inspired by:

- [Cognitect's aws-api library](https://github.com/cognitect-labs/aws-api)
- [Nubank's k8s-api library](https://github.com/nubank/k8s-api)

Leverages:

- [malli](https://github.com/metosin/malli)
- [clj-okhttp](https://github.com/rutledgepaulv/clj-okhttp)

---

### kube-api/kube-api-io

[![Clojars Project](https://img.shields.io/clojars/v/kube-api/kube-api-io.svg)](https://clojars.org/kube-api/kube-api-io)

[View code examples](./kube-api-io)

This implements higher level IO constructs like those you're accustomed to from kubectl. This is where you'll find
things like `exec`, `attach`, `logs`, `port-forward`, `proxy`, and `cp`.

Leverages:

- Java IO / NIO

---

### kube-api/kube-api-controllers

[![Clojars Project](https://img.shields.io/clojars/v/kube-api/kube-api-controllers.svg)](https://clojars.org/kube-api/kube-api-controllers)

[View code examples](./kube-api-controllers)

This satisfies the same goals as the tools/cache package from the standard go client. Provides machinery for writing
controllers (aka operators) that manages threads, watches, retries, and state for you so that your user space controller
implementation doesn't need to worry about so many details.

Inspired by:

- [This awesome 11 part series on tools/cache by Laird Nelson](https://lairdnelson.wordpress.com/2018/01/07/understanding-kubernetes-tools-cache-package-part-0/)

Leverages:

- [core.async](https://github.com/clojure/core.async)

--- 

### kube-api/kube-api-term

[![Clojars Project](https://img.shields.io/clojars/v/kube-api/kube-api-term.svg)](https://clojars.org/kube-api/kube-api-term)

[View code examples](./kube-api-term)

This module adapts the byte streams and command channel of an `exec` call into the requirements of a terminal emulator
so you can display an interactive shell into the selected Kubernetes pod.

Leverages:

- [jediterm](https://github.com/JetBrains/jediterm)

---

### kube-api/kube-api-ring

[![Clojars Project](https://img.shields.io/clojars/v/kube-api/kube-api-ring.svg)](https://clojars.org/kube-api/kube-api-ring)

[View code examples](./kube-api-ring)

This module implements ring middleware for pod source ip based authentication. You can use this for service<>service
authentication within a Kubernetes cluster (so long as the traffic originates from elsewhere in the cluster).

---

### kube-api/kube-api-test

[![Clojars Project](https://img.shields.io/clojars/v/kube-api/kube-api-test.svg)](https://clojars.org/kube-api/kube-api-test)

[View code examples](./kube-api-test)

This module provides tooling to help write tests that can interact with an isolated kubernetes cluster
using [kind](https://kind.sigs.k8s.io/). This module is used to test kube-api itself.

---

## License

This project is licensed under [MIT license](http://opensource.org/licenses/MIT).