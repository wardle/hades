(ns com.eldrix.hades.impl.sources
  "Discover terminology files and built artefacts under a path.

  Detection is filename- and content-based: walk the tree and ask of
  each file `is this an RF2 component? a LOINC table? a FHIR JSON? a
  built FTRM container?`. Distribution layouts (TRUD bundles, vendor
  repackagings, nested vs flat extracts) are out of scope — if the
  files are recognisable, the source is recognisable. The only
  directory-level boundary is a Hermes built database, which is a
  self-contained artefact we don't want to descend into.

  `find-sources` walks `path` and returns a vector of findings:

      [{:kind <kind> :path <path>} ...]

  Recognised kinds:
    :rf2         — single SNOMED CT RF2 component file (sct2_*.txt etc.)
    :loinc       — LoincTableCore.csv (the canonical LOINC marker)
    :fhir-json   — FHIR JSON resource file (CodeSystem/ValueSet/...)
    :hermes-db   — built Hermes SNOMED database directory
    :fhir-tx-db  — built FHIR terminology SQLite container (FTRM)

  Per-file emission for terminology data lets the dispatcher decide
  how to aggregate: RF2 files get rolled up to their containing dirs
  before being handed to Hermes; LOINC files have their release root
  inferred (parent of `LoincTableCore/`) before being handed to the
  LOINC loader; FHIR JSON files are passed through directly."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [com.eldrix.hermes.snomed :as snomed])
  (:import (java.io File RandomAccessFile)))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; File-level detectors
;; ---------------------------------------------------------------------------

(defn- rf2-file?
  "Any file whose name parses as a SNOMED CT RF2 component filename
  (per the IHTSDO release file naming convention). Layout-agnostic."
  [^File f]
  (and (.isFile f)
       (some? (:component (snomed/parse-snomed-filename (.getName f))))))

(defn- loinc-marker-file?
  "The canonical LOINC marker. Other LOINC CSVs (PartLink, Hierarchy,
  AnswerList, …) have generic names that could collide with non-LOINC
  data; `LoincTableCore.csv` is distinctive enough to anchor a release
  on its own."
  [^File f]
  (and (.isFile f)
       (= "LoincTableCore.csv" (.getName f))))

(def ^:private json-detect-max-bytes
  "Skip files larger than this when sniffing for FHIR JSON. The header is
  in the first few KiB; arbitrarily large JSON in the tree is almost
  certainly not what we're looking for and parsing it would be wasteful."
  (* 16 1024 1024))

(defn- json-with-fhir-resource? [^File f]
  (and (.isFile f)
       (str/ends-with? (str/lower-case (.getName f)) ".json")
       (<= (.length f) json-detect-max-bytes)
       (try
         (with-open [r (io/reader f)]
           ;; Read just enough to find `"resourceType"` — header is always
           ;; near the start of a FHIR resource. Substring check on the
           ;; first 8 KiB is enough.
           (let [buf (char-array 8192)
                 n   (.read r buf)
                 head (String. buf 0 (max 0 n))]
             (and (str/includes? head "\"resourceType\"")
                  (some #(str/includes? head (str "\"" % "\""))
                        ["CodeSystem" "ValueSet" "ConceptMap" "Bundle"]))))
         (catch Exception _ false))))

(def ^:private fhir-tx-application-id
  "ASCII 'FTRM' = 0x4654524D, the FHIR-tx container application_id."
  0x4654524D)

(defn- read-be-int32 [^RandomAccessFile raf offset]
  (.seek raf (long offset))
  (let [b0 (bit-and (.read raf) 0xff)
        b1 (bit-and (.read raf) 0xff)
        b2 (bit-and (.read raf) 0xff)
        b3 (bit-and (.read raf) 0xff)]
    (bit-or (bit-shift-left b0 24)
            (bit-shift-left b1 16)
            (bit-shift-left b2 8)
            b3)))

(defn- has-fhir-tx-header? [^File f]
  ;; SQLite file header: bytes 0–15 are "SQLite format 3\0";
  ;; application_id is a 4-byte big-endian int at offset 68.
  (and (.isFile f)
       (>= (.length f) 100)
       (try
         (with-open [raf (RandomAccessFile. f "r")]
           (let [magic (byte-array 16)]
             (.readFully raf magic)
             (and (= "SQLite format 3" (String. magic 0 15))
                  (= fhir-tx-application-id (read-be-int32 raf 68)))))
         (catch Exception _ false))))

;; ---------------------------------------------------------------------------
;; Directory-level boundary (only one: a built Hermes DB)
;; ---------------------------------------------------------------------------

(defn- has-hermes-layout? [^File root]
  ;; Hermes packs an LMDB store + Lucene index into a directory with
  ;; `manifest.edn` at the top and `store.db` as a subdirectory. We
  ;; treat this as a self-contained artefact — descend stops here.
  (and (.isDirectory root)
       (.exists (io/file root "manifest.edn"))
       (.isDirectory (io/file root "store.db"))))

