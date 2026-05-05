(ns com.eldrix.hades.impl.paths
  "Open terminology artefact paths as Hades provider bundles."
  (:require [clojure.spec.alpha :as s]
            [clojure.tools.logging.readable :as log]
            [com.eldrix.hades.impl.composite :as composite]
            [com.eldrix.hades.impl.load :as load-fhir]
            [com.eldrix.hades.impl.loaders.fhir :as fhir-loader]
            [com.eldrix.hades.impl.snomed :as snomed]
            [com.eldrix.hades.impl.sources :as sources]
            [com.eldrix.hades.impl.sqlite.db :as sqlite-db]
            [com.eldrix.hades.impl.sqlite.provider :as sqlite-provider]
            [com.eldrix.hermes.core :as hermes])
  (:import (java.io Closeable File)))

(set! *warn-on-reflection* true)

(def empty-bundle
  {:providers [] :closers [] :naming-systems [] :supplements []})

(defn- summarise-resources
  [resources]
  (let [by-type (group-by :resource-type resources)]
    {:codesystems (count (get by-type "CodeSystem" []))
     :valuesets   (count (get by-type "ValueSet" []))
     :conceptmaps (count (get by-type "ConceptMap" []))}))

(defn- close-bundle!
  [bundle]
  (doseq [closer (reverse (:closers bundle))]
    (try
      (closer)
      (catch Exception e
        (log/warn e "error closing provider while unwinding failed open")))))

(defn merge-bundles
  "Merge provider bundles returned by path opening functions."
  ([] empty-bundle)
  ([a b] (merge-with into a b))
  ([a b & more] (reduce merge-bundles (merge-bundles a b) more)))

(s/fdef open-database
  :args (s/cat :entry ::sources/entry))

(defn open-database
  "Open one built-database entry as a provider bundle. Dispatches on
  `:kind`; uses `:dir` for Hermes (the DB is a directory) and `:file`
  for FTRM (the DB is a file)."
  [{:keys [kind ^File file ^File dir]}]
  (case kind
    :hermes-db
    (let [path       (.getPath dir)
          hermes-svc (hermes/open path)
          releases   (mapv :term (hermes/release-information hermes-svc))]
      (log/info "registered provider"
                {:source path :kind :hermes-db :releases releases})
      {:providers [(snomed/->HermesService hermes-svc)]
       :closers   [#(.close hermes-svc)]})

    :fhir-tx-db
    (let [path (.getPath file)
          {:keys [datasource codesystem valueset conceptmap naming-system]}
          (sqlite-provider/open-providers path)
          providers (filterv some? [codesystem valueset conceptmap])]
      (log/info "registered provider"
                (merge {:source path :kind :fhir-tx-db
                        :naming-system? (some? naming-system)}
                       (summarise-resources (sqlite-db/list-resources datasource))))
      {:providers      providers
       :naming-systems (when naming-system [naming-system])
       :closers        (when (instance? Closeable datasource)
                         [#(.close ^Closeable datasource)])})))

(defn- aggregate-fhir-json
  "Build one in-memory provider set from FHIR JSON files."
  [root files]
  (let [data (fhir-loader/load-files files)
        {:keys [providers supplements totals]}
        (load-fhir/build-from-fhir-data data)]
    (log/info "registered provider"
              (merge {:source root :kind :fhir-json
                      :files (count files)
                      :supplements (count supplements)}
                     (select-keys totals [:codesystems :valuesets :conceptmaps])))
    {:providers   providers
     :supplements supplements}))

(defn bundle-for-path
  "Walk `path` and return the provider bundle for every recognised built
  artefact under it.

  Hermes DB directories and FHIR-tx SQLite containers open as closeable
  providers. FHIR JSON resources are aggregated into one in-memory
  provider set per input path. Release-only sources (RF2, LOINC) are
  rejected — they must be imported before opening."
  [path]
  (let [files     (sources/tx-file-seq path)
        release   (filter #(and (:importable? %) (not (:database? %))) files)
        databases (filter #(#{:hermes-db :fhir-tx-db} (:kind %)) files)
        json      (filter #(= :fhir-json (:kind %)) files)]
    (when (empty? files)
      (throw (ex-info (str "Couldn't find any terminology sources under " path)
                      {:reason :unknown-source-kind :path path})))
    (when (seq release)
      (throw (ex-info (str "Path contains release sources - run import first: " path)
                      {:reason ::release-source-not-served
                       :path path
                       :release-paths (mapv #(.getPath ^File (:file %)) release)})))
    (let [groups (cond-> (mapv open-database databases)
                   (seq json)
                   (conj (aggregate-fhir-json path (mapv :file json))))]
      (reduce merge-bundles empty-bundle groups))))

(defn bundle-for-paths
  "Return a merged provider bundle for all `paths`."
  [paths]
  (reduce merge-bundles empty-bundle (map bundle-for-path paths)))

(defn open-paths
  "Open a Hades service from built terminology artefact paths.

  Options match `com.eldrix.hades.core/open`; any `:supplements`,
  `:naming-systems` or `:closers` supplied by the caller are appended to
  those discovered from the paths."
  ([paths] (open-paths paths {}))
  ([paths opts]
   (let [bundle (bundle-for-paths paths)
         opts'  (-> opts
                    (update :supplements (fnil into []) (:supplements bundle))
                    (update :naming-systems (fnil into []) (:naming-systems bundle))
                    (update :closers (fnil into []) (:closers bundle)))]
     (try
       (composite/from-providers (:providers bundle) opts')
       (catch Throwable t
         (close-bundle! bundle)
         (throw t))))))
