(ns com.eldrix.hades.providers.ftrm.db
  "Open / create / verify a FHIR terminology SQLite container.

  File-level identity is encoded in the SQLite file header itself:

    PRAGMA application_id = 0x4654524D   ; ASCII 'FTRM'
    PRAGMA user_version   = 1            ; schema version

  The schema is provider-neutral: any FHIR terminology server can read
  or write a v1 file. `open` refuses to open a file whose
  `application_id` doesn't match — better to surface a clear \"not a
  FHIR terminology container\" error than to silently re-stamp an
  unrelated SQLite database. `create!` stamps a fresh file and applies
  the v1 DDL.

  Datasources are pooled via HikariCP — `journal_mode = WAL` permits
  many concurrent readers. The pool cap (`default-pool-size`) bounds
  concurrent in-flight SQLite reads. Per-connection runtime pragmas
  (`foreign_keys = ON`, `journal_mode = WAL`) are installed via
  HikariCP's `connectionInitSql` so every pooled connection enforces
  them. The returned datasource is `Closeable` — callers MUST close
  it on shutdown."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [next.jdbc :as jdbc])
  (:import (com.zaxxer.hikari HikariConfig HikariDataSource)
           (java.io File FileInputStream)
           (java.nio.charset StandardCharsets)
           (java.util Arrays)))

(set! *warn-on-reflection* true)

(def application-id
  "Magic number stamped in the SQLite header; ASCII 'FTRM' = 0x4654524D
  ('FHIR TeRMinology container'). Distinct from any specific server's
  identity — third-party tools may read or write v1 files."
  0x4654524D)

(def schema-version 1)


(def ^:private schema-resource
  "com/eldrix/hades/providers/ftrm/schema-v1.sql")

(defn- jdbc-spec [^String path]
  {:dbtype "sqlite" :dbname path})

(def ^:private default-pool-size
  "Default Hikari pool size: the cap on concurrent in-flight SQLite reads
  per database. WAL permits many concurrent readers, so the database isn't
  the limit — the host CPU is. The heavy reads (large `$expand`
  materialisation) are CPU-bound, so concurrency beyond the core count only
  oversubscribes the scheduler (measured: throughput flat from ~core-count
  to 48 connections). Size to the available processors, with a floor for
  tiny hosts."
  (max 4 (.availableProcessors (Runtime/getRuntime))))

(defn- pool-size
  "Hikari max pool size — normally `default-pool-size`. The
  `hades.ftrm.pool-size` system property overrides it: an UNDOCUMENTED
  internal escape hatch for benchmarking only, not public API and not for
  release notes — don't rely on it."
  []
  (or (some-> (System/getProperty "hades.ftrm.pool-size") Long/parseLong)
      default-pool-size))

(def ^:private init-sql
  "Per-connection pragmas applied at handout via Hikari's
  `connectionInitSql` (one `;`-delimited script; SQLite-JDBC runs it whole)."
  "PRAGMA foreign_keys = ON; PRAGMA journal_mode = WAL")

(defn- pooled-datasource ^HikariDataSource [^String path]
  (let [cfg (doto (HikariConfig.)
              (.setJdbcUrl (str "jdbc:sqlite:" path))
              (.setMaximumPoolSize (int (pool-size)))
              (.setMinimumIdle 1)
              ;; Load-shed at the pool boundary: under saturation surface a 503
              ;; instead of letting requests block on the 30 s default. Sits
              ;; above HikariCP's 5 s `validationTimeout` and well above any
              ;; plausible cold-cache + GC pause, so legitimate burst-queueing
              ;; isn't shed. A future benchmark will tune this from data.
              (.setConnectionTimeout (long 5000))
              (.setConnectionInitSql init-sql)
              ;; Pool name surfaces in HikariCP exception/log messages —
              ;; embed the file basename so saturation events are
              ;; attributable to a specific database.
              (.setPoolName (str "fhir-tx[" (.getName (io/file path)) "]")))]
    (HikariDataSource. cfg)))