(defn- skip-dir?
  "Skip dot-directories and common build / cache directories during
  recursive detection. Without this, running `list .` in a project root
  walks `.git`, `.lsp`, `node_modules`, etc., producing surprises."
  [^File f]
  (and (.isDirectory f)
       (let [n (.getName f)]
         (or (str/starts-with? n ".")
             (#{"node_modules" "target" "build" "out"} n)))))

;; ---------------------------------------------------------------------------
;; Walk
;; ---------------------------------------------------------------------------

(defn- file-kind
  "First file-level detector to match wins. Order matters: an FTRM
  container is a SQLite file with a specific header, so it must be
  checked before any generic content sniff. RF2 / LOINC / FHIR JSON
  filename or header checks are mutually exclusive in practice."
  [^File f]
  (cond
    (has-fhir-tx-header? f)        :fhir-tx-db
    (rf2-file? f)                  :rf2
    (loinc-marker-file? f)         :loinc
    (json-with-fhir-resource? f)   :fhir-json))

(defn- descend?
  "True when `tree-seq` should walk into `f`'s children. Skip-dirs and
  recognised Hermes-DB containers terminate descent."
  [^File f]
  (and (.isDirectory f)
       (not (skip-dir? f))
       (not (has-hermes-layout? f))))

(defn- list-children [^File f]
  ;; Sorted for deterministic order; `.listFiles` returns nil for
  ;; unreadable dirs.
  (sort-by #(.getName ^File %) (or (.listFiles f) [])))

(defn- node->finding [^File f]
  (cond
    (.isFile f)      (when-let [k (file-kind f)] {:kind k :path (.getPath f)})
    (.isDirectory f) (when (has-hermes-layout? f) {:kind :hermes-db :path (.getPath f)})))

(defn find-sources
  "Walk `path` and return a vector of `{:kind :path}` findings.

  `path` may be a file or a directory. If `path` is itself recognised
  (a Hermes DB directory, an FTRM file, an RF2 component file, etc.)
  one finding is returned for it; otherwise the walker descends,
  emitting a finding per recognised file. Returns `[]` when no marker
  matched anywhere under `path`."
  [path]
  (let [^File root (io/file path)]
    (if (.exists root)
      (into [] (keep node->finding) (tree-seq descend? list-children root))
      [])))

(def ^:private kinds-help
  "Hermes SNOMED database (manifest.edn + store.db/), FHIR-tx SQLite container (application_id 'FTRM'), SNOMED RF2 component files (sct2_*.txt etc.), LOINC (LoincTableCore.csv), FHIR JSON (*.json with a CodeSystem/ValueSet/ConceptMap/Bundle).")

(defn find-sources!
  "Like `find-sources` but throws `ex-info` when the walk yields no
  findings. Useful for subcommands where an empty input is always an
  operator error (`import`, `serve`, `index`, `compact`)."
  [path]
  (let [r (find-sources path)]
    (if (seq r)
      r
      (throw (ex-info
               (str "Couldn't find any terminology sources under " path
                    ". Looked for: " kinds-help)
               {:reason :unknown-source-kind :path path})))))

;; ---------------------------------------------------------------------------
;; Aggregation helpers — turn per-file findings into loader-ready inputs
;; ---------------------------------------------------------------------------

(defn rf2-roots
  "Given a seq of `:rf2` findings, return the unique containing
  directories. Hermes' importer walks recursively from each, so we
  don't need to compute a notional `release root` — passing the
  immediate parent of every component file is sufficient (and
  deduplicating means we don't visit the same files twice)."
  [findings]
  (->> findings
       (filter #(= :rf2 (:kind %)))
       (map (fn [{:keys [path]}] (.getPath (.getParentFile (io/file path)))))
       distinct
       vec))

(defn loinc-roots
  "Given a seq of `:loinc` findings (each pointing at a
  LoincTableCore.csv file), return the unique release-root directories
  the LOINC loader expects. Standard release: file lives at
  `<root>/LoincTableCore/LoincTableCore.csv`, so the root is the
  grandparent. Flat extract: file lives at `<root>/LoincTableCore.csv`,
  so the root is the parent."
  [findings]
  (->> findings
       (filter #(= :loinc (:kind %)))
       (map (fn [{:keys [path]}]
              (let [f (io/file path)
                    parent (.getParentFile f)]
                (.getPath (if (= "LoincTableCore" (.getName parent))
                            (.getParentFile parent)
                            parent)))))
       distinct
       vec))

;; ---------------------------------------------------------------------------
;; Legacy single-kind shims
;; ---------------------------------------------------------------------------

(defn detect
  "Legacy single-kind shim. Returns the kind of the first finding under
  `path`, or nil when none is found. Prefer `find-sources` for new code."
  [path]
  (some-> (first (find-sources path)) :kind))

(defn detect!
  "Legacy single-kind shim. Like `detect` but throws `ex-info` when no
  marker matched."
  [path]
  (or (detect path)
      (throw (ex-info
               (str "Couldn't recognise " path " as a terminology source. "
                    "Looked for: " kinds-help)
               {:reason :unknown-source-kind :path path}))))
