(ns com.eldrix.hades.impl.sources
  "Detect what kind of terminology release or built artefact lives at a
  given path. Used by the `import` and `serve` subcommands to dispatch
  to the right loader / provider without the operator having to declare
  a `--type`.

  Returns one of:
    :rf2         — SNOMED RF2 release directory (Hermes import source)
    :loinc       — LOINC CSV release directory (SQLite import source)
    :fhir-json   — FHIR JSON resources file or directory
                   (in-memory provider OR SQLite import source)
    :hermes-db   — built Hermes SNOMED database directory
    :fhir-tx-db  — built FHIR terminology SQLite container (FTRM)
    nil          — no marker matched"
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.io File RandomAccessFile)))

(defn- has-loinc-core? [^File root]
  (.exists (io/file root "LoincTableCore" "LoincTableCore.csv")))

(defn- has-rf2-snapshot? [^File root]
  ;; RF2 releases ship a Snapshot/Terminology/sct2_*.txt layout; we
  ;; just check for the directory + at least one matching file.
  (let [d (io/file root "Snapshot" "Terminology")]
    (and (.isDirectory d)
         (some #(let [n (.getName ^File %)]
                  (and (str/starts-with? n "sct2_")
                       (str/ends-with? (str/lower-case n) ".txt")))
               (.listFiles d)))))

(defn- json-with-fhir-resource? [^File f]
  (and (.isFile f)
       (str/ends-with? (str/lower-case (.getName f)) ".json")
       (try
         (with-open [r (io/reader f)]
           ;; Read just enough to find `"resourceType"` — these files
           ;; can be enormous and we don't want to parse the whole tree
           ;; just to identify the kind. A simple substring check on
           ;; the first 8 KiB is enough.
           (let [buf (char-array 8192)
                 n   (.read r buf)
                 head (String. buf 0 (max 0 n))]
             (and (str/includes? head "\"resourceType\"")
                  (some #(str/includes? head (str "\"" % "\""))
                        ["CodeSystem" "ValueSet" "ConceptMap" "Bundle"]))))
         (catch Exception _ false))))

(defn- has-fhir-json? [^File root]
  (cond
    (.isFile root) (json-with-fhir-resource? root)
    (.isDirectory root)
    (some json-with-fhir-resource?
          (filter #(.isFile ^File %) (file-seq root)))
    :else false))

(def ^:private fhir-tx-application-id
  "ASCII 'FTRM' = 0x4654524D, the FHIR-tx container application_id."
  0x4654524D)

(defn- has-hermes-layout? [^File root]
  ;; Hermes packs an LMDB store + Lucene index into a directory with
  ;; `manifest.edn` at the top and `store.db` as a subdirectory.
  (and (.isDirectory root)
       (.exists (io/file root "manifest.edn"))
       (.isDirectory (io/file root "store.db"))))

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

(def ^:private detectors
  ;; Order matters when more than one marker could plausibly match.
  ;; Built-artefact detectors first (cheap, exact); release directories
  ;; next; FHIR JSON last because it's the catch-all (any .json with a
  ;; resourceType).
  [[:hermes-db  has-hermes-layout?]
   [:fhir-tx-db has-fhir-tx-header?]
   [:rf2        has-rf2-snapshot?]
   [:loinc      has-loinc-core?]
   [:fhir-json  has-fhir-json?]])

(defn detect
  "Return the detected source kind for `path`, or nil if no marker
  matched. `path` may be a file or directory."
  [path]
  (let [^File f (io/file path)]
    (when (.exists f)
      (some (fn [[kind pred]] (when (pred f) kind)) detectors))))

(defn detect!
  "Like `detect` but throws `ex-info` with a clear error when no marker
  matches. The message lists what we looked for so the operator can see
  why detection failed."
  [path]
  (or (detect path)
      (throw (ex-info
               (str "Couldn't recognise " path " as a terminology source. "
                    "Looked for: Hermes SNOMED database (manifest.edn + store.db/), "
                    "FHIR-tx SQLite container (application_id 'FTRM'), "
                    "SNOMED RF2 (Snapshot/Terminology/sct2_*.txt), "
                    "LOINC (LoincTableCore/LoincTableCore.csv), "
                    "FHIR JSON (*.json with a CodeSystem/ValueSet/ConceptMap/Bundle).")
               {:reason :unknown-source-kind :path path}))))
