A dynamic kubernetes client for Clojure driven by the swagger specification of the remote kubernetes api server.

Inspired by:

- https://github.com/nubank/k8s-api
- https://github.com/cognitect-labs/aws-api

Seeks to improve:

- Validation should match the server I'm targeting and not a static swagger spec that was once bundled into a jar.

- Support authentication using ~/.kube/config and service account token files so users don't have to glue that together
  themselves.

- Support watches.

- Produce more readable endpoint specifications.