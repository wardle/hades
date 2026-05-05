(ns com.eldrix.hades.impl.paths
  "Open terminology artefact paths as Hades provider bundles."
  (:require [clojure.tools.logging.readable :as log]
            [com.eldrix.hades.impl.composite :as composite]
            [com.eldrix.hades.impl.load :as load-fhir]
            [com.eldrix.hades.impl.loaders.fhir :as fhir-loader]
            [com.eldrix.hades.impl.snomed :as snomed]
            [com.eldrix.hades.impl.sources :as sources]
            [com.eldrix.hades.impl.sqlite.db :as sqlite-db]
            [com.eldrix.hades.impl.sqlite.provider :as sqlite-provider]
            [com.eldrix.hermes.core :as hermes]))

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

(defn bundle-for-finding
  "Open one built artefact finding as a provider bundle.

  FHIR JSON and release-source findings are handled by `bundle-for-path`,
  not here."
  [{:keys [kind path]}]
  (case kind
    :hermes-db
    (let [hermes-svc (hermes/open path)
          releases   (mapv :term (hermes/release-information hermes-svc))]
      (log/info "registered provider"
                {:source path :kind :hermes-db :releases releases})
      {:providers [(snomed/->HermesService hermes-svc)]
       :closers   [#(.close hermes-svc)]})

    :fhir-tx-db
    (let [{:keys [datasource codesystem valueset conceptmap naming-system]}
          (sqlite-provider/open-providers path)
          providers (filterv some? [codesystem valueset conceptmap])]
      (log/info "registered provider"
                (merge {:source path :kind :fhir-tx-db
                        :naming-system? (some? naming-system)}
                       (summarise-resources (sqlite-db/list-resources datasource))))
      {:providers      providers
       :naming-systems (when naming-system [naming-system])
       :closers        (when (instance? java.io.Closeable datasource)
                         [#(.close ^java.io.Closeable datasource)])})))

(defn- aggregate-fhir-json
  "Build one in-memory provider set from FHIR JSON files under one input path."
  [root paths]
  (let [data (mapcat fhir-loader/load-paths paths)
        {:keys [providers supplements totals]}
        (load-fhir/build-from-fhir-data data)]
    (log/info "registered provider"
              (merge {:source root :kind :fhir-json
                      :files (count paths)
                      :supplements (count supplements)}
                     (select-keys totals [:codesystems :valuesets :conceptmaps])))
    {:providers   providers
     :supplements supplements}))

(defn bundle-for-path
  "Walk `path` and return the provider bundle for every recognised built
  artefact under it.

  Hermes DB directories and FHIR terminology SQLite containers open as
  closeable providers. FHIR JSON resources are aggregated into one
  in-memory provider set per input path. Release-source findings (RF2 and
  LOINC CSVs) are rejected because they must be imported before opening."
  [path]
  (let [findings (sources/find-sources! path)
        by-kind  (group-by :kind findings)
        release  (concat (by-kind :rf2) (by-kind :loinc))]
    (when (seq release)
      (throw (ex-info (str "Path contains release sources - run import first: " path)
                      {:reason ::release-source-not-served
                       :path path
                       :release-paths (mapv :path release)})))
    (let [boundary  (concat (by-kind :hermes-db) (by-kind :fhir-tx-db))
          fhir-json (by-kind :fhir-json)
          groups    (cond-> (mapv bundle-for-finding boundary)
                      (seq fhir-json)
                      (conj (aggregate-fhir-json path (mapv :path fhir-json))))]
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
