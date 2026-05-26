(ns com.eldrix.hades.fixtures
  "Shared test fixtures: pinned fixture paths, HTTP server lifecycle, and
  a request helper. SNOMED/LOINC/FTRM databases must be pre-provisioned
  (`hades/open` throws if a path is missing); FHIR package tarballs
  self-provision via `download!` (see `fhir-archive`)."
  (:require [clojure.data.json :as json]
            [com.eldrix.hades.impl.fhir-package :as fhir-package]
            [com.eldrix.hades.impl.http :as http])
  (:import (java.net ServerSocket URI)
           (java.net.http HttpClient HttpRequest HttpRequest$Builder
                          HttpRequest$BodyPublishers
                          HttpResponse$BodyHandlers)))

;; ---------------------------------------------------------------------------
;; Pinned fixture paths
;; ---------------------------------------------------------------------------

(def snomed-version    "20250201")
(def snomed-db-path    (str ".hades/snomed-intl-" snomed-version ".db"))
(def snomed-uk-db-path ".hades/snomed-uk-monolith.db")

(def loinc-version     "2.82")
(def loinc-db-path     (str ".hades/loinc-" loinc-version ".db"))

(def fhir-cache-dir    ".hades/fhir-cache")  ; download cache of FHIR package .tgz tarballs
(def fhir-tx-db-path   ".hades/fhir-tx.db")  ; single combined FTRM container (all FHIR packages incl VSAC)
(def tx-ecosystem-dir  ".hades/tx-ecosystem")

(def fhir-packages
  "Canonical FHIR R4 package set, installed into both the combined FTRM
  container (`fhir-tx-db-path`) and the download cache (`fhir-cache-dir`,
  one `.tgz` per package). Keep in sync with CI's `--dist` provisioning, or
  the two parity sides see different packages and the catalogue diff is
  meaningless. Mirrors the tx-benchmark upstream dataset, including
  `us.cdc.phinvads` (1,967 ValueSets) and `us.nlm.vsac` (9,071 ValueSets)."
  [["hl7.fhir.r4.core"     "4.0.1"]
   ["hl7.terminology.r4"   "7.0.1"]
   ["hl7.fhir.us.core"     "6.1.0"]
   ["hl7.fhir.uv.ips"      "2.0.0"]
   ["hl7.fhir.uv.ips"      "1.1.0"]
   ["fhir.tx.support.r4"   "0.34.0"]
   ["us.cdc.phinvads"      "0.12.0"]
   ["us.nlm.vsac"          "0.24.0"]])

(defn fhir-archive
  "Local tarball path for a FHIR package, via the same `download!` the
  install CLI uses: restores from `fhir-cache-dir` when present, otherwise
  fetches from the registry."
  [id version]
  (.getPath (fhir-package/download! id version fhir-cache-dir)))

(defn fhir-package-archives
  "Local tarball paths for the canonical `fhir-packages` set."
  []
  (mapv (fn [[id version]] (fhir-archive id version)) fhir-packages))

;; ---------------------------------------------------------------------------
;; HTTP server lifecycle
;; ---------------------------------------------------------------------------

(defn- free-port
  "Pick an ephemeral free port. Closes the probe socket before returning;
  Jetty re-binds the same port in the same process within microseconds, so
  the TOCTOU window is closed in practice."
  []
  (with-open [sock (ServerSocket. 0)]
    (.getLocalPort sock)))

(defn start-server
  "Start a Hades FHIR HTTP server fronting `svc`. Returns
  `{:server :port :url}`. The caller owns `svc`; `stop-server` does not
  close it."
  ([svc] (start-server svc {}))
  ([svc opts]
   (let [port (or (:port opts) (free-port))
         srv  (http/make-server svc (assoc opts :port port))]
     (http/start! srv)
     {:server srv :port port :url (str "http://localhost:" port "/fhir")})))

(defn stop-server
  "Stop a running server returned by `start-server`. Idempotent."
  [{:keys [server]}]
  (when server (http/stop! server)))

;; ---------------------------------------------------------------------------
;; HTTP request helper for tests
;; ---------------------------------------------------------------------------

(defn- http-client ^HttpClient [] (HttpClient/newHttpClient))

(defn- build-request
  ^HttpRequest [base-url {:keys [method path body headers]
                          :or   {method :get
                                 headers {"Accept" "application/fhir+json"}}}]
  (let [b (HttpRequest/newBuilder (URI. (str base-url path)))
        b (reduce-kv (fn [^HttpRequest$Builder bb k v] (.header bb k v)) b headers)]
    (case method
      :get  (.GET b)
      :post (let [json-body (if (string? body) body (json/write-str body))
                  b (.header b "Content-Type" "application/fhir+json")]
              (.POST b (HttpRequest$BodyPublishers/ofString json-body))))
    (.build b)))

(defn- parse-body
  "Decode the response body when content-type is JSON-ish; return the raw
  string otherwise. Tests that need the raw body for non-JSON content
  types can read `:raw`."
  [^String content-type ^String raw]
  (if (and content-type (re-find #"(?i)json" content-type))
    (try (json/read-str raw) (catch Exception _ raw))
    raw))

(defn request!
  "Execute one HTTP call against `base-url` (e.g. the `:url` returned by
  `start-server`). `req` is `{:method :path :body :headers}` — `body` may
  be a string or a map (encoded as JSON). Returns
  `{:status :content-type :body :raw}`."
  [base-url req]
  (let [^HttpRequest jreq (build-request base-url req)
        resp (.send (http-client) jreq (HttpResponse$BodyHandlers/ofString))
        ct   (-> resp .headers (.firstValue "content-type") (.orElse ""))
        raw  (.body resp)]
    {:status       (.statusCode resp)
     :content-type ct
     :raw          raw
     :body         (parse-body ct raw)}))