;; ---------------------------------------------------------------------------
;; Pragma helpers
;; ---------------------------------------------------------------------------

(defn- read-pragma [conn pragma]
  (some-> (jdbc/execute-one! conn [(str "PRAGMA " pragma)])
          vals
          first))

(defn- set-pragma! [conn pragma value]
  (jdbc/execute! conn [(str "PRAGMA " pragma " = " value)]))

(defn- strip-line-comments [^String sql]
  ;; Drop `--` line comments before splitting. The schema's CHECK
  ;; expressions never embed `;`, so splitting on `;` is safe once
  ;; comments are gone.
  (->> (str/split-lines sql)
       (map (fn [^String line]
              (let [i (str/index-of line "--")]
                (if i (subs line 0 i) line))))
       (str/join "\n")))

(defn- exec-script! [conn ^String sql]
  ;; sqlite-jdbc executes one statement per call.
  (doseq [stmt (->> (strip-line-comments sql)
                    (#(str/split % #";"))
                    (map str/trim)
                    (remove str/blank?))]
    (jdbc/execute! conn [stmt])))

(defn- read-schema-sql []
  (slurp (io/resource schema-resource)))

;; ---------------------------------------------------------------------------
;; Identity check
;; ---------------------------------------------------------------------------

(defn- empty-file? [^File f]
  (or (not (.exists f)) (zero? (.length f))))

(def ^:private ^"[B" sqlite-magic
  "First 16 bytes of every SQLite 3 file: 'SQLite format 3' + NUL.
  Reading the header lets us reject non-SQLite files in a microsecond
  before reaching for JDBC."
  (.getBytes "SQLite format 3\u0000" StandardCharsets/UTF_8))

(defn- has-sqlite-header?
  "Cheap pre-filter: is the first 16 bytes of `f` SQLite's magic header?
  We use this before opening JDBC because the recogniser walk may visit
  multi-GB sparse LMDB blobs (Hermes' `core.db`/`refsets.db`) and
  opening every one as SQLite is wasteful even though the eventual
  PRAGMA check would reject them."
  [^File f]
  (with-open [is (FileInputStream. f)]
    (let [n (alength sqlite-magic)
          buf (byte-array n)
          read (.read is buf 0 n)]
      (and (= read n)
           (Arrays/equals buf ^"[B" sqlite-magic)))))

(defn fhir-tx-db?
  "True when `f` is a FHIR terminology SQLite container — i.e. its
  SQLite header is intact and the `application_id` PRAGMA matches the
  FTRM stamp.

  Header check first (cheap — one open + 16-byte read) so we don't
  open a JDBC connection on every file in a directory walk that
  happens to contain LMDB / arbitrary binary blobs. PRAGMA check
  second so any future change to how the stamp is written is honoured
  automatically.

  Throws on I/O errors (e.g. permission denied opening the file). The
  walker in `impl/sources` catches and logs at the recogniser
  boundary; non-walker callers should treat exceptions as 'unknown'."
  [^File f]
  (and (.isFile f)
       (has-sqlite-header? f)
       (let [ds (jdbc/get-datasource (jdbc-spec (.getPath f)))]
         (with-open [conn (jdbc/get-connection ds)]
           (= application-id
              (long (or (read-pragma conn "application_id") 0)))))))

(def fhir-tx-db-recogniser
  "Recognises a FHIR terminology SQLite container (FTRM). Returns no
  extra annotations on the cheap path; opening to count resources is
  left to the consumer."
  {:id          :fhir-tx-db
   :importable? false
   :database?   true
   :recognise   (fn [^File f _probe?]
                  (when (fhir-tx-db? f) {}))})

(defn- check-identity! [conn path]
  (let [aid (long (or (read-pragma conn "application_id") 0))]
    (when (not= aid application-id)
      (throw (ex-info (str "Not a FHIR terminology container (application_id "
                           (format "0x%X" aid) " ≠ "
                           (format "0x%X" application-id) "): " path)
                      {:reason :not-fhir-tx-db
                       :path path
                       :application-id aid})))
    (let [uv (long (or (read-pragma conn "user_version") 0))]
      (when (> uv schema-version)
        (throw (ex-info (str "FHIR terminology container schema v" uv
                             " is newer than supported (v" schema-version "): " path)
                        {:reason :schema-too-new
                         :path path :schema-version uv})))
      (when (< uv schema-version)
        (throw (ex-info (str "FHIR terminology container schema v" uv
                             " predates v" schema-version
                             " and migrations are not implemented yet: " path)
                        {:reason :schema-too-old
                         :path path :schema-version uv}))))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn create!
  "Create (or initialise) a FHIR terminology container at `path`. Stamps
  `application_id` + `user_version` and applies the v1 schema. Idempotent
  on an already-stamped file (returns the datasource without re-applying
  DDL when the schema is current).

  Throws if `path` exists and is a non-FHIR-terminology SQLite database.
  Returns a pooled `HikariDataSource` — `Closeable`, so callers must
  `.close` it (or manage it with `with-open`) on shutdown.

  Schema bootstrap runs against an unpooled connection so that the
  `application_id` + `user_version` writes happen before any pooled
  connection observes the file. The pool is opened afterwards and
  applies the runtime pragmas to every handout."
  ^HikariDataSource [^String path]
  (let [f (io/file path)
        fresh? (empty-file? f)]
    (when fresh?
      ;; Bootstrap: create a transient unpooled connection to stamp the
      ;; header. Hikari's pool would race with the schema apply.
      (let [boot (jdbc/get-datasource (jdbc-spec path))]
        (with-open [conn (jdbc/get-connection boot)]
          (exec-script! conn (read-schema-sql))
          (set-pragma! conn "application_id" application-id)
          (set-pragma! conn "user_version" schema-version))))
    (let [ds (pooled-datasource path)]
      (with-open [conn (jdbc/get-connection ds)]
        (check-identity! conn path))
      ds)))

(defn open
  "Open an existing FHIR terminology container. Throws if missing or not
  a v1 file. Returns a pooled `HikariDataSource` — `Closeable`, so
  callers must `.close` it (or manage it with `with-open`) on shutdown."
  ^HikariDataSource [^String path]
  (let [f (io/file path)]
    (when (empty-file? f)
      (throw (ex-info (str "FHIR terminology container does not exist or is empty: " path)
                      {:reason :missing :path path}))))
  (let [ds (pooled-datasource path)]
    (with-open [conn (jdbc/get-connection ds)]
      (check-identity! conn path))
    ds))

(defn list-resources
  "Return the seq of `{:resource-type :url :version :concept-count :imported-at}`
  rows from `tx_resource`."
  [ds]
  (into []
        (map (fn [{:tx_resource/keys [resource_type url version concept_count imported_at]}]
               {:resource-type resource_type
                :url url
                :version (when-not (str/blank? version) version)
                :concept-count concept_count
                :imported-at imported_at}))
        (jdbc/plan ds ["SELECT resource_type, url, version, concept_count, imported_at
                        FROM tx_resource
                        ORDER BY resource_type, url, version"])))

(defn read-meta
  "Return the `tx_meta` map (key → value)."
  [ds]
  (into {}
        (map (fn [{:tx_meta/keys [key value]}] [key value]))
        (jdbc/execute! ds ["SELECT key, value FROM tx_meta"])))

(defn write-meta!
  "Upsert keys into `tx_meta`."
  [ds m]
  (with-open [conn (jdbc/get-connection ds)]
    (jdbc/with-transaction [tx conn]
      (doseq [[k v] m]
        (jdbc/execute! tx ["INSERT INTO tx_meta(key, value) VALUES(?, ?)
                            ON CONFLICT(key) DO UPDATE SET value=excluded.value"
                           (name k) (str v)])))))

;; ---------------------------------------------------------------------------
;; NamingSystem
;; ---------------------------------------------------------------------------

(defn- strip-urn-prefix
  "FHIR clients commonly serialise an identifier as `urn:oid:<oid>` or
  `urn:uuid:<uuid>`. The `naming_system_id.value` column stores the
  bare identifier, so we normalise both URN forms to the bare value
  before lookup."
  [^String id]
  (cond
    (str/starts-with? id "urn:oid:")  (subs id 8)
    (str/starts-with? id "urn:uuid:") (subs id 9)
    :else id))

(defn resolve-identifier
  "Resolve an identifier (bare OID/URN, `urn:oid:`/`urn:uuid:` URN form,
  or other URI alias) to `{:url canonical :kind :codesystem|:valueset|
  :conceptmap}` via the `naming_system` tables, or nil when no row
  matches (the supplied id or its URN-stripped form). Blank input
  returns nil immediately. Version-blind — an identifier names identity."
  [ds id]
  (when-not (str/blank? id)
    ;; next.jdbc namespaces result keys by source table, ignoring SQL
    ;; `AS` aliases — hence `:naming_system/url` / `:naming_system/kind`.
    (let [bare (strip-urn-prefix id)
          q    (fn [v]
                 (jdbc/execute-one! ds
                   ["SELECT ns.url, ns.kind
                     FROM naming_system_id nsi
                     JOIN naming_system ns ON ns.url = nsi.ns_url
                     WHERE nsi.value = ?
                     ORDER BY (nsi.preferred IS NULL), nsi.preferred DESC LIMIT 1"
                    v]))
          row  (or (q id) (when (not= bare id) (q bare)))]
      (when-let [url (:naming_system/url row)]
        {:url url :kind (keyword (:naming_system/kind row))}))))

(defn upsert-naming-system-id!
  "Upsert a NamingSystem row plus one identifier alias using
  `connectable` (a datasource, connection, or transaction). Performs no
  transaction management — the caller owns the transaction boundary.
  `id-type` is one of `\"oid\"`, `\"uri\"`, `\"uuid\"`, `\"other\"`; `kind`
  defaults to `\"codesystem\"`. Idempotent."
  [connectable {:keys [url name status kind id-type value preferred]}]
  (jdbc/execute! connectable
    ["INSERT INTO naming_system(url, name, status, kind, metadata)
      VALUES(?,?,?,?, NULL)
      ON CONFLICT(url) DO UPDATE SET
        name=COALESCE(excluded.name, naming_system.name),
        status=COALESCE(excluded.status, naming_system.status),
        kind=COALESCE(excluded.kind, naming_system.kind)"
     url name status (or kind "codesystem")])
  (jdbc/execute! connectable
    ["INSERT INTO naming_system_id(ns_url, identifier_type, value, preferred)
      VALUES(?,?,?,?)
      ON CONFLICT(ns_url, identifier_type, value) DO UPDATE SET
        preferred=excluded.preferred"
     url id-type value (cond (true? preferred) 1 (false? preferred) 0 :else nil)]))

(defn vacuum!
  "Run `VACUUM` on the container, reclaiming free pages and reducing
  the on-disk size. Opens its own short-lived datasource."
  [^String path]
  (with-open [ds (open path)
              conn (jdbc/get-connection ds)]
    (jdbc/execute! conn ["VACUUM"])))

(defn container-status
  "Return a status map summarising container contents: file size,
  schema version, resource counts by type, total concept rows."
  [^String path]
  (with-open [ds (open path)]
    (let [resources (list-resources ds)
          by-type (group-by :resource-type resources)]
      {:path           path
       :file-size      (.length (io/file path))
       :schema-version (read-meta ds)
       :resources      {:CodeSystem (count (get by-type "CodeSystem" []))
                        :ValueSet   (count (get by-type "ValueSet"   []))
                        :ConceptMap (count (get by-type "ConceptMap" []))}
       :concept-count  (reduce + 0 (keep :concept-count resources))})))
