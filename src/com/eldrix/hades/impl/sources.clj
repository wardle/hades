(ns com.eldrix.hades.impl.sources
  "Walk a path and recognise terminology files.

  `tx-file-seq` returns a flat sequence of entries, one per recognised
  source. Each entry has the shape:

      {:file        java.io.File   ; the file or directory that was recognised
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
  `providers/snomed`, `providers/loinc`, `providers/common/fhir-loader`,
  `providers/ftrm/db`. Each one knows how to recognise its own files or
  directories; the walker is just glue."
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.tools.logging.readable :as log]
            [com.eldrix.hades.providers.common.fhir-loader :as fhir]
            [com.eldrix.hades.providers.loinc.loader :as loinc]
            [com.eldrix.hades.providers.loinc.store :as loinc-store]
            [com.eldrix.hades.providers.snomed.provider :as snomed]
            [com.eldrix.hades.providers.ftrm.db :as ftrm-db])
  (:import (java.io File IOException)))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Specs — the contracts between the walker, its registry, and consumers.
;; ---------------------------------------------------------------------------

(s/def ::kind        #{:rf2 :loinc :loinc-db :fhir-json :hermes-db :fhir-tx-db})
(s/def ::file        #(instance? File %))
(s/def ::dir         ::file)
(s/def ::importable? boolean?)
(s/def ::database?   boolean?)

;; A recogniser-fn takes a `File` plus a `probe?` boolean and returns
;; either nil (not mine) or a map of kind-specific annotations.
(s/def ::recognise   ifn?)
(s/def ::id          ::kind)
(s/def ::scope       #{:file :directory})

(s/def ::recogniser
  (s/keys :req-un [::id ::importable? ::database? ::recognise]
          :opt-un [::scope]))

;; The walker's return entries: walker-supplied fields plus the
;; recogniser's annotations. `:dir` is opt-un because only kinds whose
;; natural unit is a directory carry it (RF2 / LOINC / Hermes-DB).
(s/def ::entry
  (s/keys :req-un [::file ::kind ::importable? ::database?]
          :opt-un [::dir]))

(def ^:private recognisers
  "Ordered registry. First match wins. Order matters when a path could
  plausibly satisfy more than one recogniser; today the kinds are
  distinctive enough that order is for clarity, not correctness."
  [ftrm-db/fhir-tx-db-recogniser
   loinc-store/loinc-db-recogniser
   snomed/hermes-db-recogniser
   snomed/rf2-recogniser
   loinc/loinc-recogniser
   fhir/fhir-json-recogniser])

(defn- recogniser-scope [{:keys [scope]}]
  (or scope :file))

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

(defn- list-children
  "Return `f`'s children sorted by name. Distinguishes empty dirs from
  I/O errors: `File.listFiles` returns `null` *both* on a non-directory
  and on a real I/O error reading the directory. Silently flattening
  null → `[]` (the previous behaviour) hid genuine I/O errors as
  'no recognisable files', which surfaced downstream as a misleading
  `Couldn't find any terminology sources` from `tx-file-seq` callers.
  We now throw on directories whose listing failed; non-directories
  return `[]` as before."
  [^File f]
  (if-let [children (.listFiles f)]
    (sort-by #(.getName ^File %) children)
    (if (.isDirectory f)
      (throw (IOException. (str "Failed to list directory contents: "
                                (.getPath f))))
      [])))

(defn- recognise-path
  "Apply recognisers of `scope` to `f` in order; first to claim it wins.
  Returns a complete entry, or nil when no recogniser claims the file.

  Centralises error handling: a recogniser may throw to indicate it
  *would* have claimed the file but couldn't open it (corrupt header,
  permission, transient I/O). We log a warning naming the recogniser
  and file, then fall through to the next recogniser. This replaces
  per-recogniser silent `(catch Exception _ false/nil)` patterns that
  made an empty walk indistinguishable from broken files at scale.

  The returned entry is asserted against `::entry` so a misbehaving
  recogniser is caught at the boundary (asserts are a no-op when
  `*compile-asserts*` is false)."
  [scope ^File f probe?]
  (reduce (fn [_ {:keys [id importable? database? recognise] :as r}]
            (when (= scope (recogniser-scope r))
              (when-let [ann (try (recognise f probe?)
                                  (catch Exception e
                                    (log/warn e "recogniser threw"
                                              {:recogniser id
                                               :file (.getPath f)})
                                    nil))]
                (let [base (cond-> {:file        f
                                     :kind        id
                                     :importable? importable?
                                     :database?   database?}
                             (= scope :directory) (assoc :dir f))]
                  (reduced (s/assert ::entry (merge base ann)))))))
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
       (let [paths (tree-seq #(and (.isDirectory ^File %) (not (skip-dir? %)))
                             list-children
                             root)]
         (into []
               (keep (fn [^File f]
                       (cond
                         (.isDirectory f) (recognise-path :directory f probe?)
                         (.isFile f)      (recognise-path :file f probe?))))
               paths))
       []))))
