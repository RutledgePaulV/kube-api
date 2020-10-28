A dynamic kubernetes client for Clojure driven by the swagger specification of the remote kubernetes api server.

Inspired by:

- https://github.com/nubank/k8s-api
- https://github.com/cognitect-labs/aws-api

Seeks to improve:

- Validation should match the server I'm targeting and not only a static swagger spec that was once bundled into a
  published jar.

- Support authentication using ~/.kube/config and service account token files so users don't have to glue that together
  themselves.

- Support automatic server ca config and client certificate config so users don't have to fuss with ssl contexts when
  using kube config files or service accounts.

- Produce more readable specifications and error messages using malli (typo assistance!).

- Support reactive watches (long poll).