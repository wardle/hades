(ns com.eldrix.hades.impl.sources-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [com.eldrix.hades.fixtures :as fixtures]
            [com.eldrix.hades.impl.sources :as sources]
            [com.eldrix.hades.impl.sqlite.db :as db]
            [next.jdbc])
  (:import (java.io File)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

;; ---------------------------------------------------------------------------
;; Fixture roots — committed under test/resources/sources/.
;; ---------------------------------------------------------------------------

(def fixture-root "test/resources/sources")

(defn- entries-by-kind [files kind]
  (filter #(= kind (:kind %)) files))

(defn- file-paths [files kind]
  (->> (entries-by-kind files kind)
       (map #(.getPath ^File (:file %)))
       sort
       vec))

(defn- dir-paths [files kind]
  (->> (entries-by-kind files kind)
       (map #(.getPath ^File (:dir %)))
       distinct
       sort
       vec))

(defn- fixture [& parts]
  (.getPath ^File (apply io/file fixture-root parts)))

;; ---------------------------------------------------------------------------
;; Entry shape
;; ---------------------------------------------------------------------------

(deftest entry-carries-file-kind-and-role-flags
  (testing "every entry has :file (a File), :kind, :importable?, :database?"
    (let [files (sources/tx-file-seq (fixture "rf2-flat"))
          e     (first files)]
      (is (= 1 (count files)))
      (is (instance? File (:file e)))
      (is (= :rf2 (:kind e)))
      (is (true? (:importable? e)))
      (is (false? (:database? e)))
      (testing "rf2 entries carry :dir (the parent directory)"
        (is (instance? File (:dir e)))
        (is (= (.getCanonicalPath (io/file fixture-root "rf2-flat"))
               (.getCanonicalPath ^File (:dir e))))))))

;; ---------------------------------------------------------------------------
;; Existing fixture-based detection.
;; ---------------------------------------------------------------------------

(deftest detects-loinc-fixture
  (testing "loinc-fixture has a LoincTableCore.csv marker"
    (let [files (sources/tx-file-seq "test/resources/loinc-fixture")
          loinc (entries-by-kind files :loinc)]
      (is (= 1 (count loinc)))
      (is (str/ends-with? (.getPath ^File (:file (first loinc))) "LoincTableCore.csv"))
      (testing ":dir resolves to the release root"
        (is (= ["test/resources/loinc-fixture"]
               (dir-paths files :loinc)))))))

(deftest detects-fhir-json-resources-dir
  (testing "fhir-resources/good has CodeSystem JSON files"
    (let [files (sources/tx-file-seq "test/resources/fhir-resources/good")]
      (is (every? #(= :fhir-json (:kind %)) files))
      (is (every? #(str/ends-with? (.getPath ^File (:file %)) ".json") files))
      (testing "fhir-json is both importable and openable"
        (is (every? :importable? files))
        (is (every? :database? files)))
      (testing "walks into nested/ to find nested-cs.json"
        (is (some #(str/ends-with? (.getPath ^File (:file %)) "nested-cs.json")
                  files))))))

(deftest detects-fhir-tx-sqlite-container
  ;; FTRM containers are dynamically built (real SQLite file with the
  ;; FTRM application_id stamp via db/create!). Building is preferable
  ;; to committing a binary fixture so the test stays current with
  ;; whatever schema-version create! stamps today.
  (let [^File f (File/createTempFile "fhir-tx-detect" ".db")]
    (.delete f)
    (try
      (let [ds (db/create! (.getPath f))]
        (db/close! ds))
      (let [files (sources/tx-file-seq (.getPath f))
            e     (first files)]
        (is (= 1 (count files)))
        (is (= :fhir-tx-db (:kind e)))
        (is (false? (:importable? e)))
        (is (true? (:database? e)))
        (is (= (.getPath f) (.getPath ^File (:file e)))))
      (finally (.delete f)))))

(deftest does-not-mistake-plain-sqlite-for-fhir-tx
  (let [^File f (File/createTempFile "plain-sqlite" ".db")]
    (.delete f)
    (try
      (let [ds (next.jdbc/get-datasource {:dbtype "sqlite" :dbname (.getPath f)})]
        (with-open [conn (next.jdbc/get-connection ds)]
          (next.jdbc/execute! conn ["CREATE TABLE noise (x INTEGER)"])))
      (is (= [] (sources/tx-file-seq (.getPath f))))
      (finally (.delete f)))))

(deftest unknown-path-returns-empty
  (let [tmp (doto (File/createTempFile "empty" "")
              (.delete))
        d   (File. (.getParentFile tmp) "definitely-not-a-release")]
    (.mkdir d)
    (try
      (is (= [] (sources/tx-file-seq (.getPath d))))
      (finally (.delete d)))))

(deftest tx-file-seq-on-nonexistent-path
  (let [missing (str (System/getProperty "java.io.tmpdir") "/no-such-thing-" (rand-int 1000000))]
    (is (= [] (sources/tx-file-seq missing)))))

;; ---------------------------------------------------------------------------
;; File-level detection: sct2_*.txt anywhere is RF2; LoincTableCore.csv
;; anywhere is LOINC. Layout-agnostic — see the committed fixtures under
;; test/resources/sources/ for the exact tree shapes covered.
;; ---------------------------------------------------------------------------

(deftest single-rf2-file-anywhere-is-detected
  (testing "a flat directory with one RF2 file — no Snapshot/Terminology nesting"
    (let [files (sources/tx-file-seq (fixture "rf2-flat"))]
      (is (= 1 (count (entries-by-kind files :rf2))))
      (is (= [(.getPath (io/file fixture-root "rf2-flat"))]
             (dir-paths files :rf2))))))

(deftest rf2-files-at-arbitrary-depth-are-detected
  (testing "a single RF2 file buried 6 levels deep is still found"
    (let [files (sources/tx-file-seq (fixture "rf2-deep"))]
      (is (= 1 (count (entries-by-kind files :rf2))))
      (is (str/ends-with? (first (dir-paths files :rf2)) "Terminology")))))

(deftest finds-sibling-rf2-trees-under-one-parent
  (testing "TRUD-style bundle: several release subtrees side by side"
    (let [files (sources/tx-file-seq (fixture "rf2-trud-bundle"))]
      ;; 4 component files: two in uk-clin/Snapshot/Terminology,
      ;; one each in uk-drug and the flat uk-edition.
      (is (= 4 (count (entries-by-kind files :rf2))))
      ;; :dir on each entry points at the immediate parent. Three
      ;; subtrees → three unique parent directories.
      (is (= 3 (count (dir-paths files :rf2)))))))

(deftest loinc-detected-in-flat-and-nested-layouts
  (testing "nested release: :dir is the grandparent of LoincTableCore.csv"
    (is (= ["test/resources/loinc-fixture"]
           (dir-paths (sources/tx-file-seq "test/resources/loinc-fixture") :loinc))))
  (testing "flat release: :dir is the file's parent"
    (is (= [(fixture "loinc-flat")]
           (dir-paths (sources/tx-file-seq (fixture "loinc-flat")) :loinc)))))

(deftest finds-mixed-loinc-and-fhir-json-in-one-tree
  (testing "LOINC release alongside a fhir-bundles dir"
    (let [files (sources/tx-file-seq (fixture "mixed-loinc-fhir"))]
      (is (= [(fixture "mixed-loinc-fhir" "loinc-2.82")]
             (dir-paths files :loinc)))
      (is (= [(fixture "mixed-loinc-fhir" "fhir-bundles" "cs.json")
              (fixture "mixed-loinc-fhir" "fhir-bundles" "vs.json")]
             (file-paths files :fhir-json))))))

(deftest mixed-rf2-and-fhir-json-sibling-in-tree
  (testing "an RF2 release next to a fhir-bundles dir"
    (let [files (sources/tx-file-seq (fixture "mixed-rf2-fhir"))]
      (is (= 1 (count (entries-by-kind files :rf2))))
      (is (= [(fixture "mixed-rf2-fhir" "bundles" "cs.json")]
             (file-paths files :fhir-json))))))

;; ---------------------------------------------------------------------------
;; Hermes-DB recognition is manifest-driven. The fixtures here are
;; built on demand because (a) git doesn't preserve empty linked
;; directories, and (b) the "good" manifest mirrors Hermes' own
;; `expected-manifest`, so the test stays current with whatever Hermes
;; ships rather than pinning a specific lmdb/N.
;; ---------------------------------------------------------------------------

(defn- mk-tmp-dir ^File [prefix]
  (.toFile (Files/createTempDirectory prefix (into-array FileAttribute []))))

(defn- delete-tree! [^File f]
  (when (.isDirectory f)
    (run! delete-tree! (or (.listFiles f) [])))
  (.delete f))

(defn- mk-hermes-dir!
  "Create a directory shaped like a real Hermes built database. Mirrors
  Hermes' `expected-manifest` so version drift is caught."
  ^File [^File root db-name]
  (let [d        (io/file root db-name)
        expected @#'com.eldrix.hermes.core/expected-manifest]
    (.mkdirs d)
    (spit (io/file d "manifest.edn") (pr-str expected))
    (doseq [linked (vals (dissoc expected :version))]
      (.mkdirs (io/file d linked)))
    d))

(deftest hermes-db-recognised-by-real-manifest
  (testing "a directory mirroring expected-manifest + linked artefacts is recognised"
    (let [root (mk-tmp-dir "hermes-manifest")]
      (try
        (let [hermes   (mk-hermes-dir! root "snomed.db")
              files    (sources/tx-file-seq (.getPath root))
              entries  (entries-by-kind files :hermes-db)
              expected @#'com.eldrix.hermes.core/expected-manifest]
          (is (= 1 (count entries)))
          (let [e (first entries)]
            (is (= "manifest.edn" (.getName ^File (:file e))))
            (is (= (.getPath hermes) (.getPath ^File (:dir e))))
            (is (= (:version expected) (:version e)))
            (is (false? (:importable? e)))
            (is (true? (:database? e)))))
        (finally (delete-tree! root))))))

(deftest empty-manifest-is-not-recognised
  (let [root (mk-tmp-dir "bogus-manifest")]
    (try
      (let [d (io/file root "fake.db")]
        (.mkdirs d)
        (spit (io/file d "manifest.edn") "{}"))
      (is (empty? (entries-by-kind (sources/tx-file-seq (.getPath root)) :hermes-db)))
      (finally (delete-tree! root)))))

(deftest manifest-with-missing-linked-files-is-not-recognised
  (let [root (mk-tmp-dir "missing-linked")]
    (try
      ;; Real-shaped manifest, but none of the linked dirs exist.
      (let [d (io/file root "incomplete.db")]
        (.mkdirs d)
        (spit (io/file d "manifest.edn")
              (pr-str @#'com.eldrix.hermes.core/expected-manifest)))
      (is (empty? (entries-by-kind (sources/tx-file-seq (.getPath root)) :hermes-db)))
      (finally (delete-tree! root)))))

;; ---------------------------------------------------------------------------
;; Walker policy.
;; ---------------------------------------------------------------------------

(deftest walker-skips-dot-and-build-dirs
  ;; Built on demand: `.git` cannot be committed (git refuses), so the
  ;; whole skip-dirs case stays inline.
  (let [root (mk-tmp-dir "skip-dirs")]
    (doseq [[d name rt] [[".git"          "config.json"       "CodeSystem"]
                         ["node_modules"  "package.json"      "ValueSet"]
                         ["target"        "classes-meta.json" "CodeSystem"]
                         ["real"          "real.json"         "CodeSystem"]]]
      (let [f (io/file root d name)]
        (.mkdirs (.getParentFile f))
        (spit f (str "{\"resourceType\":\"" rt
                     "\",\"url\":\"http://example.com/x\"}\n"))))
    (try
      (let [files (sources/tx-file-seq (.getPath root))
            real  (.getPath (io/file root "real" "real.json"))]
        (is (= [real] (file-paths files :fhir-json))))
      (finally (delete-tree! root)))))

(deftest deeply-nested-empty-tree-returns-empty
  (testing "a deep dir tree with nothing recognisable"
    (is (= [] (sources/tx-file-seq (fixture "empty-deep"))))))

;; ---------------------------------------------------------------------------
;; Live: real publisher-shipped Hermes DB. Skipped when the pinned DB
;; is absent (CI provisions it; local dev provisions per the Quickstart).
;; ---------------------------------------------------------------------------

(deftest ^:live recognises-real-hermes-db
  (fixtures/assert-snomed-db!)
  (let [files   (sources/tx-file-seq fixtures/snomed-db-path)
        entries (entries-by-kind files :hermes-db)]
    (is (= 1 (count entries))
        "the real Hermes DB should be recognised as exactly one :hermes-db entry")
    (let [e (first entries)]
      (is (= "manifest.edn" (.getName ^File (:file e))))
      (is (= (.getCanonicalPath (io/file fixtures/snomed-db-path))
             (.getCanonicalPath ^File (:dir e))))
      (is (string? (:version e)))
      (is (str/starts-with? (:version e) "lmdb/")))))
