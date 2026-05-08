(ns com.eldrix.hades.cmd-test
  "Command-entry-point tests: arity rules and the `providers-for-path`
  orchestration that drives `serve` and `status`."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [com.eldrix.hades.cmd :as cmd]
            [com.eldrix.hades.impl.cli :as cli]
            [com.eldrix.hades.impl.paths :as paths]
            [com.eldrix.hades.impl.sources :as sources]
            [com.eldrix.hades.impl.sqlite.db :as db])
  (:import (clojure.lang ExceptionInfo)
           (java.io File)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- reason-of [f]
  (try (f) nil
       (catch ExceptionInfo e (:reason (ex-data e)))))

(defn- validate [cmd args opts]
  (cli/validate-invocation (cli/commands cmd) args opts))

;; ---------------------------------------------------------------------------
;; Arity tests — declarative `:args` / `:requires-opt` spec on each command,
;; enforced in cmd's `invoke-command` via `cli/validate-invocation`.
;; ---------------------------------------------------------------------------

(deftest import-arity
  (testing "import with 0 positionals fails arity check"
    (is (some? (validate "import" [] {}))))
  (testing "import with 1 positional fails arity check"
    (is (some? (validate "import" ["snomed.db"] {}))))
  (testing "import with 2 positionals satisfies arity"
    (is (nil? (validate "import" ["snomed.db" "/some/source"] {})))))

(deftest install-arity
  (testing "install with no --dist fails because :dist is required"
    (is (some? (validate "install" ["snomed.db"] {:dist []}))))
  (testing "install with --dist but no positional fails arity"
    (is (some? (validate "install" [] {:dist [{:id "hl7.fhir.r4.core" :version "4.0.1"}]}))))
  (testing "install with --dist and >1 positional fails arity"
    (is (some? (validate "install" ["a.db" "b.db"]
                         {:dist [{:id "hl7.fhir.r4.core" :version "4.0.1"}]}))))
  (testing "install with --dist and exactly one positional satisfies the spec"
    (is (nil? (validate "install" ["snomed.db"]
                        {:dist [{:id "hl7.fhir.r4.core" :version "4.0.1"}]})))))

;; ---------------------------------------------------------------------------
;; providers-for-path orchestration
;;
;; These tests exercise the serve/status path: a directory is walked,
;; findings are partitioned, release-source findings are rejected,
;; FHIR JSON is aggregated, hermes-db / fhir-tx-db open as artefacts.
;; ---------------------------------------------------------------------------

(defn- mk-tmp-dir ^File [prefix]
  (.toFile (Files/createTempDirectory prefix (into-array FileAttribute []))))

(defn- delete-tree! [^File f]
  (when (.isDirectory f)
    (run! delete-tree! (or (.listFiles f) [])))
  (.delete f))

(defn- spit-file! ^File [^File f content]
  (.mkdirs (.getParentFile f))
  (spit f content)
  f)

