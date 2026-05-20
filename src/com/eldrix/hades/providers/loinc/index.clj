(ns com.eldrix.hades.providers.loinc.index
  "Derived indexes for native LOINC SQLite stores."
  (:require [clojure.java.io :as io]
            [com.eldrix.hades.providers.loinc.store :as store]
            [next.jdbc :as jdbc])
  (:import (java.sql Connection)))

(set! *warn-on-reflection* true)

(def ^:private add-indexes-resource
  "com/eldrix/hades/providers/loinc/add-indexes-v1.sql")

(def ^:private drop-indexes-resource
  "com/eldrix/hades/providers/loinc/drop-indexes-v1.sql")

(defn- read-resource [path]
  (slurp (io/resource path)))

(defn- with-bulk-pragmas! [^Connection conn f]
  (jdbc/execute! conn ["PRAGMA synchronous = OFF"])
  (jdbc/execute! conn ["PRAGMA temp_store = MEMORY"])
  (try
    (f)
    (finally
      (jdbc/execute! conn ["PRAGMA synchronous = NORMAL"]))))

(defn- rebuild-fts! [conn]
  (jdbc/execute! conn ["INSERT INTO loinc_fts(loinc_fts) VALUES('rebuild')"])
  (jdbc/execute! conn ["DELETE FROM loinc_variant_fts"])
  (jdbc/execute! conn ["INSERT INTO loinc_variant_fts
                        (loinc_num, variant_id, iso_language, iso_country,
                         long_common_name, linguistic_variant_display_name,
                         shortname, component, related_names_2)
                        SELECT r.loinc_num, r.variant_id,
                               c.iso_language, c.iso_country,
                               r.long_common_name,
                               r.linguistic_variant_display_name,
                               r.shortname, r.component, r.related_names_2
                        FROM linguistic_variant_row r
                        LEFT JOIN linguistic_variant_catalog c
                               ON c.id = r.variant_id"]))

(defn drop-indexes!
  [conn]
  (store/exec-script! conn (read-resource drop-indexes-resource)))

(defn add-indexes!
  [conn]
  (store/exec-script! conn (read-resource add-indexes-resource)))

(defn index!
  "Rebuild derived indexes for a native LOINC SQLite store."
  [db-path]
  (let [ds (store/open db-path)]
    (try
      (with-open [conn (jdbc/get-connection ds)]
        (with-bulk-pragmas! conn
          (fn []
            (jdbc/with-transaction [tx conn]
              (drop-indexes! tx)
              (add-indexes! tx)
              (rebuild-fts! tx))))
        (jdbc/execute! conn ["ANALYZE"]))
      {:db-path db-path}
      (finally
        (store/close! ds)))))
