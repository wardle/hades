(ns com.eldrix.hades.impl.archive
  "Treat archive files as terminology sources.

  A positional source path that is an archive file — `.tgz`, `.tar.gz`,
  `.tar` or `.zip` — is extracted into a fresh temporary directory and
  that directory substituted downstream. The walker (`impl/sources`) and
  the loaders need no knowledge of archives: they only ever see a
  directory. Resolution is deliberate and once-per-command — `resolve-sources`
  returns the temp directories so the caller cleans them up (a `finally`
  for `import`, a service closer for `serve`)."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.tools.logging.readable :as log])
  (:import (java.io File)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.util.zip ZipEntry ZipFile)))

(set! *warn-on-reflection* true)

(def ^:private tar-suffixes [".tgz" ".tar.gz" ".tar"])
(def ^:private zip-suffixes [".zip"])
(def ^:private archive-suffixes (into tar-suffixes zip-suffixes))

(defn- has-suffix? [^File f suffixes]
  (let [n (str/lower-case (.getName f))]
    (boolean (some #(str/ends-with? n %) suffixes))))

(defn archive?
  "True when `path` is an existing regular file with a recognised archive
  extension (`.tgz`, `.tar.gz`, `.tar`, `.zip`)."
  [path]
  (let [^File f (io/file path)]
    (and (.isFile f) (has-suffix? f archive-suffixes))))

(defn- extract-tar!
  "Extract a tar-family archive via the system `tar`. `tar xf` auto-detects
  gzip, and refuses absolute / `..` member paths by default."
  [^File archive ^File dest]
  (let [{:keys [exit err]} (sh/sh "tar" "xf" (.getPath archive) "-C" (.getPath dest))]
    (when (not= 0 exit)
      (throw (ex-info (str "tar extract failed: " err)
                      {:archive (.getPath archive) :dest (.getPath dest) :exit exit})))))

(defn- within?
  "True when `child` resolves inside `parent` — guards against zip-slip
  entries (`../`, absolute paths) escaping the destination directory."
  [^File parent ^File child]
  (.startsWith (.toPath (.getCanonicalFile child))
               (.toPath (.getCanonicalFile parent))))

(defn- extract-zip! [^File archive ^File dest]
  (with-open [zf (ZipFile. archive)]
    (doseq [^ZipEntry entry (enumeration-seq (.entries zf))]
      (let [out (io/file dest (.getName entry))]
        (when-not (within? dest out)
          (throw (ex-info (str "zip entry escapes destination: " (.getName entry))
                          {:archive (.getPath archive) :entry (.getName entry)})))
        (if (.isDirectory entry)
          (.mkdirs out)
          (do (.mkdirs (.getParentFile out))
              (with-open [in (.getInputStream zf entry)]
                (io/copy in out))))))))

(defn extract!
  "Extract `archive` into `dest` (created if absent). Dispatches on
  extension: the tar family via the system `tar`, `.zip` via
  `java.util.zip`. Returns `dest`."
  [^File archive ^File dest]
  (.mkdirs dest)
  (cond
    (has-suffix? archive tar-suffixes) (extract-tar! archive dest)
    (has-suffix? archive zip-suffixes) (extract-zip! archive dest)
    :else (throw (ex-info (str "Unrecognised archive type: " (.getName archive))
                          {:archive (.getPath archive)})))
  dest)

(defn extract-to-temp!
  "Extract `archive` into a fresh temporary directory and return that
  directory (a `File`)."
  ^File [^File archive]
  (let [dest (.toFile (Files/createTempDirectory "hades-archive-"
                                                  (make-array FileAttribute 0)))]
    (log/info "extracting archive" {:archive (.getPath archive) :into (.getPath dest)})
    (extract! archive dest)))

(defn delete-recursively
  "Delete `dir` and everything under it."
  [^File dir]
  (when (.isDirectory dir)
    (run! delete-recursively (.listFiles dir)))
  (.delete dir))

(defn resolve-sources
  "Resolve input source `paths`, extracting any path that is an archive
  file to a temporary directory and substituting that directory. Non-archive
  paths pass through unchanged. Returns `{:paths <substituted path strings>
  :temp-dirs <extraction dirs>}`; delete the `:temp-dirs` with
  `delete-recursively` once the paths are no longer needed."
  [paths]
  (reduce (fn [acc path]
            (if (archive? path)
              (let [dir (extract-to-temp! (io/file path))]
                (-> acc
                    (update :paths conj (.getPath dir))
                    (update :temp-dirs conj dir)))
              (update acc :paths conj (str path))))
          {:paths [] :temp-dirs []}
          paths))
