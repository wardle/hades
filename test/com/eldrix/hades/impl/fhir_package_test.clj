(ns com.eldrix.hades.impl.fhir-package-test
  "Unit tests for `impl/fhir-package` that don't require network access:
  semver-aware version ordering and the idempotent / blank-version
  branches of `download!`. The HTTP+tar happy path is exercised
  end-to-end by the CI `build-data` job."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [com.eldrix.hades.impl.fhir-package :as fp])
  (:import (java.io File)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- make-temp-dir ^File []
  (.toFile (Files/createTempDirectory "fp-test" (into-array FileAttribute []))))

(defn- delete-tree [^File f]
  (when (.isDirectory f)
    (run! delete-tree (.listFiles f)))
  (.delete f))

(deftest list-versions-orders-numeric-newest-first
  (testing "purely numeric semver components sort newest-first"
    (let [stub {:name     "hl7.test.dummy"
                :versions {(keyword "1.0.2") {:fhirVersion "1.0.2"}
                           (keyword "3.0.2") {:fhirVersion "3.0.2"}
                           (keyword "4.0.1") {:fhirVersion "4.0.1"}
                           (keyword "5.0.0") {:fhirVersion "5.0.0"}}}]
      (with-redefs [fp/metadata (fn [& _] stub)]
        (let [vs (fp/list-versions "hl7.test.dummy")]
          (is (= ["5.0.0" "4.0.1" "3.0.2" "1.0.2"]
                 (mapv :version vs)))
          (is (every? :fhir-version vs)))))))

(deftest list-versions-orders-prerelease-correctly
  (testing "SemVer 2.0.0 §11: pre-release versions sort *before* their numeric peers, and a stable release sorts above pre-releases of the same precedence. Currently fails — `semver-key` in impl/fhir_package.clj relies on Clojure's vector compareTo, which compares lengths first so [4 0 0 \"rc1\"] > [5 0 0]. Blocks commit until fixed."
    (let [stub {:versions {(keyword "1.0.2")     {:fhirVersion "1.0.2"}
                           (keyword "3.0.2")     {:fhirVersion "3.0.2"}
                           (keyword "4.0.0-rc1") {:fhirVersion "4.0.0"}
                           (keyword "4.0.1")     {:fhirVersion "4.0.1"}
                           (keyword "5.0.0")     {:fhirVersion "5.0.0"}}}]
      (with-redefs [fp/metadata (fn [& _] stub)]
        (let [vs (fp/list-versions "hl7.test.dummy")]
          (is (= ["5.0.0" "4.0.1" "4.0.0-rc1" "3.0.2" "1.0.2"]
                 (mapv :version vs))))))))

(deftest list-versions-handles-multiple-fhir-versions
  (testing "fhirVersions array is joined into the displayed fhir-version"
    (let [stub {:versions {(keyword "1.0.0") {:fhirVersions ["4.0.1" "5.0.0"]}}}]
      (with-redefs [fp/metadata (fn [& _] stub)]
        (is (= "4.0.1, 5.0.0"
               (-> (fp/list-versions "x") first :fhir-version)))))))

(deftest download-blank-version-throws
  (testing "download! refuses an empty version rather than constructing nonsense paths"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"version required"
          (fp/download! "hl7.test.dummy" "" "/tmp/__fp-test-never-created")))))

(deftest download-idempotent-when-already-extracted
  (testing "if both <id>-<ver>.tgz and <id>-<ver>/ already exist, download! returns the package dir without HTTP or tar"
    (let [tmp (make-temp-dir)
          id  "hl7.test.dummy"
          ver "1.2.3"
          tag (str id "-" ver)
          tgz (io/file tmp (str tag ".tgz"))
          dst (io/file tmp tag)
          pkg (io/file dst "package")]
      (try
        (spit tgz "")
        (.mkdirs pkg)
        (let [result (fp/download! id ver tmp)]
          (is (= (.getCanonicalPath pkg) (.getCanonicalPath result))
              "returns the inner /package directory when present"))
        (finally
          (delete-tree tmp))))))

(deftest download-returns-extract-root-when-no-package-subdir
  (testing "if the extract has no inner /package dir, the extract root is returned"
    (let [tmp (make-temp-dir)
          id  "hl7.test.dummy"
          ver "9.9.9"
          tag (str id "-" ver)
          tgz (io/file tmp (str tag ".tgz"))
          dst (io/file tmp tag)]
      (try
        (spit tgz "")
        (.mkdirs dst)
        (let [result (fp/download! id ver tmp)]
          (is (= (.getCanonicalPath dst) (.getCanonicalPath result))))
        (finally
          (delete-tree tmp))))))
