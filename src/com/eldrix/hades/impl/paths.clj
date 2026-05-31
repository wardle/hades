(ns com.eldrix.hades.impl.paths
  "Open terminology artefact paths as Hades provider bundles."
  (:require [clojure.spec.alpha :as s]
            [clojure.tools.logging.readable :as log]
            [com.eldrix.hades.composite :as composite]
            [com.eldrix.hades.impl.archive :as archive]
            [com.eldrix.hades.impl.load :as load-fhir]
            [com.eldrix.hades.providers.common.fhir-loader :as fhir-loader]
            [com.eldrix.hades.providers.snomed.provider :as snomed]
            [com.eldrix.hades.impl.sources :as sources]
            [com.eldrix.hades.providers.ftrm.provider :as ftrm-provider]
            [com.eldrix.hades.providers.loinc.provider :as loinc-provider]
            [com.eldrix.hermes.core :as hermes])
  (:import (java.io Closeable File)))

(set! *warn-on-reflection* true)

(def empty-bundle
  {:providers []})

(defn- close-bundle!
  [bundle]
  (doseq [p (reverse (:providers bundle))
          :when (instance? Closeable p)]
    (try
      (.close ^Closeable p)
      (catch Exception e
        (log/warn e "error closing provider while unwinding failed open")))))

(defn merge-bundles
  "Merge provider bundles returned by path opening functions."
  ([] empty-bundle)
  ([a b] (merge-with into a b))
  ([a b & more] (reduce merge-bundles (merge-bundles a b) more)))

(s/fdef open-database
  :args (s/cat :entry ::sources/entry :opts (s/? map?)))

(defn open-database
  "Open one built-database entry as a provider bundle. Dispatches on
  `:kind`; uses `:dir` for Hermes (the DB is a directory) and `:file`
  for FTRM / native LOINC (the DB is a file).

  `opts` may carry `:default-locale` (a BCP 47 / Accept-Language string)
  forwarded to `hermes/open` for Hermes entries."
  ([entry] (open-database entry {}))
  ([{:keys [kind ^File file ^File dir]} {:keys [default-locale]}]
   (case kind
     :hermes-db
     (let [path       (.getPath dir)
           hermes-svc (hermes/open path (cond-> {} default-locale (assoc :default-locale default-locale)))
           releases   (mapv :term (hermes/release-information hermes-svc))]
       (log/info "registered provider"
                 {:source path :kind :hermes-db :releases releases})
       {:providers [(snomed/->HermesService hermes-svc)]})

     :fhir-tx-db
     (let [path     (.getPath file)
           provider (ftrm-provider/open path)]
       {:providers [provider]})

     :loinc-db
     (let [path     (.getPath file)
           provider (loinc-provider/open path)]
       (log/info "registered provider" {:source path :kind :loinc-db})
       {:providers [provider]}))))

(defn- aggregate-fhir-json
  "Build one in-memory provider set from the FHIR JSON files gathered
  across every opened path. Aggregating all loose resources into a single
  in-memory container — rather than one container per path — mirrors the
  FTRM container model: the indexer sees every version and every
  `CodeSystem.identifier` of a canonical URL at once. OID/URN aliases
  therefore resolve url-scoped (an alias declared on one version routes to
  the canonical URL; normal version resolution then picks the version),
  matching FTRM."
  [roots files]
  (let [data (fhir-loader/load-files files)
        {:keys [providers totals]} (load-fhir/build-from-fhir-data data)]
    (log/info "registered provider"
              (merge {:source roots :kind :fhir-json
                      :files (count files)}
                     (select-keys totals [:codesystems :valuesets :conceptmaps :supplements])))
    {:providers providers}))

(defn- classify-path
  "Walk `path`, validate it, and split its recognised sources into
  self-contained `:databases` (Hermes / FTRM / native LOINC, as
  `::sources/entry` maps) and loose `:json-files` (FHIR JSON `File`s).
  Throws when the path yields no sources, or contains release-only
  sources (RF2, LOINC CSV) that must be imported before opening."
  [path]
  (let [files     (sources/tx-file-seq path)
        release   (filter #(and (:importable? %) (not (:database? %))) files)
        databases (filter #(#{:hermes-db :fhir-tx-db :loinc-db} (:kind %)) files)
        json      (filter #(= :fhir-json (:kind %)) files)]
    (when (empty? files)
      (throw (ex-info (str "Couldn't find any terminology sources under " path)
                      {:reason :unknown-source-kind :path path})))
    (when (seq release)
      (throw (ex-info (str "Path contains release sources - run import first: " path)
                      {:reason ::release-source-not-served
                       :path path
                       :release-paths (mapv #(.getPath ^File (:file %)) release)})))
    {:databases databases :json-files (mapv :file json)}))

(defn bundle-for-paths
  "Open `paths` into one merged provider bundle. Each path's self-contained
  databases (Hermes / FTRM / native LOINC) open individually, but loose
  FHIR JSON from every path is aggregated into a single in-memory provider
  set (see `aggregate-fhir-json`) so cross-package concerns — version
  aggregation, OID/URN alias resolution — resolve over the whole corpus.
  `opts` is forwarded to `open-database`."
  ([paths] (bundle-for-paths paths {}))
  ([paths opts]
   (let [classified  (mapv classify-path paths)
         databases   (mapcat :databases classified)
         json-files  (into [] (mapcat :json-files) classified)
         db-bundles  (mapv #(open-database % opts) databases)
         json-bundle (when (seq json-files)
                       (aggregate-fhir-json (vec paths) json-files))]
     (reduce merge-bundles empty-bundle
             (cond-> db-bundles json-bundle (conj json-bundle))))))

(defn bundle-for-path
  "Open a single `path` into a provider bundle — convenience over
  `bundle-for-paths`."
  ([path] (bundle-for-path path {}))
  ([path opts] (bundle-for-paths [path] opts)))

(defn open-paths
  "Open a Hades service from built terminology artefact paths.

  Any `paths` that are archive files (`.tgz`/`.tar.gz`/`.tar`/`.zip`) are
  extracted to temporary directories, opened, and the directories deleted
  before returning. FHIR JSON is read into memory while opening, so the
  extracted files aren't needed afterwards — archived sources must be
  release/JSON sources, not databases opened in place.

  Options match `com.eldrix.hades.core/open`. `:default-locale` is
  forwarded to `hermes/open` for SNOMED entries. Supplements among the
  opened providers are detected and wired by `from-providers`."
  ([paths] (open-paths paths {}))
  ([paths opts]
   (let [{:keys [paths temp-dirs]} (archive/resolve-sources paths)]
     (try
       (let [bundle (bundle-for-paths paths (select-keys opts [:default-locale]))]
         (try
           (composite/from-providers (:providers bundle) opts)
           (catch Throwable t
             (close-bundle! bundle)
             (throw t))))
       (finally
         (run! archive/delete-recursively temp-dirs))))))
