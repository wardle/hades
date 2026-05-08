(ns com.eldrix.hades.impl.sqlite.db-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [com.eldrix.hades.impl.sqlite.db :as db]
            [next.jdbc :as jdbc])
  (:import (java.io File)))

(defn- temp-db-path []
  (let [^File f (File/createTempFile "hades-sqlite-test" ".db")]
    (.delete f)
    (.getPath f)))

(defn- delete-quietly [^String path]
  (let [^File f (io/file path)]
    (when (.exists f) (.delete f))))

(deftest create-stamps-application-id-and-schema
  (let [path (temp-db-path)]
    (try
      (let [ds (db/create! path)]
        (with-open [conn (jdbc/get-connection ds)]
          (let [aid (-> (jdbc/execute-one! conn ["PRAGMA application_id"]) vals first)
                uv  (-> (jdbc/execute-one! conn ["PRAGMA user_version"])  vals first)]
            (is (= db/application-id aid))
            (is (= db/schema-version uv)))))
      (finally (delete-quietly path)))))

(deftest open-rejects-non-fhir-tx-sqlite-file
  (let [path (temp-db-path)]
    (try
      ;; Build a plain SQLite file with no FHIR-tx stamp.
      (let [ds (jdbc/get-datasource {:dbtype "sqlite" :dbname path})]
        (with-open [conn (jdbc/get-connection ds)]
          (jdbc/execute! conn ["CREATE TABLE noise (x INTEGER)"])))
      (let [ex (try (db/open path) nil
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex))
        (is (= :not-fhir-tx-db (:reason (ex-data ex)))))
      (finally (delete-quietly path)))))

(deftest list-resources-and-meta
  (let [path (temp-db-path)]
    (try
      (let [ds (db/create! path)]
        (with-open [conn (jdbc/get-connection ds)]
          (jdbc/execute! conn ["INSERT INTO tx_resource(resource_type, url, version, concept_count, imported_at)
                                VALUES ('CodeSystem','http://loinc.org','2.82',109325,'2026-05-02T07:00:00Z')"]))
        (db/write-meta! ds {:loader_type "loinc-csv"
                            :tx_schema_version "1"})
        (testing "list-resources reads the catalogue"
          (let [rows (db/list-resources ds)]
            (is (= 1 (count rows)))
            (is (= "http://loinc.org" (:url (first rows))))
            (is (= "2.82" (:version (first rows))))
            (is (= 109325 (:concept-count (first rows))))))
        (testing "read-meta reads what write-meta wrote"
          (let [m (db/read-meta ds)]
            (is (= "loinc-csv" (get m "loader_type")))
            (is (= "1" (get m "tx_schema_version"))))))
      (finally (delete-quietly path)))))

(deftest naming-system-roundtrip
  (let [path (temp-db-path)]
    (try
      (let [ds (db/create! path)]
        (db/add-naming-system! ds {:url "http://loinc.org"
                                    :name "LOINC"
                                    :status "active"
                                    :kind "codesystem"
                                    :id-type "oid"
                                    :value "2.16.840.1.113883.6.1"
                                    :preferred true})
        (testing "resolve-system returns canonical for known OID"
          (is (= "http://loinc.org"
                 (db/resolve-system ds "2.16.840.1.113883.6.1"))))
        (testing "resolve-system returns nil for unknown alias"
          (is (nil? (db/resolve-system ds "1.2.3.4.99"))))
        (testing "resolve-system strips urn:oid: prefix"
          (is (= "http://loinc.org"
                 (db/resolve-system ds "urn:oid:2.16.840.1.113883.6.1"))))
        (testing "resolve-system strips urn:uuid: prefix"
          (db/add-naming-system! ds {:url "http://example.org/cs"
                                      :id-type "uuid"
                                      :value "12345678-1234-1234-1234-123456789012"})
          (is (= "http://example.org/cs"
                 (db/resolve-system ds "urn:uuid:12345678-1234-1234-1234-123456789012"))))
        (testing "resolve-system on blank/nil is nil"
          (is (nil? (db/resolve-system ds nil)))
          (is (nil? (db/resolve-system ds ""))))
        (testing "add-naming-system! is idempotent"
          (db/add-naming-system! ds {:url "http://loinc.org"
                                      :id-type "oid"
                                      :value "2.16.840.1.113883.6.1"
                                      :preferred true})
          (is (= "http://loinc.org"
                 (db/resolve-system ds "2.16.840.1.113883.6.1")))))
      (finally (delete-quietly path)))))

(deftest open-an-existing-fhir-tx-file-roundtrips
  (let [path (temp-db-path)]
    (try
      (db/create! path)
      (let [ds (db/open path)]
        (is (some? ds))
        (is (vector? (db/list-resources ds))))
      (finally (delete-quietly path)))))

;; ---------------------------------------------------------------------------
;; `fhir-tx-db?` short-circuits via SQLite magic-header pre-check before
;; opening JDBC. Regression guard: the directory-walker can visit
;; multi-GB sparse LMDB blobs (Hermes' core.db / refsets.db) with names
;; that would otherwise be opened by sqlite-jdbc. The header check has
;; to reject them in microseconds without throwing.
;; ---------------------------------------------------------------------------

(deftest fhir-tx-db?-rejects-non-sqlite-without-jdbc
  (testing "a file whose first 16 bytes aren't SQLite magic returns false fast"
    (let [^File f (File/createTempFile "not-sqlite" ".db")]
      (try
        (spit f "this is plain text, definitely not a SQLite database header")
        (is (false? (db/fhir-tx-db? f))
            "non-SQLite content rejects without throwing")
        (finally (.delete f))))))

(deftest fhir-tx-db?-rejects-empty-file
  (let [^File f (File/createTempFile "empty-sqlite" ".db")]
    (try
      ;; .createTempFile leaves the file zero-length; .read returns -1
      ;; on the first call so the magic comparison must short-circuit.
      (is (false? (db/fhir-tx-db? f)))
      (finally (.delete f)))))

(deftest fhir-tx-db?-rejects-tiny-file
  (let [^File f (File/createTempFile "tiny" ".db")]
    (try
      ;; Less than 16 bytes — too short to even match the magic.
      (spit f "abc")
      (is (false? (db/fhir-tx-db? f)))
      (finally (.delete f)))))

(deftest fhir-tx-db?-rejects-plain-sqlite
  (testing "a plain SQLite file (correct magic, wrong application_id) returns false"
    (let [path (temp-db-path)]
      (try
        (let [ds (jdbc/get-datasource {:dbtype "sqlite" :dbname path})]
          (with-open [conn (jdbc/get-connection ds)]
            (jdbc/execute! conn ["CREATE TABLE noise (x INTEGER)"])))
        (is (false? (db/fhir-tx-db? (io/file path))))
        (finally (delete-quietly path))))))

(deftest fhir-tx-db?-accepts-stamped-file
  (let [path (temp-db-path)]
    (try
      (let [ds (db/create! path)]
        (db/close! ds))
      (is (true? (db/fhir-tx-db? (io/file path))))
      (finally (delete-quietly path)))))
