(ns com.eldrix.hades.impl.server
  "Public entry point for the Hades FHIR terminology server."
  (:require [com.eldrix.hades.impl.http :as http]))

(defn make-server
  "Create an unstarted Hades FHIR server connector. Use `start!` and `stop!`
  to control the lifecycle.

  Options:
    :port               — TCP port (default 8080)
    :host               — bind address (default \"0.0.0.0\")
    :max-expansion-size — soft limit on ValueSet expansion total (default 10000)"
  [opts]
  (http/make-server opts))

(defn start!
  "Start a Hades server connector; returns the connector."
  [connector]
  (http/start! connector))

(defn stop!
  "Stop a Hades server connector; returns the connector."
  [connector]
  (http/stop! connector))

(defn build-tx-ctx
  "Build an overlay ctx from a seq of resource maps (plain string-keyed)."
  [resource-maps]
  (http/build-tx-ctx resource-maps))