(defn- close-bundle!
  "Run every closer in `bundle`. Tests that open real artefacts must
  call this so we don't leak file handles or LMDB envs."
  [bundle]
  (run! #(try (%) (catch Exception _)) (:closers bundle)))

(defn- mk-empty-ftrm! ^File [^File f]
  (let [ds (db/create! (.getPath f))]
    (db/close! ds)
    f))

(defn- canonical-rf2-name [component]
  (str "sct2_" component "_Snapshot_INT_20250201.txt"))

(deftest providers-for-path-on-random-empty-dir-throws
  (testing "a directory with nothing recognisable raises :unknown-source-kind"
    (let [root (mk-tmp-dir "empty-provider-test")]
      (try
        (spit-file! (io/file root "readme.txt") "nothing to see")
        (is (= :unknown-source-kind
               (reason-of #(paths/bundle-for-path (.getPath root)))))
        (finally (delete-tree! root))))))

(deftest providers-for-path-on-stray-rf2-rejects-cleanly
  (testing "a release source under serve raises ::release-source-not-served"
    (let [root (mk-tmp-dir "rf2-only")]
      (try
        (spit-file! (io/file root "Snapshot" "Terminology"
                             (canonical-rf2-name "Concept"))
                    "id\n")
        (is (= :com.eldrix.hades.impl.paths/release-source-not-served
               (reason-of #(paths/bundle-for-path (.getPath root)))))
        (finally (delete-tree! root))))))

(deftest providers-for-path-on-mixed-rf2-and-fhir-json-rejects
  (testing "release-source rejection wins even when FHIR JSON is present"
    (let [root (mk-tmp-dir "mixed-rf2+fhir")]
      (try
        (spit-file! (io/file root "rf2" (canonical-rf2-name "Concept")) "id\n")
        (spit-file! (io/file root "bundles" "cs.json")
                    "{\"resourceType\":\"CodeSystem\",\"url\":\"http://example.com/x\"}\n")
        (is (= :com.eldrix.hades.impl.paths/release-source-not-served
               (reason-of #(paths/bundle-for-path (.getPath root)))))
        (finally (delete-tree! root))))))

(deftest providers-for-path-on-fhir-json-aggregates
  (testing "FHIR JSON-only tree builds an in-memory provider set"
    (let [root (mk-tmp-dir "fhir-json-only")]
      (try
        (spit-file! (io/file root "cs1.json")
                    "{\"resourceType\":\"CodeSystem\",\"url\":\"http://example.com/cs1\",\"content\":\"complete\"}")
        (spit-file! (io/file root "vs1.json")
                    "{\"resourceType\":\"ValueSet\",\"url\":\"http://example.com/vs1\"}")
        (let [bundle (paths/bundle-for-path (.getPath root))]
          (is (pos? (count (:providers bundle)))
              "at least one provider registered from the JSON")
          (is (every? some? (:providers bundle))))
        (finally (delete-tree! root))))))

(deftest providers-for-path-on-fhir-json-aggregates-across-subdirs
  (testing "JSON files in nested subdirs are merged into one provider set"
    (let [root (mk-tmp-dir "fhir-json-nested")]
      (try
        (spit-file! (io/file root "a" "cs.json")
                    "{\"resourceType\":\"CodeSystem\",\"url\":\"http://example.com/a\",\"content\":\"complete\"}")
        (spit-file! (io/file root "b" "deep" "vs.json")
                    "{\"resourceType\":\"ValueSet\",\"url\":\"http://example.com/b\"}")
        (let [bundle (paths/bundle-for-path (.getPath root))]
          (is (pos? (count (:providers bundle)))))
        (finally (delete-tree! root))))))

(deftest providers-for-path-opens-empty-ftrm
  (testing "an FTRM container with no resources still returns a closer"
    (let [root (mk-tmp-dir "ftrm-only")
          ftrm (mk-empty-ftrm! (io/file root "tx.db"))]
      (try
        (let [bundle (paths/bundle-for-path (.getPath ftrm))]
          ;; Empty FTRM has no providers (no CodeSystems/ValueSets/CMs)
          ;; but it still opens, and the datasource closer must come back.
          (is (= [] (:providers bundle)))
          (is (= 1 (count (:closers bundle))))
          (close-bundle! bundle))
        (finally (delete-tree! root))))))

(deftest providers-for-path-opens-two-ftrms-side-by-side
  (testing "two FTRM files in one tree both register, both close"
    (let [root (mk-tmp-dir "two-ftrm")]
      (try
        (mk-empty-ftrm! (io/file root "a.db"))
        (mk-empty-ftrm! (io/file root "b.db"))
        (let [bundle (paths/bundle-for-path (.getPath root))]
          (is (= 2 (count (:closers bundle)))
              "one closer per FTRM artefact")
          (close-bundle! bundle))
        (finally (delete-tree! root))))))

(deftest providers-for-path-mixes-ftrm-with-stray-fhir-json
  (testing "an FTRM next to a CodeSystem.json: both register, JSON aggregated"
    (let [root (mk-tmp-dir "ftrm+json")]
      (try
        (mk-empty-ftrm! (io/file root "tx.db"))
        (spit-file! (io/file root "extra" "cs.json")
                    "{\"resourceType\":\"CodeSystem\",\"url\":\"http://example.com/extra\",\"content\":\"complete\"}")
        (let [bundle (paths/bundle-for-path (.getPath root))]
          ;; FTRM is empty (no providers from it); the JSON contributes one.
          (is (pos? (count (:providers bundle))))
          (is (= 1 (count (:closers bundle)))
              "FTRM datasource closer; JSON providers are pure-memory")
          (close-bundle! bundle))
        (finally (delete-tree! root))))))

(deftest providers-for-path-recognises-hermes-db-via-manifest
  (testing "a hermes-db dir inside the path is recognised by its manifest;
             open fails at hermes/open with a non-orchestration error"
    (let [root (mk-tmp-dir "fake-hermes")]
      (try
        ;; Build a directory that satisfies the manifest-driven
        ;; recogniser: real-looking manifest.edn + the three linked
        ;; artefacts. The contents are stubs — `hermes/open` will fail
        ;; on the empty LMDB/Lucene dirs, which is exactly the error
        ;; we want to surface (provider-level, not walker-level).
        (let [hermes-dir (io/file root "snomed.db")]
          (spit-file! (io/file hermes-dir "manifest.edn")
                      (pr-str {:version "lmdb/18"
                               :store   "store.db"
                               :search  "search.db"
                               :members "members.db"}))
          (.mkdirs (io/file hermes-dir "store.db"))
          (.mkdirs (io/file hermes-dir "search.db"))
          (.mkdirs (io/file hermes-dir "members.db")))
        ;; The walker recognised the hermes-db; `bundle-for-path` then
        ;; calls `hermes/open` which throws on the stub LMDB store.
        ;; Crucially the failure is NOT one of the walker's
        ;; orchestration error reasons.
        (let [thrown (try (paths/bundle-for-path (.getPath root))
                          nil
                          (catch Throwable t t))
              reason (when (instance? ExceptionInfo thrown)
                       (:reason (ex-data thrown)))]
          (is (some? thrown) "fake hermes-db must throw at open time")
          (is (not= :unknown-source-kind reason)
              "walker found the hermes-db, did not return empty")
          (is (not= :com.eldrix.hades.impl.paths/release-source-not-served reason)
              "the hermes-db wasn't classified as a release source"))
        (finally (delete-tree! root))))))

;; ---------------------------------------------------------------------------
;; import → in-process detection round-trip
;;
;; Exercises the `hades import <dest> <src>` happy path end-to-end: import an
;; RF2 skeleton, then assert the just-written hermes-db is detectable by
;; `tx-file-seq` in the SAME JVM. This is what the cmd's `import-from`
;; relies on internally to run its post-import auto-index step.
;;
;; A regression here surfaces in tx-benchmark's hades server build as
;; `ERROR: Couldn't find any terminology sources under <dest>` — visible as
;; a build failure with the SNOMED tree in place but no search.db / members.db.
;; ---------------------------------------------------------------------------

(def ^:private rf2-skeleton-path
  "test/resources/sources/rf2-skeleton")

(deftest import-then-detect-roundtrip
  (testing "after import, the just-written hermes-db is detectable in-process"
    (let [root (mk-tmp-dir "hermes-roundtrip")
          dest (.getPath (io/file root "snomed.db"))]
      (try
        (#'cmd/import-from {} [dest rf2-skeleton-path])
        (let [files   (sources/tx-file-seq dest)
              hermes  (filter #(= :hermes-db (:kind %)) files)]
          (is (= 1 (count hermes))
              "post-import, tx-file-seq must find exactly one hermes-db")
          (let [{:keys [^File dir version]} (first hermes)]
            (is (= dest (.getPath dir)))
            (is (string? version))))
        (finally (delete-tree! root))))))

(deftest import-without-auto-index-leaves-importable-db
  (testing "with --no-index, import skips the post-step but writes a manifest"
    (let [root (mk-tmp-dir "hermes-no-index")
          dest (.getPath (io/file root "snomed.db"))]
      (try
        (#'cmd/import-from {:no-index true} [dest rf2-skeleton-path])
        (is (.isFile (io/file dest "manifest.edn"))
            "import (even without auto-index) must persist a manifest")
        (let [hermes (filter #(= :hermes-db (:kind %)) (sources/tx-file-seq dest))]
          (is (= 1 (count hermes))
              "the partial DB must still register as a hermes-db so a separate
               `index` invocation can complete it"))
        (finally (delete-tree! root))))))
