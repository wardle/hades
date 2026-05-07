(ns com.eldrix.hades.fhir-packages-live-test
  "Live parity tests for real FHIR packages: load the same set of
  packages into the in-memory provider and the SQLite container and
  assert that both engines enumerate, lookup, validate and expand
  identically.

  Tagged `^:live` — needs `.hades/fhir-packages/` (extracted FHIR JSON)
  and `.hades/fhir-smoke.db` (SQLite container of the same packages).
  Provision once via the install CLI:

    clj -M:run install .hades/fhir-smoke.db \\
      --dist hl7.terminology.r4@7.0.1 \\
      --dist hl7.fhir.us.core@6.1.0 \\
      --dist hl7.fhir.uv.ips@2.0.0 \\
      --cache-dir .hades/fhir-packages"
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.eldrix.hades.core :as hades]
            [com.eldrix.hades.fixtures :as fixtures]
            [com.eldrix.hades.impl.composite :as composite]
            [com.eldrix.hades.impl.load :as load-fhir]
            [com.eldrix.hades.impl.loaders.fhir :as fhir-loader]
            [com.eldrix.hades.impl.protocols :as protos]
            [com.eldrix.hades.impl.sqlite.provider :as sqlite-provider])
  (:import (java.io File)))

(def ^:dynamic *mem-svc* nil)
(def ^:dynamic *sql-svc* nil)

(defn- package-dirs
  "Resolve the canonical `fixtures/fhir-packages` list to the on-disk
  `<id>-<version>/package/` directories produced by `clojure -M:run install
  --cache-dir .hades/fhir-packages`. Driving from the explicit list (rather
  than walking `fhir-packages-dir`) keeps the in-memory side aligned with
  the SQLite smoke DB even when the cache directory has stale extracts
  from earlier installs."
  []
  (mapv (fn [[id version]]
          (let [base (io/file fixtures/fhir-packages-dir (str id "-" version))
                pkg  (io/file base "package")]
            (when-not (.isDirectory base)
              (throw (ex-info (str "missing FHIR package extract: " base)
                              {:id id :version version :path (.getPath base)})))
            (if (.isDirectory pkg) pkg base)))
        fixtures/fhir-packages))

(defn parity-fixture [f]
  (let [data (mapcat #(fhir-loader/load-paths (str %)) (package-dirs))
        {:keys [providers supplements]} (load-fhir/build-from-fhir-data data)
        mem-svc (composite/from-providers providers {:supplements supplements})
        {:keys [datasource codesystem valueset conceptmap]}
        (sqlite-provider/open-providers fixtures/fhir-smoke-db-path)
        sql-svc (composite/from-providers (filterv some? [codesystem valueset conceptmap])
                            {:closers [#(when (instance? java.io.Closeable datasource)
                                          (.close ^java.io.Closeable datasource))]})]
    (binding [*mem-svc* mem-svc *sql-svc* sql-svc]
      (try (f)
          (finally
            (hades/close mem-svc)
            (hades/close sql-svc))))))

(use-fixtures :once parity-fixture)

(defn- url-version-set [metadata]
  (->> metadata (map (juxt :url :version)) set))

(defn- first-code-from-cs
  "Pull one code from a CodeSystem via `cs-find-matches` with no filters,
  bounded by `:max-hits 1`. Returns nil when the CS exposes no concepts
  (e.g. content=not-present)."
  [svc system]
  (when-let [cs (composite/find-codesystem svc system)]
    (-> (protos/cs-find-matches cs {:system system :max-hits 1})
        :concepts first :code)))

(deftest ^:live cs-metadata-parity
  (testing "in-memory and SQLite engines list the same CodeSystems"
    (let [mem-cs (url-version-set (protos/cs-metadata *mem-svc*))
          sql-cs (url-version-set (protos/cs-metadata *sql-svc*))]
      (is (pos? (count mem-cs)) "expected non-empty CodeSystem catalogue")
      (is (= mem-cs sql-cs)
          (str "Engines disagree on CodeSystem catalogue. "
               "in-memory only: " (pr-str (set/difference mem-cs sql-cs))
               "; sqlite only: "  (pr-str (set/difference sql-cs mem-cs)))))))

(deftest ^:live vs-metadata-parity
  (testing "in-memory and SQLite engines list the same ValueSets"
    (let [mem-vs (url-version-set (protos/vs-metadata *mem-svc*))
          sql-vs (url-version-set (protos/vs-metadata *sql-svc*))]
      (is (pos? (count mem-vs)) "expected non-empty ValueSet catalogue")
      (is (= mem-vs sql-vs)
          (str "Engines disagree on ValueSet catalogue. "
               "in-memory only: " (pr-str (set/difference mem-vs sql-vs))
               "; sqlite only: "  (pr-str (set/difference sql-vs mem-vs)))))))

(deftest ^:live cm-metadata-parity
  (testing "in-memory and SQLite engines list the same ConceptMaps"
    (is (= (url-version-set (protos/cm-metadata *mem-svc*))
           (url-version-set (protos/cm-metadata *sql-svc*))))))

(deftest ^:live cs-lookup-parity-sampled
  (testing "$lookup agrees on the first concept of a sample of CodeSystems"
    ;; Sample the first 10 CodeSystems that expose at least one concept.
    ;; Skip empty / not-present CSes — there's nothing to lookup. We rely
    ;; on metadata-parity above to prove the catalogue itself matches;
    ;; here we exercise the read path.
    (let [samples (->> (protos/cs-metadata *mem-svc*)
                       (keep (fn [{:keys [url version]}]
                               (when-let [code (first-code-from-cs *mem-svc* url)]
                                 [url version code])))
                       (take 10))]
      (is (pos? (count samples))
          "expected at least one CodeSystem with concepts to sample")
      (doseq [[system version code] samples]
        (let [params {:system system :version version :code code}
              m (hades/lookup *mem-svc* params)
              s (hades/lookup *sql-svc* params)]
          (is (= (:display m) (:display s))
              (str "display mismatch for " system "|" version " #" code
                   ": mem=" (pr-str (:display m)) " sql=" (pr-str (:display s))))
          (is (= (:code m) (:code s))
              (str "code echo mismatch for " system "|" version " #" code)))))))

(deftest ^:live cs-validate-code-parity-sampled
  (testing "$validate-code agrees on a sample of known-good codes"
    (let [samples (->> (protos/cs-metadata *mem-svc*)
                       (keep (fn [{:keys [url version]}]
                               (when-let [code (first-code-from-cs *mem-svc* url)]
                                 [url version code])))
                       (take 10))]
      (doseq [[system version code] samples]
        (let [params {:system system :version version :code code}
              m (hades/validate-code *mem-svc* params)
              s (hades/validate-code *sql-svc* params)]
          (is (= (boolean (:result m)) (boolean (:result s)))
              (str "result mismatch for " system "|" version " #" code))
          (is (= (:display m) (:display s))
              (str "display mismatch for " system "|" version " #" code)))))))
