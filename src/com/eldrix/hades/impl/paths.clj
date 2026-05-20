(ns com.eldrix.hades.impl.paths
  "Open terminology artefact paths as Hades provider bundles."
  (:require [clojure.spec.alpha :as s]
            [clojure.tools.logging.readable :as log]
            [com.eldrix.hades.composite :as composite]
            [com.eldrix.hades.impl.load :as load-fhir]
            [com.eldrix.hades.providers.common.fhir-loader :as fhir-loader]
            [com.eldrix.hades.providers.snomed.provider :as snomed]
            [com.eldrix.hades.impl.sources :as sources]
            [com.eldrix.hades.providers.ftrm.db :as ftrm-db]
            [com.eldrix.hades.providers.ftrm.provider :as ftrm-provider]
            [com.eldrix.hades.providers.loinc.provider :as loinc-provider]
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
       {:providers [(snomed/->HermesService hermes-svc)]
        :closers   [#(.close hermes-svc)]})

     :fhir-tx-db
     (let [path (.getPath file)
           {:keys [datasource codesystem valueset conceptmap naming-system]}
           (ftrm-provider/open-providers path)
           providers (filterv some? [codesystem valueset conceptmap])]
       (log/info "registered provider"
                 (merge {:source path :kind :fhir-tx-db
                         :naming-system? (some? naming-system)}
                        (summarise-resources (ftrm-db/list-resources datasource))))
       {:providers      providers
        :naming-systems (when naming-system [naming-system])
        :closers        (when (instance? Closeable datasource)
                          [#(.close ^Closeable datasource)])})

     :loinc-db
     (let [path (.getPath file)
           {:keys [datasource codesystem valueset conceptmap naming-system]} (loinc-provider/open-providers path)]
       (log/info "registered provider" {:source path :kind :loinc-db})
       {:providers (filterv some? [codesystem valueset conceptmap])
        :naming-systems (when naming-system [naming-system])
        :closers   (when (instance? Closeable datasource)
                     [#(.close ^Closeable datasource)])}))))

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

  Options match `com.eldrix.hades.core/open`; any `:supplements`,
  `:naming-systems` or `:closers` supplied by the caller are appended to
  those discovered from the paths. `:default-locale` is forwarded to
  `hermes/open` for SNOMED entries."
  ([paths] (open-paths paths {}))
  ([paths opts]
   (let [bundle (bundle-for-paths paths (select-keys opts [:default-locale]))
         opts'  (-> opts
                    (update :supplements (fnil into []) (:supplements bundle))
                    (update :naming-systems (fnil into []) (:naming-systems bundle))
                    (update :closers (fnil into []) (:closers bundle)))]
     (try
       (composite/from-providers (:providers bundle) opts')
       (catch Throwable t
         (close-bundle! bundle)
         (throw t))))))
