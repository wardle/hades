(ns com.eldrix.hades.impl.sqlite.provider-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [com.eldrix.hades.impl.index.sqlite :as sqlite-index]
            [com.eldrix.hades.impl.loaders.loinc :as loinc]
            [com.eldrix.hades.impl.protocols :as protos]
            [com.eldrix.hades.impl.sqlite.provider :as sqlite-provider])
  (:import (java.io File)))

(def fixture-root "test/resources/loinc-fixture")

(defn- new-temp-path []
  (let [^File f (File/createTempFile "hades-provider-test" ".db")]
    (.delete f)
    (.getPath f)))

(defn- delete-quietly [path]
  (let [^File f (io/file path)]
    (when (.exists f) (.delete f))))

(defn- build-fixture-db [path]
  (sqlite-index/import-fhir-data path
    (loinc/stream-release fixture-root {:version "2.82"})
    {:loader-type "loinc-csv"})
  (sqlite-index/index! path))

(deftest providers-enumerate-from-fixture
  (let [path (new-temp-path)]
    (try
      (build-fixture-db path)
      (let [{:keys [codesystem valueset conceptmap]}
            (sqlite-provider/open-providers path)]
        (testing "one catalogue impl per resource type"
          (is (some? codesystem))
          (is (some? valueset))
          (is (some? conceptmap)))
        (testing "cs-metadata enumerates CodeSystems"
          (let [meta (protos/cs-metadata codesystem)]
            (is (= 1 (count meta)))
            (is (= "http://loinc.org" (:url (first meta))))
            (is (= "2.82" (:version (first meta))))))
        (testing "vs-metadata enumerates ValueSets"
          (is (= 2 (count (protos/vs-metadata valueset)))))
        (testing "cm-metadata enumerates ConceptMaps"
          (is (= 1 (count (protos/cm-metadata conceptmap))))))
      (finally (delete-quietly path)))))

