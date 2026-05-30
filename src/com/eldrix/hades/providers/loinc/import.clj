(ns com.eldrix.hades.providers.loinc.import
  "Thin LOINC release import orchestration."
  (:require [clojure.core.async :as async]
            [com.eldrix.hades.providers.loinc.loader :as loader]
            [com.eldrix.hades.providers.loinc.store :as store]
            [next.jdbc :as jdbc])
  (:import (java.time Instant)))

(defn- stream-blocks [dir opts]
  (let [ch (async/chan 8)]
    (async/thread (loader/stream-release ch dir opts))
    ch))

(defn import-release-into!
  "Import a LOINC release directory into an existing connection.
  Performs row-level upserts through `store/write-batch!` and returns
  row counts by loader type."
  ([conn dir] (import-release-into! conn dir nil))
  ([conn dir opts]
   (let [{:keys [version]} (loader/metadata dir)]
     (when-not version
       (throw (ex-info (str "Invalid LOINC distribution: " dir) {:dir dir})))
     (jdbc/with-transaction [tx conn]
       (let [ch (stream-blocks dir opts)]
         (loop [counts {}]
           (if-let [{:keys [type data ex] :as block} (async/<!! ch)]
             (if (= type ::loader/error)
               (throw ex)
               (do
                 (store/write-batch! tx block)
                 (recur (update counts type (fnil + 0) (count data)))))
             (do
               (store/upsert-meta! tx :loinc_version version)
               (store/upsert-meta! tx :schema_version store/schema-version)
               (store/upsert-meta! tx :imported_at (Instant/now))
               counts))))))))

(defn import-release!
  "Create/open a LOINC SQLite store at `db-path`, import `dir`, and close
  the datasource before returning row counts by loader type."
  ([db-path dir] (import-release! db-path dir nil))
  ([db-path dir opts]
   (let [ds (store/create! db-path)]
     (try
       (with-open [conn (jdbc/get-connection ds)]
         (import-release-into! conn dir opts))
       (finally
         (store/close! ds))))))
