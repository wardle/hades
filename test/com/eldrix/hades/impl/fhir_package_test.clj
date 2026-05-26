(ns com.eldrix.hades.impl.fhir-package-test
  "Unit tests for `impl/fhir-package` that don't require network access:
  semver-aware version ordering and the idempotent / blank-version
  branches of `download!`. The HTTP+tar happy path is exercised
  end-to-end by the CI `build-data` job."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
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

(deftest try-registries-falls-through-on-404-only
  (testing "a 404 falls through to the next registry"
    (is (= :second
           (fp/try-registries
            ["a" "b"]
            (fn [r] (if (= r "a")
                      (throw (ex-info "nope" {:status 404}))
                      :second))))))
  (testing "a non-404 failure propagates immediately, no fallthrough"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"boom"
          (fp/try-registries
           ["a" "b"]
           (fn [r] (if (= r "a")
                     (throw (ex-info "boom" {:status 500}))
                     :should-not-reach))))))
  (testing "404 on the final registry propagates (nothing left to try)"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"gone"
          (fp/try-registries
           ["only"]
           (fn [_] (throw (ex-info "gone" {:status 404}))))))))

(deftest merge-docs-unions-versions-and-recomputes-latest
  (testing "versions union across registries; latest is highest semver of the union"
    (let [a {:name "x" :description "from-a"
             :dist-tags {:latest "0.17.0"}
             :versions {(keyword "0.16.0") {:fhirVersion "4.0.1"}
                        (keyword "0.17.0") {:fhirVersion "4.0.1"}}}
          b {:name "x" :description "from-b"
             :dist-tags {:latest "0.24.0"}
             :versions {(keyword "0.17.0") {:fhirVersion "4.0.1"}
                        (keyword "0.24.0") {:fhirVersion "4.0.1"}}}
          merged (fp/merge-docs [a b])]
      (is (= #{"0.16.0" "0.17.0" "0.24.0"}
             (set (map name (keys (:versions merged))))))
      (is (= "0.24.0" (get-in merged [:dist-tags :latest])))
      (is (= "from-a" (:description merged))
          "first registry wins on shared top-level fields"))))

(deftest metadata-merges-across-registries
  (testing "metadata unions docs from every registry that carries the package"
    (with-redefs [fp/fetch-metadata
                  (fn [registry _id]
                    (case registry
                      "r1" {:dist-tags {:latest "0.17.0"}
                            :versions {(keyword "0.17.0") {}}}
                      "r2" {:dist-tags {:latest "0.24.0"}
                            :versions {(keyword "0.24.0") {}}}))]
      (let [m (fp/metadata "us.nlm.vsac" ["r1" "r2"])]
        (is (= "0.24.0" (get-in m [:dist-tags :latest])))
        (is (= #{"0.17.0" "0.24.0"} (set (map name (keys (:versions m))))))))))

(deftest metadata-throws-when-absent-everywhere
  (testing "metadata throws when no registry carries the package"
    (with-redefs [fp/fetch-metadata (fn [_ _] nil)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not found in any registry"
            (fp/metadata "no.such.pkg" ["r1" "r2"]))))))

(deftest download-blank-version-throws
  (testing "download! refuses an empty version rather than constructing nonsense paths"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"version required"
          (fp/download! "hl7.test.dummy" "" "/tmp/__fp-test-never-created")))))

(defn- make-package-tgz
  "Build a cached `<dir>/<tag>.tgz` containing one FHIR JSON resource under
  the usual `package/` layout."
  [^File dir tag]
  (let [staging (io/file dir "staging")
        pkg     (io/file staging "package")]
    (.mkdirs pkg)
    (spit (io/file pkg "CodeSystem-x.json") "{\"resourceType\":\"CodeSystem\"}")
    (let [{:keys [exit err]} (sh/sh "tar" "czf" (str tag ".tgz")
                                    "-C" (.getPath staging) "package"
                                    :dir (.getPath dir))]
      (when (not= 0 exit) (throw (ex-info (str "fixture tar failed: " err) {:exit exit}))))
    (delete-tree staging)))

(deftest download-caches-and-returns-tgz
  (testing "download! returns the cached tarball and does not extract it"
    (let [tmp (make-temp-dir)
          tag "hl7.test.dummy-1.0.0"]
      (try
        (make-package-tgz tmp tag)
        (let [^File result (fp/download! "hl7.test.dummy" "1.0.0" tmp)]
          (is (= (str tag ".tgz") (.getName result)))
          (is (.isFile result))
          (is (not (.exists (io/file tmp tag)))
              "the download cache holds only the tarball, no extracted directory"))
        (finally
          (delete-tree tmp))))))

(deftest download-skips-network-when-tgz-cached
  (testing "a cached tgz is returned without any network download"
    (let [tmp (make-temp-dir)
          tag "hl7.test.dummy-1.0.0"]
      (try
        (make-package-tgz tmp tag)
        (with-redefs [fp/try-registries (fn [& _] (throw (ex-info "network must not be touched" {})))]
          (is (.isFile ^File (fp/download! "hl7.test.dummy" "1.0.0" tmp))))
        (finally
          (delete-tree tmp))))))
