(ns com.eldrix.hades.snomed-db
  "Identity and provisioning for the canonical SNOMED CT database used by
  integration tests and the conformance suite.

  We pin to one specific SNOMED CT International release: tests, benchmarks,
  and the conformance suite all open the same DB on every machine. The IG's
  tx-ecosystem fixtures are themselves authored against this release;
  running against any other release produces failures that are data-version
  drift, not real defects.

  Building the DB is a separate explicit step (`clj -X:build-db`); tests and
  conformance only consume it. They fail fast if the DB is missing.

  Provisioning paths (in order of preference):

    1. If `.hades/snomed-intl-20250201.db` already exists, use it.
    2. Else if a release zip is at `.hades/snomed-int-20250201.zip`,
       extract it and build.
    3. Else download from SNOMED MLDS (Affiliate licence required) using
       `:username` and `:password` params passed to `clj -X:build-db`,
       then build. `:password` is the path to a file containing the
       password (convenient for CI secret mounting).

  See CLAUDE.md for credential setup."
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [com.eldrix.hermes.core :as hermes]
            [com.eldrix.hermes.download :as hermes-download])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.util.zip ZipInputStream)))

(def pinned-version       "20250201")
(def pinned-release-date  "2025-02-01")            ;; ISO 8601 — MLDS lookup
(def pinned-mlds-package  "ihtsdo.mlds/167")       ;; SNOMED CT International Edition
(def pinned-db-path       (str ".hades/snomed-intl-" pinned-version ".db"))
(def pinned-zip-path      (str ".hades/snomed-int-"  pinned-version ".zip"))

(defn- missing-db-error []
  (ex-info
    (str "Pinned SNOMED CT DB not found at " pinned-db-path ".\n"
         "Run `clj -X:build-db` to provision it (uses a local release zip at "
         pinned-zip-path ", or downloads from MLDS with "
         ":username / :password params). See CLAUDE.md.")
    {:expected-db   pinned-db-path
     :expected-zip  pinned-zip-path
     :version       pinned-version}))

(defn assert-pinned-db!
  "Return the path to the pinned SNOMED CT DB, throwing a clear error if it
  doesn't exist. Use this from tests/conformance — provisioning is a separate
  step (`clj -X:build-db`)."
  []
  (let [db-file (io/file pinned-db-path)]
    (when-not (.exists db-file)
      (throw (missing-db-error)))
    pinned-db-path))

;; ---------------------------------------------------------------------------
;; Provisioning — invoked by `clj -X:build-db`, not by tests/conformance.
;; ---------------------------------------------------------------------------

(defn- delete-recursively!
  [^java.io.File f]
  (when (.isDirectory f)
    (run! delete-recursively! (.listFiles f)))
  (.delete f))

(defn- extract-zip!
  "Extract `zip-file` into `out-dir`, preserving directory structure."
  [^java.io.File zip-file ^java.io.File out-dir]
  (with-open [zis (ZipInputStream. (io/input-stream zip-file))]
    (loop []
      (when-let [entry (.getNextEntry zis)]
        (let [out (io/file out-dir (.getName entry))]
          (if (.isDirectory entry)
            (.mkdirs out)
            (do (.mkdirs (.getParentFile out))
                (with-open [os (io/output-stream out)]
                  (io/copy zis os)))))
        (recur)))))

(defn- download-pinned-source!
  "Download the pinned SNOMED International release via MLDS. Returns the
  path of the extracted RF2 directory that hermes' downloader produced.

  `username` / `password` come from `:build-db` params; `password` is the
  path to a file containing the password (mirrors hades' `install` CLI)."
  [username password]
  (when-not (and username password)
    (throw (ex-info
             (str "Cannot build pinned DB: no local zip and MLDS credentials missing.\n"
                  "Either place a release zip at " pinned-zip-path ",\n"
                  "or run:\n"
                  "  clj -X:build-db :username '\"your-mlds-username\"' :password '\"path/to/password-file\"'\n"
                  "See CLAUDE.md.")
             {:version pinned-version})))
  (log/info "Downloading SNOMED International" pinned-release-date "via MLDS"
            {:package pinned-mlds-package})
  (let [^java.nio.file.Path p (hermes-download/download
                                pinned-mlds-package
                                {:username     username
                                 :password     password
                                 :release-date pinned-release-date})]
    (str p)))

(defn build-pinned-db!
  "Provision the canonical pinned SNOMED CT DB. Intended as the exec-fn for
  `clj -X:build-db`. No-op if the DB already exists.

  Params (optional, only needed for the download path):
    :username — SNOMED MLDS username
    :password — path to a file containing the MLDS password

  Sources in order of preference: existing DB → local zip at
  `.hades/snomed-int-20250201.zip` → MLDS download."
  [{:keys [username password]}]
  (let [db-file (io/file pinned-db-path)]
    (if (.exists db-file)
      (do (log/info "Pinned SNOMED DB already present" {:path pinned-db-path})
          pinned-db-path)
      (let [zip-file (io/file pinned-zip-path)
            [source-dir cleanup]
            (if (.exists zip-file)
              (let [tmp (.toFile (Files/createTempDirectory
                                   "hades-snomed-extract-"
                                   (into-array FileAttribute [])))]
                (log/info "Extracting" (.getName zip-file) "→" (str tmp))
                (extract-zip! zip-file tmp)
                [(str tmp) #(delete-recursively! tmp)])
              (let [p (download-pinned-source! username password)
                    f (io/file p)]
                [p #(delete-recursively! f)]))]
        (try
          (log/info "Building Hermes DB at" pinned-db-path)
          (hermes/import-snomed pinned-db-path [source-dir])
          (hermes/index pinned-db-path)
          (hermes/compact pinned-db-path)
          (log/info "SNOMED pinned DB ready" {:path pinned-db-path})
          pinned-db-path
          (finally (cleanup)))))))
