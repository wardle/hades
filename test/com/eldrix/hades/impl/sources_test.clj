(ns com.eldrix.hades.impl.sources-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [com.eldrix.hades.impl.sources :as sources]
            [com.eldrix.hades.impl.sqlite.db :as db]
            [next.jdbc])
  (:import (java.io File)))

(deftest detects-loinc-fixture
  (testing "loinc-fixture has the LoincTableCore marker"
    (is (= :loinc (sources/detect "test/resources/loinc-fixture")))))

(deftest detects-fhir-json-resources-dir
  (testing "fhir-resources/good has CodeSystem JSON files"
    (is (= :fhir-json (sources/detect "test/resources/fhir-resources/good")))))

(deftest detects-fhir-tx-sqlite-container
  (let [^File f (File/createTempFile "fhir-tx-detect" ".db")]
    (.delete f)
    (try
      (let [ds (db/create! (.getPath f))]
        (db/close! ds))
      (is (= :fhir-tx-db (sources/detect (.getPath f))))
      (finally (.delete f)))))

(deftest does-not-mistake-plain-sqlite-for-fhir-tx
  (let [^File f (File/createTempFile "plain-sqlite" ".db")]
    (.delete f)
    (try
      ;; Build a plain SQLite file with no FTRM header.
      (let [ds (next.jdbc/get-datasource {:dbtype "sqlite" :dbname (.getPath f)})]
        (with-open [conn (next.jdbc/get-connection ds)]
          (next.jdbc/execute! conn ["CREATE TABLE noise (x INTEGER)"])))
      (is (nil? (sources/detect (.getPath f))))
      (finally (.delete f)))))

(deftest unknown-path-returns-nil-and-detect!-throws
  (let [tmp (doto (java.io.File/createTempFile "empty" "")
              (.delete))
        d (java.io.File. (.getParentFile tmp) "definitely-not-a-release")]
    (.mkdir d)
    (try
      (is (nil? (sources/detect (.getPath d))))
      (is (thrown? clojure.lang.ExceptionInfo (sources/detect! (.getPath d))))
      (finally (.delete d)))))