(deftest cs-lookup-and-validate
  (let [path (new-temp-path)]
    (try
      (build-fixture-db path)
      (let [{:keys [codesystem]} (sqlite-provider/open-providers path)]
        (testing "cs-lookup hits a known code"
          (let [r (protos/cs-lookup codesystem
                    {:system "http://loinc.org" :code "718-7"})]
            (is (some? r))
            (is (= "Hemoglobin [Mass/volume] in Blood" (:display r)))
            (is (= :718-7 (:code r)))
            (let [props (->> (:properties r)
                             (filter #(= :STATUS (:code %)))
                             first)]
              (is (= :ACTIVE (:value props))))))
        (testing "cs-lookup returns a not-found map for an unknown code"
          (let [r (protos/cs-lookup codesystem
                    {:system "http://loinc.org" :code "doesnt-exist"})]
            (is (true? (:not-found r)))
            (is (= :unknown-code (:not-found-reason r)))))
        (testing "cs-validate-code green"
          (let [r (protos/cs-validate-code codesystem
                    {:system "http://loinc.org" :code "2160-0"})]
            (is (true? (:result r)))
            (is (= "Creatinine [Mass/volume] in Serum or Plasma" (:display r)))))
        (testing "cs-validate-code red on unknown"
          (let [r (protos/cs-validate-code codesystem
                    {:system "http://loinc.org" :code "999-X"})]
            (is (false? (:result r)))
            (is (= "code-invalid" (-> r :issues first :type))))))
      (finally (delete-quietly path)))))

(deftest cs-find-matches-text-search
  (let [path (new-temp-path)]
    (try
      (build-fixture-db path)
      (let [{:keys [codesystem]} (sqlite-provider/open-providers path)]
        (testing "FTS text query matches display"
          (let [r (protos/cs-find-matches codesystem
                    {:system "http://loinc.org" :text "Hemoglobin"})
                codes (set (map :code (:concepts r)))]
            (is (contains? codes "718-7"))
            (is (contains? codes "4548-4"))
            (is (not (contains? codes "2160-0")))))
        (testing "FTS tokens are AND-ed"
          (let [r (protos/cs-find-matches codesystem
                    {:system "http://loinc.org" :text "Creatinine Serum"})
                codes (set (map :code (:concepts r)))]
            (is (contains? codes "2160-0"))
            (is (not (contains? codes "718-7")))))
        (testing "blank text returns whole CodeSystem (subject to active-only)"
          (let [r (protos/cs-find-matches codesystem
                    {:system "http://loinc.org" :text ""})]
            (is (>= (count (:concepts r)) 8)))))
      (finally (delete-quietly path)))))

(deftest cs-find-matches-filters
  (let [path (new-temp-path)]
    (try
      (build-fixture-db path)
      (let [{:keys [codesystem]} (sqlite-provider/open-providers path)]
        (testing "= on a property"
          (let [r (protos/cs-find-matches codesystem
                    {:system "http://loinc.org"
                     :filters [{:property "CLASS" :op "=" :value "CHEM"}]})
                codes (set (map :code (:concepts r)))]
            (is (contains? codes "2160-0"))
            (is (contains? codes "2345-7"))
            (is (not (contains? codes "718-7")))))
        (testing "in on a property"
          (let [r (protos/cs-find-matches codesystem
                    {:system "http://loinc.org"
                     :filters [{:property "CLASS" :op "in" :value "CHEM,HEM/BC"}]})
                codes (set (map :code (:concepts r)))]
            (is (contains? codes "2160-0"))
            (is (contains? codes "718-7"))))
        (testing "exists on a property"
          (let [r (protos/cs-find-matches codesystem
                    {:system "http://loinc.org"
                     :filters [{:property "STATUS" :op "exists" :value "true"}]})]
            (is (pos? (count (:concepts r))))))
        (testing "= on c.code direct column"
          (let [r (protos/cs-find-matches codesystem
                    {:system "http://loinc.org"
                     :filters [{:property "code" :op "=" :value "718-7"}]})]
            (is (= 1 (count (:concepts r))))
            (is (= "718-7" (:code (first (:concepts r)))))))
        (testing "max-hits caps results"
          (let [r (protos/cs-find-matches codesystem
                    {:system "http://loinc.org" :max-hits 2})]
            (is (= 2 (count (:concepts r))))))
        (testing "unknown system → empty"
          (let [r (protos/cs-find-matches codesystem
                    {:system "http://nope.example/cs" :text "hemoglobin"})]
            (is (= [] (:concepts r))))))
      (finally (delete-quietly path)))))

(deftest cs-find-matches-active-only-and-text
  (let [path (new-temp-path)]
    (try
      (build-fixture-db path)
      (let [{:keys [codesystem]} (sqlite-provider/open-providers path)]
        (testing "text + filter combine (LOINC fixture has DEPRECATED row 1009-0)"
          (let [r (protos/cs-find-matches codesystem
                    {:system "http://loinc.org"
                     :text "antiglobulin"})
                codes (set (map :code (:concepts r)))]
            (is (contains? codes "1009-0")))))
      (finally (delete-quietly path)))))

(defn- build-multilang-db [path]
  ;; Synthetic CodeSystem with English/French/Welsh designations on one
  ;; concept and English-only on another, for displayLanguage tests.
  (sqlite-index/build! path
    [{:type :codesystem-meta
      :url "http://example.org/cs/colours"
      :version "1.0"
      :status "active"
      :content "complete"
      :name "Colours"
      :title "Colours CS"}
     {:type :concept
      :system "http://example.org/cs/colours" :version "1.0"
      :code "red" :display "Red"
      :designations [{:value "Red"   :language :en}
                     {:value "Rouge" :language :fr}
                     {:value "Coch"  :language :cy}]}
     {:type :concept
      :system "http://example.org/cs/colours" :version "1.0"
      :code "blue" :display "Blue"
      :designations [{:value "Blue" :language :en}]}]
    {:loader-type "synthetic-multilang"})
  (sqlite-index/index! path))

(deftest cs-lookup-respects-displayLanguage
  (let [path (new-temp-path)]
    (try
      (build-multilang-db path)
      (let [{:keys [codesystem]} (sqlite-provider/open-providers path)]
        (testing "displayLanguage selects matching designation as display"
          (let [r (protos/cs-lookup codesystem
                    {:system "http://example.org/cs/colours" :code "red"
                     :displayLanguage "fr"})]
            (is (= "Rouge" (:display r)))))
        (testing "displayLanguage falls back to primary display when no match"
          (let [r (protos/cs-lookup codesystem
                    {:system "http://example.org/cs/colours" :code "red"
                     :displayLanguage "de"})]
            (is (= "Red" (:display r)))))
        (testing "no displayLanguage → primary display"
          (let [r (protos/cs-lookup codesystem
                    {:system "http://example.org/cs/colours" :code "red"})]
            (is (= "Red" (:display r)))))
        (testing "language with quality factors picks highest match"
          (let [r (protos/cs-lookup codesystem
                    {:system "http://example.org/cs/colours" :code "red"
                     :displayLanguage "de;q=1.0,cy;q=0.5"})]
            (is (= "Coch" (:display r))))))
      (finally (delete-quietly path)))))

(defn- build-case-insensitive-db [path]
  (sqlite-index/build! path
    [{:type :codesystem-meta
      :url "http://example.org/cs/ci"
      :version "1.0"
      :status "active"
      :content "complete"
      :case-sensitive false}
     {:type :concept
      :system "http://example.org/cs/ci" :version "1.0"
      :code "FOO" :display "Foo"}]
    {:loader-type "synthetic-ci"})
  (sqlite-index/index! path))

(defn- build-case-sensitive-db [path]
  (sqlite-index/build! path
    [{:type :codesystem-meta
      :url "http://example.org/cs/cs"
      :version "1.0"
      :status "active"
      :content "complete"
      :case-sensitive true}
     {:type :concept
      :system "http://example.org/cs/cs" :version "1.0"
      :code "FOO" :display "Foo"}]
    {:loader-type "synthetic-cs"})
  (sqlite-index/index! path))

(deftest cs-lookup-respects-case-sensitivity
  (let [path (new-temp-path)]
    (try
      (build-case-insensitive-db path)
      (let [{:keys [codesystem]} (sqlite-provider/open-providers path)]
        (testing "case-insensitive CS resolves a different-case code"
          (let [r (protos/cs-lookup codesystem
                    {:system "http://example.org/cs/ci" :code "foo"})]
            (is (some? r))
            (is (= "Foo" (:display r)))))
        (testing "exact case still works"
          (let [r (protos/cs-lookup codesystem
                    {:system "http://example.org/cs/ci" :code "FOO"})]
            (is (= "Foo" (:display r))))))
      (finally (delete-quietly path))))
  (let [path (new-temp-path)]
    (try
      (build-case-sensitive-db path)
      (let [{:keys [codesystem]} (sqlite-provider/open-providers path)]
        (testing "case-sensitive CS rejects different-case code"
          (is (= :unknown-code
                 (:not-found-reason
                   (protos/cs-lookup codesystem
                     {:system "http://example.org/cs/cs" :code "foo"})))))
        (testing "exact case works"
          (is (some? (protos/cs-lookup codesystem
                       {:system "http://example.org/cs/cs" :code "FOO"})))))
      (finally (delete-quietly path)))))

(deftest cs-validate-code-case-insensitive-info-issue
  (let [path (new-temp-path)]
    (try
      (build-case-insensitive-db path)
      (let [{:keys [codesystem]} (sqlite-provider/open-providers path)]
        (testing "wrong-case code validates and surfaces a code-rule info issue"
          (let [r (protos/cs-validate-code codesystem
                    {:system "http://example.org/cs/ci" :code "foo"})]
            (is (true? (:result r)))
            (is (= :FOO (:normalized-code r)))
            (is (= "information" (-> r :issues first :severity)))
            (is (= "code-rule" (-> r :issues first :details-code)))))
        (testing "correct-case code: no case issue"
          (let [r (protos/cs-validate-code codesystem
                    {:system "http://example.org/cs/ci" :code "FOO"})]
            (is (true? (:result r)))
            (is (nil? (:normalized-code r)))
            (is (empty? (:issues r))))))
      (finally (delete-quietly path)))))

(deftest cs-validate-code-respects-displayLanguage
  (let [path (new-temp-path)]
    (try
      (build-multilang-db path)
      (let [{:keys [codesystem]} (sqlite-provider/open-providers path)]
        (testing "language-tagged designation is accepted as valid display"
          (let [r (protos/cs-validate-code codesystem
                    {:system "http://example.org/cs/colours" :code "red"
                     :display "Rouge" :displayLanguage "fr"})]
            (is (true? (:result r)))
            (is (= "Rouge" (:display r)))))
        (testing "wrong display in requested language fails"
          (let [r (protos/cs-validate-code codesystem
                    {:system "http://example.org/cs/colours" :code "red"
                     :display "Rojo" :displayLanguage "fr"})]
            (is (false? (:result r)))
            (is (= "invalid-display" (-> r :issues first :details-code)))))
        (testing "primary-language display still validates without displayLanguage"
          (let [r (protos/cs-validate-code codesystem
                    {:system "http://example.org/cs/colours" :code "red"
                     :display "Red"})]
            (is (true? (:result r))))))
      (finally (delete-quietly path)))))

(deftest cm-translate-against-loinc-mapto
  (let [path (new-temp-path)]
    (try
      (build-fixture-db path)
      (let [{:keys [conceptmap]} (sqlite-provider/open-providers path)
            cm-url (-> (protos/cm-metadata conceptmap) first :url)
            result (protos/cm-translate conceptmap
                     {:url cm-url
                      :code "1009-0"
                      :system "http://loinc.org"})]
        (is (true? (:result result)))
        (is (= 2 (count (:matches result))))
        (let [first-match (first (:matches result))]
          (is (= "http://loinc.org" (:system first-match)))
          (is (= "1007-4" (:code first-match)))
          (is (= "equivalent" (:equivalence first-match)))))
      (finally (delete-quietly path)))))

;; ---------------------------------------------------------------------------
;; Multi-version bare-URL selection — healthcare safety contract.
;;
;; A SQLite container can hold multiple `(url, version)` rows for the same
;; CodeSystem. When a bare-URL request arrives (no version specified), the
;; provider must deterministically return data from the latest version per
;; SemVer. The current implementation falls through to a `some` over a
;; PersistentHashMap (provider.clj:245-247) — iteration order is undefined,
;; so the version returned is non-deterministic. In a clinical context this
;; can silently expose retired displays / hierarchy edges.
;; ---------------------------------------------------------------------------

(defn- multi-version-fhir-data
  "Synthesise N versions of a CodeSystem at the same URL. Each version's
  concept C1 carries the version string in its display so we can detect
  which version was returned."
  [url versions]
  (mapcat (fn [v]
            [{:type :codesystem-meta :url url :version v
              :status "active" :content "complete"
              :name "Multi" :title "Multi-version CS"}
             {:type :concept :system url :version v
              :code "C1" :display (str "v=" v)}])
          versions))

(defn- build-versions-db [path url versions]
  (sqlite-index/build! path
    (multi-version-fhir-data url versions)
    {:loader-type "synthetic-multi-version"})
  (sqlite-index/index! path))

(deftest cs-lookup-bare-url-picks-latest-semver
  (testing "Healthcare safety: with multiple versions in one container, a bare-URL $lookup MUST return the latest SemVer version. Currently fails — provider's lookup-entry falls through to (some …) over a hash-map, yielding whichever entry the iterator hits first regardless of version. Tested with several version sets so the bug is exposed regardless of incidental hash order."
    (doseq [[url versions expected]
            [["http://example.org/cs/a" ["1.0.0" "2.0.0"] "2.0.0"]
             ["http://example.org/cs/b" ["1.2.0" "1.10.0"] "1.10.0"]
             ["http://example.org/cs/c" ["1.0.0" "1.0.1" "1.0.2" "2.0.0"] "2.0.0"]
             ["http://example.org/cs/d" ["3.0.0" "1.0.0" "2.0.0"] "3.0.0"]
             ["http://example.org/cs/e" ["1.0.0" "10.0.0" "2.0.0"] "10.0.0"]]]
      (let [path (new-temp-path)]
        (try
          (build-versions-db path url versions)
          (let [{:keys [codesystem]} (sqlite-provider/open-providers path)
                r (protos/cs-lookup codesystem {:system url :code "C1"})]
            (is (= expected (:version r))
                (str "for " url " with versions " (vec versions)
                     " the bare-URL lookup must return " expected
                     " (the SemVer-latest)"))
            (is (= (str "v=" expected) (:display r))
                "the returned version's content must match"))
          (finally (delete-quietly path)))))))
