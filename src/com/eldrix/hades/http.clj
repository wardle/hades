(ns com.eldrix.hades.http
  "Public HTTP adapter for Hades. Exposes a `TerminologyService` over
  HTTP using Pedestal+Jetty.

  Lifetime: the caller owns the service and the connector. `start!`
  attaches the service to the connector's interceptor chain and
  starts Jetty. `stop!` stops Jetty but does NOT close the service —
  call `core/close` separately.

  Options:
    :port               — TCP port (default 8080)
    :host               — bind address (default \"0.0.0.0\")
    :max-expansion-size — soft limit on expansion total (default 10000)
    :max-body-bytes     — hard cap on POST body size in bytes; requests
                          over this return 413 (default 16 MiB)"
  (:require [com.eldrix.hades.impl.http :as impl]))

(defn make-server
  "Build an unstarted Jetty connector wired to `svc`. Use `start!` to
  bind the port and begin serving."
  ([svc] (make-server svc {}))
  ([svc opts] (impl/make-server svc opts)))

(defn start!
  "Start the connector. Returns it for use with `stop!`."
  [connector]
  (impl/start! connector))

(defn stop!
  "Stop the connector. The underlying service is not closed."
  [connector]
  (impl/stop! connector))
