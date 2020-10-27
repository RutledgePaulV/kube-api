(ns kube-api.ssl
  (:import [java.security.cert CertificateFactory]
           [java.io ByteArrayInputStream]
           [java.security KeyStore]
           [javax.net.ssl TrustManagerFactory]
           [java.util Base64]))



(defn base64-decode [^String s]
  (let [decoder (Base64/getDecoder)]
    (.decode decoder s)))

(defn trust-managers [certificate]
  (let [cf (CertificateFactory/getInstance "X.509")
        ca (.generateCertificate cf (ByteArrayInputStream. (base64-decode certificate)))
        ks (doto (KeyStore/getInstance (KeyStore/getDefaultType))
             (.load nil)
             (.setCertificateEntry "caCert" ca))
        tf (doto (TrustManagerFactory/getInstance (TrustManagerFactory/getDefaultAlgorithm))
             (.init ks))]
    (.getTrustManagers tf)))

(alter-var-root #'trust-managers memoize)