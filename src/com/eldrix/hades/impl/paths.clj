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
  "Build one in-memory provider set from FHIR JSON files."
  [root files]
  (let [data (fhir-loader/load-files files)
        {:keys [providers totals]} (load-fhir/build-from-fhir-data data)]
    (log/info "registered provider"
              (merge {:source root :kind :fhir-json
                      :files (count files)}
                     (select-keys totals [:codesystems :valuesets :conceptmaps :supplements])))
    {:providers providers}))

(defn bundle-for-path
  "Walk `path` and return the provider bundle for every recognised built
  artefact under it.

  Hermes DB directories and FHIR-tx SQLite containers open as closeable
  providers. FHIR JSON resources are aggregated into one in-memory
  provider set per input path. Release-only sources (RF2, LOINC) are
  rejected — they must be imported before opening.

  `opts` is forwarded to `open-database`."
  ([path] (bundle-for-path path {}))
  ([path opts]
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
     (let [groups (cond-> (mapv #(open-database % opts) databases)
                    (seq json)
                    (conj (aggregate-fhir-json path (mapv :file json))))]
       (reduce merge-bundles empty-bundle groups)))))

(defn bundle-for-paths
  "Return a merged provider bundle for all `paths`. `opts` is forwarded
  to `bundle-for-path`."
  ([paths] (bundle-for-paths paths {}))
  ([paths opts]
   (reduce merge-bundles empty-bundle (map #(bundle-for-path % opts) paths))))

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
