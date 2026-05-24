(ns com.eldrix.hades.impl.fhir-package
  "Fetch FHIR conformance packages from a FHIR Package Registry.

  A registry serves npm-style metadata at `<base>/<id>` and tarballs at
  `<base>/<id>/<version>`. We span `default-registries`, but listing and
  download use different semantics: metadata/version listing UNIONS across
  registries (a registry can carry a 200 metadata doc with a truncated
  version list — packages.fhir.org stops at us.nlm.vsac 0.17.0 while
  packages2 has 0.24.0 — so first-hit-wins would hide versions), whereas a
  tarball download walks the chain and takes the first registry that serves
  the bytes (404 falls through; other failures propagate so a transient
  outage is not masked as not-found)."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [clojure.tools.logging.readable :as log]
            [com.eldrix.hades.providers.common.canonical :as canonical])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)
           (java.nio.file Files StandardCopyOption)
           (java.time Duration)))

(set! *warn-on-reflection* true)

(def default-registries
  "Ordered FHIR Package Registries, tried first-to-last. Note the path
  shapes differ — packages2 serves under `/packages` — so entries are
  full base URLs, not bare hosts."
  ["https://packages.fhir.org"
   "https://packages2.fhir.org/packages"])

(def browsable-registry
  "Human-browsable FHIR Package Registry index (Simplifier-hosted)."
  "https://registry.fhir.org/")

(def known-packages
  "Curated short list of FHIR packages users commonly install. The full
  registry holds >1200 packages — too many to dump to a terminal — so
  this list shows concrete starting points and `print-known` points at
  the browsable registry for everything else."
  [{:id "hl7.fhir.r4.core"     :description "FHIR R4 core specification"}
   {:id "hl7.fhir.r4b.core"    :description "FHIR R4B core specification"}
   {:id "hl7.fhir.r5.core"     :description "FHIR R5 core specification"}
   {:id "hl7.terminology.r4"   :description "HL7 standard CodeSystems and ValueSets (R4)"}
   {:id "hl7.terminology.r5"   :description "HL7 standard CodeSystems and ValueSets (R5)"}
   {:id "hl7.fhir.us.core"     :description "US Core implementation guide"}
   {:id "hl7.fhir.uv.ips"      :description "International Patient Summary"}])

;; ---------------------------------------------------------------------------
;; HTTP

(def ^:private connect-timeout (Duration/ofSeconds 10))
(def ^:private metadata-request-timeout (Duration/ofSeconds 30))
(def ^:private tarball-request-timeout (Duration/ofMinutes 5))

(defn- http-client ^HttpClient []
  (-> (HttpClient/newBuilder)
      (.followRedirects java.net.http.HttpClient$Redirect/NORMAL)
      (.connectTimeout connect-timeout)
      (.build)))

(defn- get-json [url]
  (let [req (-> (HttpRequest/newBuilder (URI/create url))
                (.timeout metadata-request-timeout)
                (.header "Accept" "application/json")
                (.GET)
                (.build))
        resp (.send (http-client) req (HttpResponse$BodyHandlers/ofString))
        status (.statusCode resp)]
    (when (>= status 400)
      (throw (ex-info (str "Registry request failed: " url " (HTTP " status ")")
                      {:url url :status status})))
    (json/read-str (.body resp) :key-fn keyword)))

(defn- download-stream [url file]
  (let [req (-> (HttpRequest/newBuilder (URI/create url))
                (.timeout tarball-request-timeout)
                (.GET)
                (.build))
        resp (.send (http-client) req
                    (HttpResponse$BodyHandlers/ofFile (.toPath (io/file file))))
        status (.statusCode resp)]
    (when (>= status 400)
      (.delete (io/file file))
      (throw (ex-info (str "Tarball download failed: " url " (HTTP " status ")")
                      {:url url :status status})))
    file))

(defn ->registries [registry]
  (if (string? registry) [registry] (vec registry)))

(defn try-registries
  "Apply `f` to each registry in `registries` until one succeeds, returning
  that result. A 404 falls through to the next registry; any other failure
  propagates immediately so a transient outage is not masked as not-found.
  Throws when every registry returns 404."
  [registries f]
  (loop [[registry & more] (->registries registries)]
    (let [r (try
              {:ok (f registry)}
              (catch clojure.lang.ExceptionInfo e
                (if (and (= 404 (:status (ex-data e))) more)
                  ::miss
                  (throw e))))]
      (if (= ::miss r) (recur more) (:ok r)))))

;; ---------------------------------------------------------------------------
;; Public API

(defn fetch-metadata
  "Raw metadata doc for `id` from one registry, or nil when the registry
  doesn't carry the package (HTTP 404). Other failures propagate."
  [registry id]
  (try
    (get-json (str registry "/" id))
    (catch clojure.lang.ExceptionInfo e
      (when-not (= 404 (:status (ex-data e))) (throw e)))))

