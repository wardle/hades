(ns com.eldrix.hades.providers.loinc.loader
  "Raw LOINC release CSV streaming.

  This namespace emits release CSV rows as blocks of vectors. It does not
  shape FHIR resources and does not write SQLite."
  (:require [charred.api :as charred]
            [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [com.eldrix.hades.providers.common.canonical :as canonical]
            [com.eldrix.hades.providers.loinc.model :as model])
  (:import (java.io File)))

(set! *warn-on-reflection* true)

(s/def ::type keyword?)
(s/def ::file string?)
(s/def ::version string?)
(s/def ::variant-id string?)
(s/def ::headings (s/coll-of string? :kind vector?))
(s/def ::data (s/coll-of (s/coll-of string? :kind vector?) :kind vector?))

(s/def ::size nat-int?)

(s/def ::block
  (s/keys :req-un [::type]
          :opt-un [::file ::version ::variant-id ::headings ::data]))

(def release-files
  "The LOINC release files this loader knows how to read. `:type` is a
  semantic family used to key emitted data blocks; `:path` locates the
  file on disk — either an exact relative path (string) or a regex
  matched against every file's `/`-separated relative path under the
  release root; `:cols` are the columns the loader requires, checked
  against the CSV header as a subset. Trailing extra columns and
  reordering are accepted: each block carries `:headings`, so consumers
  reference cells by name. `:required?` marks files that must be
  present for the directory to be recognised as a LOINC release; every
  listed optional file is streamed when present and ignored when absent.

  Not streamed:
  - `LoincTableCore/*` — slim duplicate used only for release detection
    and version fallback when the full LOINC table is absent."
  model/release-files)

(defn loinc-file
  "Return the `release-files` entry matching `f` under release `root`,
  with `:path` rewritten to the actual relative path of `f`. Returns nil
  when `f` is not a known LOINC release file."
  [root f]
  (let [^File root (io/file root)
        ^File file (io/file f)
        rel (-> (.relativize (.toPath root) (.toPath file))
                str
                (str/replace File/separatorChar \/))]
    (some (fn [{:keys [path] :as lf}]
            (when (if (string? path) (= path rel) (re-matches path rel))
              (assoc lf :path rel)))
          release-files)))

(defn loinc-file-seq* [dir]
  (keep #(loinc-file dir %) (file-seq (io/file dir))))

(defn loinc-file-seq
  "Returns a sequence of LOINC file definitions for the directory specified,
  or nil if the directory is not a LOINC distribution."
  [dir]
  (let [required (->> release-files (filter :required?) (map :type) set)
        files (loinc-file-seq* dir)
        found (set (map :type files))]
    (when (set/subset? required found)
      (map (fn [{:keys [path] :as lf}]
             (assoc lf :f (io/file dir path)))
           files))))

(defn csv-rows
  "Read CSV from `r`, returning a lazy seq of `{column -> cell}` row maps
  keyed by the header row."
  [r]
  (let [[header & rows] (charred/read-csv r :async? false :close-reader? false)]
    (map #(zipmap header %) rows)))

(defn- read-version-from
  [f]
  (when (.exists ^File f)
    (with-open [r (io/reader f :encoding "UTF-8")]
      (reduce (fn [best {v "VersionLastChanged"}]
                (if (pos? (canonical/semver-compare v best)) v best))
              nil
              (csv-rows r)))))

(defn read-version
  "Return max VersionLastChanged from the release tables, or nil.
  Every release bumps at least one concept's last-changed marker, so
  `max(VersionLastChanged)` is the release version itself —
  content-derived, immune to operator-renamed unzip directories. Prefer
  `LoincTableCore/LoincTableCore.csv` when present because it is
  narrower, but fall back to required `LoincTable/Loinc.csv` so optional
  Core packaging is not required for import."
  [root]
  (or (read-version-from (io/file root "LoincTableCore" "LoincTableCore.csv"))
      (read-version-from (io/file root "LoincTable" "Loinc.csv"))))

(defn- block-extra
  [{:keys [block-meta path]}]
  (if block-meta
    (block-meta path)
    {}))

(defn metadata
  "Static information about the LOINC release at `dir`."
  [dir]
  (when-let [files (loinc-file-seq dir)]
    {:version (read-version dir)
     :files   files}))

(def loinc-recogniser
  {:id          :loinc
   :scope       :directory
   :importable? true
   :database?   false
   :recognise   (fn [^File dir _probe?]
                  (when (loinc-file-seq dir) {}))})

(defn stream-file!
  "Stream `f` to `ch` in `batch-size` row chunks. Throws when any column
  declared in `cols` is absent from the file header — that's the failure
  mode that matters, because downstream consumers reference cells by
  header name. Trailing extra columns and reordering are forwarded
  untouched: each block carries `:headings` so consumers can `zipmap` by
  name."
  [ch version batch-size {:keys [f type path cols] :as file-def}]
  (let [[headings & rows] (charred/read-csv f)
        missing (remove (set headings) cols)]
    (when (seq missing)
      (throw (ex-info (str "Missing columns in " path ": " missing)
                      {:path path :found headings :missing missing :expected cols})))
    (doseq [batch (partition-all batch-size rows)]
      (async/>!! ch (merge {:type     type :path path :version version
                            :headings headings :data batch}
                           (block-extra file-def))))))

(defn stream-release
  "Blocking: stream raw LOINC CSV blocks from an unpacked release directory.
   Run in a background thread. e.g.
   ```
   (async/thread
     (stream-release ch dir))
   ```
   Data blocks carry vectors of row vectors keyed by a semantic `:type`
   from `release-files`. Failures are emitted as a final
   `{:type ::error :ex t}` block before the channel closes; the
   function does not throw."
  ([ch dir] (stream-release ch dir nil))
  ([ch dir {:keys [batch-size close?] :or {batch-size 500 close? true}}]
   (try
     (if-let [{:keys [files version]} (metadata dir)]
       (run! #(stream-file! ch version batch-size %) files)
       (throw (ex-info (str "Invalid LOINC distribution: " dir) {})))
     (catch Throwable t
       (async/>!! ch {:type ::error :ex t}))
     (finally
       (when close? (async/close! ch))))))

(defn row-counts
  "Stream `dir` through `stream-release` and tally data-row counts —
  the streaming-API equivalent of `wc -l` minus each file's header.
  Returns `{:per-file {path -> rows} :by-type {type -> rows}}`."
  [dir]
  (let [ch (async/chan 8)
        _ (async/thread (stream-release ch dir))]
    (loop [per-file {} by-type {}]
      (if-let [{:keys [type path data ex]} (async/<!! ch)]
        (if (= type ::error)
          (throw ex)
          (let [n (count data)]
            (recur (update per-file path (fnil + 0) n)
                   (update by-type type (fnil + 0) n))))
        {:per-file per-file :by-type by-type}))))
