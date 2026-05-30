(ns com.eldrix.hades.providers.loinc.store
  "LOINC SQLite store: file-level identity, schema, and lifecycle.
  Per-connection pragmas (foreign_keys, synchronous, journal_mode) are
  the caller's responsibility."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [com.eldrix.hades.providers.loinc.model :as model]
            [next.jdbc :as jdbc])
  (:import (com.zaxxer.hikari HikariConfig HikariDataSource)
           (java.io Closeable File FileInputStream)
           (java.nio.charset StandardCharsets)
           (java.util Arrays)))

(set! *warn-on-reflection* true)

(def application-id
  "ASCII 'LOIN' = 0x4C4F494E."
  0x4C4F494E)

(def schema-version 1)

(def schema-resource
  "com/eldrix/hades/providers/loinc/schema-v1.sql")

(def default-pool-size 32)

(defn jdbc-spec [^String path]
  {:dbtype "sqlite" :dbname path})

(defn pooled-datasource ^HikariDataSource [^String path]
  (let [cfg (doto (HikariConfig.)
              (.setJdbcUrl (str "jdbc:sqlite:" path))
              (.setMaximumPoolSize (int default-pool-size))
              (.setMinimumIdle 1)
              (.setConnectionTimeout (long 5000))
              (.setPoolName (str "loinc[" (.getName (io/file path)) "]")))]
    (HikariDataSource. cfg)))

(defn read-pragma [conn pragma]
  (some-> (jdbc/execute-one! conn [(str "PRAGMA " pragma)])
          vals
          first))

(defn set-pragma! [conn pragma value]
  (jdbc/execute! conn [(str "PRAGMA " pragma " = " value)]))

(defn strip-line-comments [^String sql]
  (->> (str/split-lines sql)
       (map (fn [^String line]
              (let [i (str/index-of line "--")]
                (if i (subs line 0 i) line))))
       (str/join "\n")))

(defn exec-script! [conn ^String sql]
  (doseq [stmt (->> (strip-line-comments sql)
                    (#(str/split % #";"))
                    (map str/trim)
                    (remove str/blank?))]
    (jdbc/execute! conn [stmt])))

(defn read-schema-sql []
  (slurp (io/resource schema-resource)))

(defn empty-file? [^File f]
  (or (not (.exists f)) (zero? (.length f))))

(def ^"[B" sqlite-magic
  (.getBytes "SQLite format 3\u0000" StandardCharsets/UTF_8))

(defn has-sqlite-header? [^File f]
  (with-open [is (FileInputStream. f)]
    (let [n (alength sqlite-magic)
          buf (byte-array n)
          read (.read is buf 0 n)]
      (and (= read n)
           (Arrays/equals buf ^"[B" sqlite-magic)))))

(defn loinc-db?
  "True when `f` is a Hades LOINC SQLite store (SQLite header intact
  and `PRAGMA application_id` matches 'LOIN')."
  [^File f]
  (and (.isFile f)
       (has-sqlite-header? f)
       (let [ds (jdbc/get-datasource (jdbc-spec (.getPath f)))]
         (with-open [conn (jdbc/get-connection ds)]
           (= application-id
              (long (or (read-pragma conn "application_id") 0)))))))

(def loinc-db-recogniser
  "Recognises a native Hades LOINC SQLite store."
  {:id          :loinc-db
   :importable? false
   :database?   true
   :recognise   (fn [^File f _probe?]
                  (when (loinc-db? f) {}))})

(defn check-identity! [conn path]
  (let [aid (long (or (read-pragma conn "application_id") 0))]
    (when (not= aid application-id)
      (throw (ex-info (str "Not a LOINC store (application_id "
                           (format "0x%X" aid) " ≠ "
                           (format "0x%X" application-id) "): " path)
                      {:reason :not-loinc-db
                       :path path
                       :application-id aid}))))
  (let [uv (long (or (read-pragma conn "user_version") 0))]
    (when (> uv schema-version)
      (throw (ex-info (str "LOINC store schema v" uv
                           " is newer than supported (v" schema-version "): " path)
                      {:reason :schema-too-new
                       :path path :schema-version uv})))
    (when (< uv schema-version)
      (throw (ex-info (str "LOINC store schema v" uv
                           " predates v" schema-version
                           " and migrations are not implemented yet: " path)
                      {:reason :schema-too-old
                       :path path :schema-version uv})))))

(defn close!
  "Close a pooled datasource."
  [ds]
  (when (instance? Closeable ds)
    (.close ^Closeable ds)))

(defn upsert-meta!
  [conn k v]
  (jdbc/execute! conn ["INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)"
                       (name k) (str v)]))

(defn- quote-ident [s]
  (str "\"" (str/replace (name s) "\"" "\"\"") "\""))

(defn insert-rows!
  "Insert row vectors into `table`. `columns` are database column names in
  insertion order. Empty row collections are ignored."
  [conn table columns rows]
  (when-let [rows (seq rows)]
    (let [cols (mapv name columns)
          placeholders (str/join "," (repeat (count cols) "?"))
          sql (str "INSERT OR REPLACE INTO " (quote-ident table)
                   " (" (str/join "," (map quote-ident cols)) ") VALUES ("
                   placeholders ")")
          params (mapv vec rows)]
      (jdbc/execute-batch! conn sql params {}))))

(defn- apply-column-transforms
  [columns column-transforms row]
  (reduce-kv (fn [row column f]
               (if-let [idx (first (keep-indexed #(when (= column %2) %1) columns))]
                 (update row idx f)
                 row))
             row
             column-transforms))

(defn- block->table
  [{:keys [headings data] :as block} {:keys [column-transforms table-transform]}]
  (let [columns (mapv model/column-name headings)
        rows (mapv (fn [row] (apply-column-transforms columns column-transforms row))
                   data)]
    ((or table-transform (fn [_block columns rows]
                           {:columns columns :rows rows}))
     block columns rows)))

(defmulti write-batch!
  "Write a loader data block to its model-defined source table using
  row-level upsert."
  (fn [_conn block] (:type block)))

(defmethod write-batch! :default [conn {:keys [type] :as block}]
  (let [{:keys [table-name] :as file-def} (model/release-file type)
        {:keys [columns rows]} (block->table block file-def)]
    (insert-rows! conn table-name columns rows)))

(defn create!
  "Create (or open) a LOINC store at `path`. Stamps `application_id` +
  `user_version` and applies the v1 schema on a fresh file. Throws if
  `path` is a non-LOINC SQLite database. Returns a pooled
  `HikariDataSource` — callers MUST `close!` it on shutdown."
  [^String path]
  (let [f (io/file path)]
    (when (empty-file? f)
      (let [boot (jdbc/get-datasource (jdbc-spec path))]
        (with-open [conn (jdbc/get-connection boot)]
          (exec-script! conn (read-schema-sql))
          (set-pragma! conn "application_id" application-id)
          (set-pragma! conn "user_version" schema-version)))))
  (let [ds (pooled-datasource path)]
    (with-open [conn (jdbc/get-connection ds)]
      (check-identity! conn path))
    ds))

(defn open
  "Open an existing LOINC store. Throws if missing or not a v1 file."
  [^String path]
  (let [f (io/file path)]
    (when (empty-file? f)
      (throw (ex-info (str "LOINC store does not exist or is empty: " path)
                      {:reason :missing :path path}))))
  (let [ds (pooled-datasource path)]
    (with-open [conn (jdbc/get-connection ds)]
      (check-identity! conn path))
    ds))
