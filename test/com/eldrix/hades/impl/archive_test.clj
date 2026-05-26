(ns com.eldrix.hades.impl.archive-test
  "Unit tests for `impl/archive`: recognition, extraction (tar + zip),
  source resolution, and the zip-slip guard."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.test :refer [deftest is testing]]
            [com.eldrix.hades.impl.archive :as archive])
  (:import (java.io File)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.util.zip ZipEntry ZipOutputStream)))

(defn- temp-dir ^File []
  (.toFile (Files/createTempDirectory "archive-test" (make-array FileAttribute 0))))

(defn- delete-tree [^File f]
  (when (.isDirectory f) (run! delete-tree (.listFiles f)))
  (.delete f))

(defn- make-tgz
  "Build `<dir>/<name>` (a .tgz) containing `package/CodeSystem-x.json`."
  [^File dir name]
  (let [staging (io/file dir "staging")
        pkg     (io/file staging "package")]
    (.mkdirs pkg)
    (spit (io/file pkg "CodeSystem-x.json") "{\"resourceType\":\"CodeSystem\"}")
    (let [{:keys [exit err]} (sh/sh "tar" "czf" name "-C" (.getPath staging) "package"
                                    :dir (.getPath dir))]
      (when (not= 0 exit) (throw (ex-info (str "tar failed: " err) {:exit exit}))))
    (delete-tree staging)
    (io/file dir name)))

(defn- make-zip
  "Build `<dir>/<name>` (a .zip) with the given `entries` map of
  entry-name → content string."
  [^File dir name entries]
  (let [zip (io/file dir name)]
    (with-open [out (ZipOutputStream. (io/output-stream zip))]
      (doseq [[entry-name content] entries]
        (.putNextEntry out (ZipEntry. entry-name))
        (io/copy content out)
        (.closeEntry out)))
    zip))

(deftest archive?-recognises-by-extension
  (let [dir (temp-dir)]
    (try
      (testing "recognised archive extensions on existing files"
        (doseq [n ["a.tgz" "a.tar.gz" "a.tar" "a.zip" "A.ZIP"]]
          (let [f (io/file dir n)]
            (spit f "x")
            (is (archive/archive? f) n))))
      (testing "non-archives and non-files are rejected"
        (let [json (io/file dir "x.json")]
          (spit json "{}")
          (is (not (archive/archive? json))))
        (is (not (archive/archive? dir)) "a directory is not an archive")
        (is (not (archive/archive? (io/file dir "missing.tgz"))) "a missing file is not an archive"))
      (finally (delete-tree dir)))))

(deftest extract-tgz-round-trips
  (let [dir (temp-dir)]
    (try
      (let [tgz  (make-tgz dir "pkg.tgz")
            dest (archive/extract-to-temp! tgz)]
        (try
          (is (.isFile (io/file dest "package" "CodeSystem-x.json")))
          (finally (archive/delete-recursively dest))))
      (finally (delete-tree dir)))))

(deftest extract-zip-round-trips
  (let [dir (temp-dir)]
    (try
      (let [zip  (make-zip dir "pkg.zip"
                           {"package/CodeSystem-x.json" "{\"resourceType\":\"CodeSystem\"}"
                            "package/nested/ValueSet-y.json" "{\"resourceType\":\"ValueSet\"}"})
            dest (archive/extract-to-temp! zip)]
        (try
          (is (.isFile (io/file dest "package" "CodeSystem-x.json")))
          (is (.isFile (io/file dest "package" "nested" "ValueSet-y.json"))
              "nested directories are created")
          (finally (archive/delete-recursively dest))))
      (finally (delete-tree dir)))))

(deftest extract-zip-rejects-zip-slip
  (let [dir (temp-dir)]
    (try
      (let [zip (make-zip dir "evil.zip" {"../escape.json" "{}"})]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"escapes destination"
              (archive/extract-to-temp! zip))))
      (finally (delete-tree dir)))))

(deftest resolve-sources-substitutes-archives-and-passes-through
  (let [dir (temp-dir)]
    (try
      (let [tgz   (make-tgz dir "pkg.tgz")
            plain (.getPath (io/file dir "some-db"))
            {:keys [paths temp-dirs]} (archive/resolve-sources [(.getPath tgz) plain])]
        (try
          (is (= 1 (count temp-dirs)) "one archive extracted")
          (is (= 2 (count paths)))
          (is (= (.getPath ^File (first temp-dirs)) (first paths))
              "archive path replaced by its extraction directory")
          (is (= plain (second paths)) "non-archive path passes through unchanged")
          (is (.isFile (io/file (first paths) "package" "CodeSystem-x.json")))
          (finally (run! archive/delete-recursively temp-dirs))))
      (finally (delete-tree dir)))))

(deftest resolve-sources-no-archives-is-noop
  (let [{:keys [paths temp-dirs]} (archive/resolve-sources ["/a/db" "/b/release"])]
    (is (= ["/a/db" "/b/release"] paths))
    (is (empty? temp-dirs))))
