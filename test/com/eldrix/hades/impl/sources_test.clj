(ns com.eldrix.hades.impl.sources-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [com.eldrix.hades.impl.sources :as sources]
            [com.eldrix.hades.impl.sqlite.db :as db]
            [next.jdbc])
  (:import (java.io File)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

;; ---------------------------------------------------------------------------
;; Helpers for building fake source trees on disk.
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

(defn- canonical-rf2-name
  "A real-shaped RF2 filename that hermes.snomed/parse-snomed-filename
  will accept: sct2_<Component>_<ReleaseType><...>_<Region>_<Date>.txt."
  [component]
  (str "sct2_" component "_Snapshot_INT_20250201.txt"))

(defn- mk-rf2-file!
  "Drop a single RF2 component file at `dir/<filename>`. No layout
  scaffolding — that's the whole point of file-level detection."
  ^File [^File dir component]
  (spit-file! (io/file dir (canonical-rf2-name component)) "id\teffectiveTime\n"))

(defn- mk-loinc-marker!
  "Drop LoincTableCore.csv at `dir/<name>` (flat) or
  `dir/LoincTableCore/LoincTableCore.csv` (nested)."
  [^File dir & {:keys [nested?] :or {nested? true}}]
  (let [target (if nested?
                 (io/file dir "LoincTableCore" "LoincTableCore.csv")
                 (io/file dir "LoincTableCore.csv"))]
    (spit-file! target "LOINC_NUM,COMPONENT\n")))

(defn- mk-hermes-dir!
  "Create a directory that looks like a Hermes built database."
  ^File [^File root name]
  (let [d (io/file root name)]
    (spit-file! (io/file d "manifest.edn") "{}")
    (.mkdirs (io/file d "store.db"))
    d))

(defn- mk-fhir-json! ^File [^File dir name resource-type]
  (spit-file! (io/file dir name)
              (str "{\"resourceType\":\"" resource-type
                   "\",\"url\":\"http://example.com/x\"}\n")))

(defn- finding-paths [findings kind]
  (->> findings (filter #(= kind (:kind %))) (map :path) sort vec))

;; ---------------------------------------------------------------------------
;; Existing fixture-based detection.
;; ---------------------------------------------------------------------------

(deftest detects-loinc-fixture
  (testing "loinc-fixture has a LoincTableCore.csv marker"
    (let [findings (sources/find-sources "test/resources/loinc-fixture")]
      (is (= 1 (count (filter #(= :loinc (:kind %)) findings))))
      (is (every? #(str/ends-with? (:path %) "LoincTableCore.csv")
                  (filter #(= :loinc (:kind %)) findings))))
    (testing "loinc-roots resolves the file finding back to the release dir"
      (is (= ["test/resources/loinc-fixture"]
             (sources/loinc-roots
              (sources/find-sources "test/resources/loinc-fixture")))))))

(deftest detects-fhir-json-resources-dir
  (testing "fhir-resources/good has CodeSystem JSON files"
    (let [findings (sources/find-sources "test/resources/fhir-resources/good")]
      (is (every? #(= :fhir-json (:kind %)) findings))
      (is (every? #(str/ends-with? (:path %) ".json") findings))
      (testing "walks into nested/ to find nested-cs.json"
        (is (some #(str/ends-with? (:path %) "nested-cs.json") findings))))))

(deftest detects-fhir-tx-sqlite-container
  (let [^File f (File/createTempFile "fhir-tx-detect" ".db")]
    (.delete f)
    (try
      (let [ds (db/create! (.getPath f))]
        (db/close! ds))
      (is (= [{:kind :fhir-tx-db :path (.getPath f)}]
             (sources/find-sources (.getPath f))))
      (finally (.delete f)))))

(deftest does-not-mistake-plain-sqlite-for-fhir-tx
  (let [^File f (File/createTempFile "plain-sqlite" ".db")]
    (.delete f)
    (try
      (let [ds (next.jdbc/get-datasource {:dbtype "sqlite" :dbname (.getPath f)})]
        (with-open [conn (next.jdbc/get-connection ds)]
          (next.jdbc/execute! conn ["CREATE TABLE noise (x INTEGER)"])))
      (is (= [] (sources/find-sources (.getPath f))))
      (finally (.delete f)))))

(deftest unknown-path-returns-empty-and-bang-throws
  (let [tmp (doto (java.io.File/createTempFile "empty" "")
              (.delete))
        d (java.io.File. (.getParentFile tmp) "definitely-not-a-release")]
    (.mkdir d)
    (try
      (is (= [] (sources/find-sources (.getPath d))))
      (is (thrown? clojure.lang.ExceptionInfo (sources/find-sources! (.getPath d))))
      (finally (.delete d)))))

;; ---------------------------------------------------------------------------
;; File-level detection: sct2_*.txt anywhere is RF2; LoincTableCore.csv
;; anywhere is LOINC. No layout coupling.
;; ---------------------------------------------------------------------------

(deftest single-rf2-file-anywhere-is-detected
  (testing "a flat directory with one RF2 file — no Snapshot/Terminology nesting"
    (let [root (mk-tmp-dir "flat-rf2")]
      (try
        (mk-rf2-file! root "Concept")
        (let [findings (sources/find-sources (.getPath root))]
          (is (= 1 (count (filter #(= :rf2 (:kind %)) findings))))
          (is (= [(.getPath root)] (sources/rf2-roots findings))))
        (finally (delete-tree! root))))))

(deftest rf2-files-at-arbitrary-depth-are-detected
  (testing "a single RF2 file buried 6 levels deep is still found"
    (let [root (mk-tmp-dir "deep-rf2")]
      (try
        (let [deep-dir (io/file root "x" "y" "z" "release" "Snapshot" "Terminology")]
          (mk-rf2-file! deep-dir "Description"))
        (let [findings (sources/find-sources (.getPath root))]
          (is (= 1 (count (filter #(= :rf2 (:kind %)) findings))))
          (is (str/ends-with? (first (sources/rf2-roots findings)) "Terminology")))
        (finally (delete-tree! root))))))

(deftest finds-sibling-rf2-trees-under-one-parent
  (testing "TRUD-style bundle: several release subtrees side by side"
    (let [root (mk-tmp-dir "trud-bundle")]
      (try
        ;; Three sibling 'releases', each laid out a bit differently.
        (mk-rf2-file! (io/file root "uk-clin" "Snapshot" "Terminology") "Concept")
        (mk-rf2-file! (io/file root "uk-clin" "Snapshot" "Terminology") "Description")
        (mk-rf2-file! (io/file root "uk-drug" "Snapshot" "Terminology") "Concept")
        ;; flat layout — sct2 file directly in the release dir
        (mk-rf2-file! (io/file root "uk-edition") "Concept")
        (let [findings (sources/find-sources (.getPath root))
              roots (sources/rf2-roots findings)]
          ;; 4 component files: two in uk-clin/Snapshot/Terminology,
          ;; one each in uk-drug and the flat uk-edition.
          (is (= 4 (count (filter #(= :rf2 (:kind %)) findings))))
          ;; rf2-roots returns the unique parent directories of those
          ;; component files; passing those to hermes/import-snomed
          ;; lets it find any siblings via its own recursion. Three
          ;; subtrees → three unique parent directories.
          (is (= 3 (count roots))))
        (finally (delete-tree! root))))))

(deftest loinc-detected-in-flat-and-nested-layouts
  (testing "LoincTableCore.csv is detected wherever it sits"
    (let [nested (mk-tmp-dir "loinc-nested")
          flat   (mk-tmp-dir "loinc-flat")]
      (try
        (mk-loinc-marker! nested :nested? true)
        (mk-loinc-marker! flat   :nested? false)
        (testing "nested release: root is the grandparent of LoincTableCore.csv"
          (let [r (sources/loinc-roots (sources/find-sources (.getPath nested)))]
            (is (= [(.getPath nested)] r))))
        (testing "flat release: root is the file's parent"
          (let [r (sources/loinc-roots (sources/find-sources (.getPath flat)))]
            (is (= [(.getPath flat)] r))))
        (finally (delete-tree! nested) (delete-tree! flat))))))

(deftest finds-mixed-loinc-and-fhir-json-in-one-tree
  (testing "LOINC release alongside a fhir-bundles dir"
    (let [root (mk-tmp-dir "mixed")]
      (try
        (mk-loinc-marker! (io/file root "loinc-2.82") :nested? true)
        (let [fhir-dir (io/file root "fhir-bundles")
              cs (mk-fhir-json! fhir-dir "cs.json" "CodeSystem")
              vs (mk-fhir-json! fhir-dir "vs.json" "ValueSet")]
          (let [findings (sources/find-sources (.getPath root))]
            (is (= [(.getPath (io/file root "loinc-2.82"))]
                   (sources/loinc-roots findings)))
            (is (= (sort [(.getPath cs) (.getPath vs)])
                   (finding-paths findings :fhir-json)))))
        (finally (delete-tree! root))))))

(deftest mixed-rf2-and-fhir-json-sibling-in-tree
  (testing "an RF2 release next to a fhir-bundles dir"
    (let [root (mk-tmp-dir "rf2+fhir")]
      (try
        (mk-rf2-file! (io/file root "rf2-tree" "Snapshot" "Terminology") "Concept")
        (let [cs (mk-fhir-json! (io/file root "bundles") "cs.json" "CodeSystem")]
          (let [findings (sources/find-sources (.getPath root))]
            (is (= 1 (count (filter #(= :rf2 (:kind %)) findings))))
            (is (= [(.getPath cs)] (finding-paths findings :fhir-json)))))
        (finally (delete-tree! root))))))

;; ---------------------------------------------------------------------------
;; The Hermes-DB boundary still terminates descent.
;; ---------------------------------------------------------------------------

(deftest walker-stops-at-hermes-db-boundary
  (testing "files inside a hermes-db are not picked up"
    (let [root (mk-tmp-dir "hermes-boundary")]
      (try
        (let [hermes (mk-hermes-dir! root "snomed.db")]
          ;; Plant rogue files inside the hermes-db that would otherwise
          ;; match our detectors. The walker must not descend in.
          (mk-fhir-json! hermes "rogue.json" "CodeSystem")
          (mk-rf2-file!  hermes "Concept")
          (mk-loinc-marker! hermes :nested? true)
          (let [findings (sources/find-sources (.getPath root))]
            (is (= [{:kind :hermes-db :path (.getPath hermes)}] findings))))
        (finally (delete-tree! root))))))

(deftest walker-skips-dot-and-build-dirs
  (testing "node_modules, .git etc. are not walked"
    (let [root (mk-tmp-dir "skip-dirs")]
      (try
        (mk-fhir-json! (io/file root ".git")          "config.json"        "CodeSystem")
        (mk-fhir-json! (io/file root "node_modules")  "package.json"       "ValueSet")
        (mk-fhir-json! (io/file root "target")        "classes-meta.json"  "CodeSystem")
        (mk-fhir-json! (io/file root "real")          "real.json"          "CodeSystem")
        (let [findings (sources/find-sources (.getPath root))
              real (.getPath (io/file root "real" "real.json"))]
          (is (= [real] (finding-paths findings :fhir-json))))
        (finally (delete-tree! root))))))

(deftest deeply-nested-empty-tree-returns-empty
  (testing "a deep dir tree with nothing recognisable"
    (let [root (mk-tmp-dir "empty-deep")]
      (try
        (.mkdirs (io/file root "a" "b" "c" "d"))
        (spit-file! (io/file root "a" "b" "readme.txt") "hello")
        (is (= [] (sources/find-sources (.getPath root))))
        (is (thrown? clojure.lang.ExceptionInfo
                     (sources/find-sources! (.getPath root))))
        (finally (delete-tree! root))))))

(deftest find-sources-on-nonexistent-path
  (let [missing (str (System/getProperty "java.io.tmpdir") "/no-such-thing-" (rand-int 1000000))]
    (is (= [] (sources/find-sources missing)))
    (is (nil? (sources/detect missing)))))

(deftest legacy-detect-returns-first-kind
  (testing "with multiple findings, detect returns one (legacy single-kind shim)"
    (let [root (mk-tmp-dir "legacy")]
      (try
        (mk-rf2-file! (io/file root "a") "Concept")
        (mk-rf2-file! (io/file root "b") "Concept")
        (is (= :rf2 (sources/detect (.getPath root))))
        (is (= 2 (count (sources/find-sources (.getPath root)))))
        (finally (delete-tree! root))))))
