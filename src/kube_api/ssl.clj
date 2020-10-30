(ns kube-api.ssl
  (:require [kube-api.utils :as utils])
  (:import [java.security.cert CertificateFactory Certificate]
           [java.security KeyStore KeyFactory]
           [javax.net.ssl TrustManagerFactory KeyManagerFactory]
           [java.security.spec PKCS8EncodedKeySpec]))

(defonce rsa-factory
  (KeyFactory/getInstance "RSA"))

(defonce x509-factory
  (CertificateFactory/getInstance "X.509"))


(utils/defmemo trust-managers [certificate]
  (let [ca (with-open [stream (utils/pem-stream certificate)]
             (.generateCertificate x509-factory stream))
        ks (doto (KeyStore/getInstance (KeyStore/getDefaultType))
             (.load nil)
             (.setCertificateEntry "caCert" ca))
        tf (doto (TrustManagerFactory/getInstance (TrustManagerFactory/getDefaultAlgorithm))
             (.init ks))]
    (.getTrustManagers tf)))


(utils/defmemo key-managers [client-certificate client-key]
  (let [cert-chain  (with-open [stream (utils/pem-stream client-certificate)]
                      (let [^"[Ljava.security.cert.Certificate;" ar (make-array Certificate 0)]
                        (.toArray (.generateCertificates x509-factory stream) ar)))
        private-key (with-open [stream (utils/pem-stream client-key)]
                      (.generatePrivate rsa-factory (PKCS8EncodedKeySpec. stream)))
        password    (utils/random-password 64)
        key-store   (doto (KeyStore/getInstance (KeyStore/getDefaultType))
                      (.load nil)
                      (.setKeyEntry "cert" private-key password cert-chain))
        kmf         (doto (KeyManagerFactory/getInstance (KeyManagerFactory/getDefaultAlgorithm))
                      (.init key-store password))]
    (.getKeyManagers kmf)))
