(ns com.eldrix.hades.fixtures
  "Shared test fixtures: pinned identifiers, fixture preflight, HTTP
  server lifecycle, and request helper.

  Tests that need real terminology fixtures use `^:live`, assert the
  required well-known path exists, then call `hades/open` with those
  paths. Missing fixtures throw with the provisioning command.

  Run live tests with `clj -M:test`; skip them with `clj -M:test -e :live`."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string]
            [com.eldrix.hades.impl.http :as http])
  (:import (java.net ServerSocket URI)
           (java.net.http HttpClient HttpRequest HttpRequest$Builder
                          HttpRequest$BodyPublishers
                          HttpResponse$BodyHandlers)))

;; ---------------------------------------------------------------------------
;; Pinned fixture identifiers
;; ---------------------------------------------------------------------------

(def snomed-version       "20250201")
(def snomed-release-date  "2025-02-01")
(def snomed-mlds-package  "ihtsdo.mlds/167")
(def snomed-db-path       (str ".hades/snomed-intl-" snomed-version ".db"))

(def snomed-uk-db-path ".hades/snomed-uk-monolith.db")

(def loinc-version        "2.81")
(def loinc-db-path        (str ".hades/loinc-" loinc-version ".db"))

(def fhir-packages-dir    ".hades/fhir-packages")
(def fhir-smoke-db-path   ".hades/fhir-smoke.db")

(def tx-ecosystem-dir     ".hades/tx-ecosystem")

;; ---------------------------------------------------------------------------
;; Fixture preflight — assertions throw with a clear provisioning hint
;; ---------------------------------------------------------------------------

(defn- assert-exists!
  [path label install-hint]
  (when-not (.exists (io/file path))
    (throw (ex-info (str "Missing test fixture (" label ") at " path
                         ". Provision with:\n  " install-hint)
                    {:fixture label :path path :install install-hint})))
  path)

(defn assert-snomed-db! []
  (assert-exists! snomed-db-path "SNOMED CT International"
    (str "clj -M:run install " snomed-db-path
         " --dist " snomed-mlds-package "@" snomed-release-date
         " --username <MLDS user> --password <password file>"
         " && clj -M:run index " snomed-db-path
         " && clj -M:run compact " snomed-db-path)))

(defn assert-snomed-uk-db! []
  (assert-exists! snomed-uk-db-path "SNOMED CT UK Monolith"
    (str "clj -M:run install " snomed-uk-db-path
         " --dist uk.nhs/sct-monolith"
         " --api-key <trud-api-key-file>"
         " && clj -M:run index "   snomed-uk-db-path
         " && clj -M:run compact " snomed-uk-db-path)))

(defn assert-loinc-db! []
  (assert-exists! loinc-db-path (str "LOINC " loinc-version)
    (str "clj -M:run import " loinc-db-path " /path/to/Loinc_" loinc-version)))

(def fhir-packages
  "Pinned FHIR packages used by the parity test. Versions track the CI
  cache key in `.github/workflows/ci.yml`; bump both together."
  ["hl7.terminology.r4@7.0.1"
   "hl7.fhir.us.core@6.1.0"
   "hl7.fhir.uv.ips@2.0.0"])

(defn assert-fhir-packages! []
  ;; --cache-dir makes install land the extracted JSON at
  ;; <cache>/<id>-<version>/package/ — exactly the layout the parity
  ;; test walks. The SQLite container is the positional dest.
  (let [install-hint (str "clj -M:run install " fhir-smoke-db-path
                          " "
                          (clojure.string/join " "
                                               (map #(str "--dist " %) fhir-packages))
                          " --cache-dir " fhir-packages-dir)]
    (assert-exists! fhir-packages-dir "FHIR packages directory" install-hint)
    (assert-exists! fhir-smoke-db-path "FHIR packages SQLite container" install-hint)))

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
