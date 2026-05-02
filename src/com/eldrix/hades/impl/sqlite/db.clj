(ns com.eldrix.hades.impl.sqlite.db
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

  Datasources are pooled via HikariCP. SQLite's WAL mode permits one
  writer and many concurrent readers, so the pool is sized small (4
  by default) to match. Per-connection runtime pragmas
  (`foreign_keys = ON`, `journal_mode = WAL`) are installed via
  HikariCP's `connectionInitSql` so every pooled connection enforces
  them. The returned datasource is `Closeable` — callers MUST close
  it on shutdown."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [next.jdbc :as jdbc])
  (:import (com.zaxxer.hikari HikariConfig HikariDataSource)
           (java.io Closeable)))

(def application-id
  "Magic number stamped in the SQLite header; ASCII 'FTRM' = 0x4654524D
  ('FHIR TeRMinology container'). Distinct from any specific server's
  identity — third-party tools may read or write v1 files."
  0x4654524D)

(def schema-version 1)

(def ^:private schema-resource
  "com/eldrix/hades/sqlite/schema-v1.sql")

(defn- jdbc-spec [^String path]
  {:dbtype "sqlite" :dbname path})

(def ^:private default-pool-size
  "Default Hikari pool size. SQLite WAL serialises writers but allows
  concurrent readers, so a small pool is the right shape — 4 covers
  parallel query workloads without thrashing the OS file handles."
  4)

(def ^:private init-sql
  "Per-connection pragmas applied at handout. Equivalent to the
  apply-runtime-pragmas! function the unpooled path used to call.
  Joined with `;` because Hikari's connectionInitSql accepts one
  statement; SQLite-JDBC happily executes a `;`-delimited script."
  "PRAGMA foreign_keys = ON; PRAGMA journal_mode = WAL")

(defn- pooled-datasource ^HikariDataSource [^String path]
  (let [cfg (doto (HikariConfig.)
              (.setJdbcUrl (str "jdbc:sqlite:" path))
              (.setMaximumPoolSize (int default-pool-size))
              (.setMinimumIdle 1)
              (.setConnectionInitSql init-sql)
              (.setPoolName (str "fhir-tx-" (.hashCode ^String path))))]
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
              (let [i (.indexOf line "--")]
                (if (neg? i) line (subs line 0 i)))))
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

(defn- empty-file? [^java.io.File f]
  (or (not (.exists f)) (zero? (.length f))))

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

(defn close!
  "Close a pooled datasource. Required on shutdown — pool threads keep
  the JVM alive otherwise. Safe to call on any `Closeable` datasource."
  [ds]
  (when (instance? Closeable ds)
    (.close ^Closeable ds)))

(defn create!
  "Create (or initialise) a FHIR terminology container at `path`. Stamps
  `application_id` + `user_version` and applies the v1 schema. Idempotent
  on an already-stamped file (returns the datasource without re-applying
  DDL when the schema is current).

  Throws if `path` exists and is a non-FHIR-terminology SQLite database.
  Returns a pooled `HikariDataSource` — callers MUST `close!` it on
  shutdown.

  Schema bootstrap runs against an unpooled connection so that the
  `application_id` + `user_version` writes happen before any pooled
  connection observes the file. The pool is opened afterwards and
  applies the runtime pragmas to every handout."
  [^String path]
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
  a v1 file. Returns a pooled `HikariDataSource` — callers MUST
  `close!` it on shutdown."
  [^String path]
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
  rows from `tx_resource`. Used by the SQLite provider to publish its
  metadata at registration time."
  [ds]
  (mapv (fn [{:tx_resource/keys [resource_type url version concept_count imported_at]}]
          {:resource-type resource_type
           :url url
           :version (when-not (str/blank? version) version)
           :concept-count concept_count
           :imported-at imported_at})
        (jdbc/execute! ds ["SELECT resource_type, url, version, concept_count, imported_at
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

(defn resolve-system
  "Resolve an identifier (canonical URL, bare OID/URN, `urn:oid:`/
  `urn:uuid:` URN form, or other URI alias) to its canonical URL via
  the `naming_system_id` table. Returns the canonical `ns_url` when a
  row matches the supplied id (or its URN-stripped form), or nil. Blank
  input returns nil immediately."
  [ds id]
  (when-not (or (nil? id) (str/blank? id))
    (let [bare (strip-urn-prefix id)
          row (or (jdbc/execute-one! ds
                    ["SELECT ns_url FROM naming_system_id WHERE value = ?
                      ORDER BY (preferred IS NULL), preferred DESC LIMIT 1"
                     id])
                  (when (not= bare id)
                    (jdbc/execute-one! ds
                      ["SELECT ns_url FROM naming_system_id WHERE value = ?
                        ORDER BY (preferred IS NULL), preferred DESC LIMIT 1"
                       bare])))]
      (:naming_system_id/ns_url row))))

(defn add-naming-system!
  "Upsert a NamingSystem row plus a single identifier alias. `id-type`
  is one of `\"oid\"`, `\"uri\"`, `\"uuid\"`, `\"other\"`. Idempotent."
  [ds {:keys [url name status kind id-type value preferred]}]
  (with-open [conn (jdbc/get-connection ds)]
    (jdbc/with-transaction [tx conn]
      (jdbc/execute! tx
        ["INSERT INTO naming_system(url, name, status, kind, metadata)
          VALUES(?,?,?,?, NULL)
          ON CONFLICT(url) DO UPDATE SET
            name=COALESCE(excluded.name, naming_system.name),
            status=COALESCE(excluded.status, naming_system.status),
            kind=COALESCE(excluded.kind, naming_system.kind)"
         url name status kind])
      (jdbc/execute! tx
        ["INSERT INTO naming_system_id(ns_url, identifier_type, value, preferred)
          VALUES(?,?,?,?)
          ON CONFLICT(ns_url, identifier_type, value) DO UPDATE SET
            preferred=excluded.preferred"
         url id-type value (cond (true? preferred) 1 (false? preferred) 0 :else nil)]))))