(defn merge-docs
  "Union package metadata across registries. `:versions` are merged with
  earlier registries winning on a shared version key; `:dist-tags :latest`
  is recomputed as the highest semver across the union. Other top-level
  fields (`:name`, `:description`, `:time`) take the first registry's value."
  [docs]
  (let [versions (reduce (fn [acc d] (merge (:versions d) acc)) {} docs)
        latest   (->> (keys versions) (map name) (sort canonical/semver-compare) last)]
    (cond-> (assoc (apply merge (reverse docs)) :versions versions)
      latest (assoc-in [:dist-tags :latest] latest))))

(defn metadata
  "Fetch npm-style metadata for `id`, merged across `registries` (a base
  URL or seq of them). Returns a map with `:name`, `:description`,
  `:dist-tags`, `:versions`, `:time`. Throws when no registry carries `id`."
  ([id] (metadata id default-registries))
  ([id registries]
   (let [docs (keep #(fetch-metadata % id) (->registries registries))]
     (when (empty? docs)
       (throw (ex-info (str "Package not found in any registry: " id) {:id id})))
     (merge-docs docs))))

(defn list-versions
  "Return a seq of `{:version :fhir-version}` for the given package id,
  newest first by semver."
  ([id] (list-versions id default-registries))
  ([id registries]
   (->> (:versions (metadata id registries))
        (map (fn [[v entry]]
               {:version      (name v)
                :fhir-version (or (:fhirVersion entry)
                                  (str/join ", " (:fhirVersions entry)))}))
        (sort-by :version (fn [a b] (canonical/semver-compare b a))))))

(defn- tarball-url [id version registry]
  (str registry "/" id "/" version))

(defn download!
  "Download and extract `id`@`version` into `dest-dir/<id>-<version>/`.
  Returns the path to the extracted package directory (the directory
  that contains FHIR JSON resources — typically `<dir>/package`).
  Idempotent: skips download/extract when the targets already exist."
  ([id version dest-dir] (download! id version dest-dir default-registries))
  ([id version dest-dir registries]
   (when (str/blank? version)
     (throw (ex-info "Package version required (use id@version or specify --version)"
                     {:id id})))
   (let [tag (str id "-" version)
         dest (io/file dest-dir tag)
         tgz  (io/file dest-dir (str tag ".tgz"))]
     (.mkdirs (io/file dest-dir))
     (when-not (.exists tgz)
       (let [tmp (io/file dest-dir (str tag ".tgz.part"))]
         (try-registries registries
           (fn [registry]
             (log/info "fetching FHIR package" {:id id :version version :url (tarball-url id version registry)})
             (download-stream (tarball-url id version registry) tmp)))
         (try
           (Files/move (.toPath tmp) (.toPath tgz)
                       (into-array java.nio.file.CopyOption
                                   [StandardCopyOption/ATOMIC_MOVE
                                    StandardCopyOption/REPLACE_EXISTING]))
           (catch java.nio.file.FileSystemException _
             ;; Cross-device or filesystem doesn't support atomic move —
             ;; fall back to a non-atomic replace. Still better than the
             ;; old `.renameTo` which silently dropped the result.
             (Files/move (.toPath tmp) (.toPath tgz)
                         (into-array java.nio.file.CopyOption
                                     [StandardCopyOption/REPLACE_EXISTING]))))))
     (when-not (.exists dest)
       (log/info "extracting FHIR package" {:tgz (.getPath tgz) :into (.getPath dest)})
       (.mkdirs dest)
       (let [{:keys [exit err]} (sh/sh "tar" "xzf" (.getPath tgz) "-C" (.getPath dest))]
         (when (not= 0 exit)
           (throw (ex-info (str "tar extract failed: " err)
                           {:tgz (.getPath tgz) :dest (.getPath dest) :exit exit})))))
     (let [pkg-dir (io/file dest "package")]
       (if (.isDirectory pkg-dir) pkg-dir dest)))))

;; ---------------------------------------------------------------------------
;; Presentation

(defn print-known
  "Print the curated list of known FHIR packages and registry pointers."
  []
  (println "")
  (println "Common FHIR packages (the registry holds many more — see Browse below):")
  (pp/print-table [:id :description] known-packages)
  (println "")
  (println (str "  Registries:      " (str/join ", " default-registries)))
  (println (str "  Browse all:      " browsable-registry)))

(defn print-versions
  "Print a versions table for a package id."
  ([id] (print-versions id default-registries))
  ([id registries]
   (let [m       (metadata id registries)
         latest  (get-in m [:dist-tags :latest])
         rows    (list-versions id registries)]
     (println (str "\n=== Versions of " id " ==="))
     (when latest (println (str "Latest: " latest)))
     (when-let [d (:description m)] (println d))
     (println "")
     (pp/print-table [:version :fhir-version] rows))))
