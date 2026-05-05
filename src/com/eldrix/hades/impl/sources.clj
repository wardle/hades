(ns com.eldrix.hades.impl.sources
  "Walk a path and recognise terminology files.

  `tx-file-seq` returns a flat sequence of entries, one per recognised
  file. Each entry has the shape:

      {:file        java.io.File   ; the file that was recognised
       :kind        :rf2 | :loinc | :fhir-json | :hermes-db | :fhir-tx-db
       :importable? boolean        ; can be fed to an importer
       :database?   boolean        ; can be opened as a provider
       :dir         java.io.File   ; only on kinds whose unit is a directory
       ;; …kind-specific annotations (:component, :version, :resource-type, …)}

  `:importable?` and `:database?` are role flags declared on the
  recogniser; consumers filter by them. `:file` is what was recognised;
  `:dir` is the consumer-facing handle for kinds where the natural unit
  is a directory (RF2 release, LOINC release, Hermes DB).

  Recognition is delegated to per-provider recognisers — see
  `impl/snomed`, `impl/loaders/loinc`, `impl/loaders/fhir`,
  `impl/sqlite/db`. Each one knows how to recognise its own files; the
  walker is just glue."
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [com.eldrix.hades.impl.loaders.fhir :as fhir]
            [com.eldrix.hades.impl.loaders.loinc :as loinc]
            [com.eldrix.hades.impl.snomed :as snomed]
            [com.eldrix.hades.impl.sqlite.db :as sqlite-db])
  (:import (java.io File)))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Specs — the contracts between the walker, its registry, and consumers.
;; ---------------------------------------------------------------------------

(s/def ::kind        #{:rf2 :loinc :fhir-json :hermes-db :fhir-tx-db})
(s/def ::file        #(instance? File %))
(s/def ::dir         ::file)
(s/def ::importable? boolean?)
(s/def ::database?   boolean?)

;; A recogniser-fn takes a `File` plus a `probe?` boolean and returns
;; either nil (not mine) or a map of kind-specific annotations.
(s/def ::recognise   ifn?)
(s/def ::id          ::kind)

(s/def ::recogniser
  (s/keys :req-un [::id ::importable? ::database? ::recognise]))

;; The walker's return entries: walker-supplied fields plus the
;; recogniser's annotations. `:dir` is opt-un because only kinds whose
;; natural unit is a directory carry it (RF2 / LOINC / Hermes-DB).
(s/def ::entry
  (s/keys :req-un [::file ::kind ::importable? ::database?]
          :opt-un [::dir]))

(def ^:private recognisers
  "Ordered registry. First match wins. Order matters when a file could
  plausibly satisfy more than one recogniser; today the kinds are
  distinctive enough that order is for clarity, not correctness."
  [sqlite-db/fhir-tx-db-recogniser
   snomed/hermes-db-recogniser
   snomed/rf2-recogniser
   loinc/loinc-recogniser
   fhir/fhir-json-recogniser])

;; Validate the registry at load time so a misshapen recogniser fails
;; here rather than during a walk. Cheap; runs once per ns load.
(when-let [bad (seq (remove #(s/valid? ::recogniser %) recognisers))]
  (throw (ex-info (str "Invalid recogniser(s) in registry: "
                       (s/explain-str (s/coll-of ::recogniser) bad))
                  {:invalid bad})))

(defn- skip-dir?
  "Skip dot-directories and common build/cache directories. Without
  this, walking a project root descends into `.git`, `node_modules`,
  `target`, etc., producing surprises."
  [^File f]
  (and (.isDirectory f)
       (let [n (.getName f)]
         (or (str/starts-with? n ".")
             (#{"node_modules" "target" "build" "out"} n)))))

(defn- list-children [^File f]
  (sort-by #(.getName ^File %) (or (.listFiles f) [])))

(defn- recognise-file
  "Apply each recogniser to `f` in order; first to claim it wins.
  Returns a complete entry, or nil when no recogniser claims the file.
  The returned entry is asserted against `::entry` so a misbehaving
  recogniser is caught at the boundary (asserts are a no-op when
  `*compile-asserts*` is false)."
  [^File f probe?]
  (reduce (fn [_ {:keys [id importable? database? recognise]}]
            (when-let [ann (recognise f probe?)]
              (reduced (s/assert ::entry
                                 (assoc ann
                                        :file        f
                                        :kind        id
                                        :importable? importable?
                                        :database?   database?)))))
          nil
          recognisers))

(defn tx-file-seq
  "Walk `path` and return a flat seq of recognised terminology entries.
  See the namespace docstring for the entry shape. Returns `[]` when
  `path` doesn't exist or contains nothing recognisable.

  `:probe?` (default false) lets recognisers opt into more expensive
  enrichment of annotations — e.g. opening a database to count
  resources. Off the cheap walking path."
  ([path] (tx-file-seq path {}))
  ([path {:keys [probe?] :or {probe? false}}]
   (let [^File root (io/file path)]
     (if (.exists root)
       (into []
             (comp (filter #(.isFile ^File %))
                   (keep #(recognise-file % probe?)))
             (tree-seq #(and (.isDirectory ^File %) (not (skip-dir? %)))
                       list-children
                       root))
       []))))
