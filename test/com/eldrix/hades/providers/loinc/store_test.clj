(ns com.eldrix.hades.providers.loinc.store-test
  (:require [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [com.eldrix.hades.providers.loinc.loader :as loader]
            [com.eldrix.hades.providers.loinc.store :as store]
            [next.jdbc :as jdbc]
            [next.jdbc.plan :as plan])
  (:import (java.io File)))

(def fixture-root "test/resources/loinc-fixture")

(defn- temp-db-path []
  (let [^File f (File/createTempFile "hades-loinc-store-test" ".db")]
    (.delete f)
    (.getPath f)))

(defn- delete-quietly [^String path]
  (let [^File f (io/file path)]
    (when (.exists f) (.delete f))))

(defn- stream-blocks [dir]
  (let [ch (async/chan 8)]
    (async/thread (loader/stream-release ch dir {:batch-size 7}))
    (async/<!! (async/into [] ch))))

(defn- import-fixture! [conn]
  (doseq [{:keys [type ex] :as block} (stream-blocks fixture-root)]
    (if (= type ::loader/error)
      (throw ex)
      (store/write-batch! conn block))))

(deftest writes-streamed-blocks-with-model-table-metadata
  (let [path (temp-db-path)]
    (try
      (let [ds (store/create! path)]
        (try
          (with-open [conn (jdbc/get-connection ds)]
            (import-fixture! conn)
            (testing "representative source rows are present"
              (is (= 1
                     (plan/select-one! conn :count ["SELECT COUNT(*) AS count FROM loinc"])))
              (is (= "Health informatics pioneer and the father of LOINC"
                     (plan/select-one! conn :long_common_name
                                       ["SELECT long_common_name FROM loinc WHERE loinc_num = '100000-9'"])))
              (is (= 1
                     (plan/select-one! conn :count ["SELECT COUNT(*) AS count FROM part_link_primary"])))
              (is (= 1
                     (plan/select-one! conn :count ["SELECT COUNT(*) AS count FROM part_link_supplementary"])))
              (is (= 21
                     (plan/select-one! conn :count ["SELECT COUNT(*) AS count FROM linguistic_variant_row"])))
              (is (= 1
                     (plan/select-one! conn :count
                                       ["SELECT COUNT(*) AS count FROM linguistic_variant_row WHERE variant_id = '24'"]))))
            (testing "row-level upsert is idempotent for the same release"
              (import-fixture! conn)
              (is (= 1
                     (plan/select-one! conn :count ["SELECT COUNT(*) AS count FROM loinc"])))
              (is (= 1
                     (plan/select-one! conn :count ["SELECT COUNT(*) AS count FROM part_link_primary"])))
              (is (= 1
                     (plan/select-one! conn :count ["SELECT COUNT(*) AS count FROM part_link_supplementary"])))
              (is (= 21
                     (plan/select-one! conn :count ["SELECT COUNT(*) AS count FROM linguistic_variant_row"])))))
          (finally
            (store/close! ds))))
      (finally
        (delete-quietly path)))))
