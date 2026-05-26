(ns com.eldrix.hades.providers.ftrm.provider-test
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [com.eldrix.hades.composite :as composite]
            [com.eldrix.hades.protocols :as protos]
            [com.eldrix.hades.protocols.result :as result]
            [com.eldrix.hades.providers.common.fhir-loader :as loaders-fhir]
            [com.eldrix.hades.providers.ftrm.db :as ftrm-db]
            [com.eldrix.hades.providers.ftrm.index :as ftrm-index]
            [com.eldrix.hades.providers.ftrm.provider :as ftrm-provider])
  (:import (java.io File)))

(defn- new-temp-path []
  (let [^File f (File/createTempFile "hades-provider-test" ".db")]
    (.delete f)
    (.getPath f)))

(defn- delete-quietly [path]
  (let [^File f (io/file path)]
    (when (.exists f) (.delete f))))


(defn- build-multilang-db [path]
  ;; Synthetic CodeSystem with English/French/Welsh designations on one
  ;; concept and English-only on another, for displayLanguage tests.
  (ftrm-index/build! path
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
  (ftrm-index/index! path))

(def stored-vs-data
  [{:type :valueset
    :url "http://example.org/vs/stored"
    :version "1.0"
    :metadata {"status" "active"}
    :compose {"include"
              [{"system" "http://example.org/cs/external"
                "concept" [{"code" "alpha" "display" "Alpha"}
                           {"code" "bravo" "display" "Bravo"
                            "designation" [{"language" "en"
                                            "value" "Bravo stored EN"}]}
                           {"code" "charlie" "display" "Charlie"}
                           {"code" "delta" "display" "Delta"}]}]}}])

(defn- build-stored-vs-db [path]
  (ftrm-index/build! path stored-vs-data {:loader-type "synthetic-stored-vs"})
  (ftrm-index/index! path))

(deftest cs-lookup-respects-displayLanguage
  (let [path (new-temp-path)]
    (try
      (build-multilang-db path)
      (let [{:keys [codesystem]} (ftrm-provider/open-providers path)]
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

(deftest stored-extensional-vs-expand-uses-stored-membership
  (let [path (new-temp-path)]
    (try
      (build-stored-vs-db path)
      (let [{:keys [valueset datasource]} (ftrm-provider/open-providers path)
            svc (composite/from-providers [valueset])]
        (try
          (testing "no displayLanguage pages stored membership and reports pre-page total"
            (let [{:keys [concepts total used-codesystems compose-pins]}
                  (protos/vs-expand valueset svc {:url "http://example.org/vs/stored"
                                                  :offset 1
                                                  :count 2})]
              (is (= 4 total))
              (is (= ["bravo" "charlie"] (mapv :code concepts)))
              (is (= ["Bravo" "Charlie"] (mapv :display concepts)))
              (is (= [{:uri "http://example.org/cs/external"}] used-codesystems))
              (is (empty? compose-pins))))
          (testing "displayLanguage is answered from embedded designations"
            (let [{:keys [concepts display-language]}
                  (protos/vs-expand valueset svc {:url "http://example.org/vs/stored"
                                                  :displayLanguage "en"
                                                  :offset 1
                                                  :count 1})]
              (is (= "en" display-language))
              (is (= ["Bravo stored EN"] (mapv :display concepts)))))
          (testing "wildcard displayLanguage does not create a display preference"
            (let [{:keys [concepts display-language]}
                  (protos/vs-expand valueset svc {:url "http://example.org/vs/stored"
                                                  :displayLanguage "*"
                                                  :offset 1
                                                  :count 1})]
              (is (nil? display-language))
              (is (= ["Bravo"] (mapv :display concepts)))))
          (testing "filter matches stored code and display before pagination"
            (let [{:keys [concepts total]}
                  (protos/vs-expand valueset svc {:url "http://example.org/vs/stored"
                                                  :filter "char"
                                                  :count 1})]
              (is (= 1 total))
              (is (= ["charlie"] (mapv :code concepts)))))
          (finally
            (ftrm-db/close! datasource))))
      (finally (delete-quietly path)))))

(deftest cs-expand*-supports-code-eq-filter
  (let [path (new-temp-path)]
    (try
      (build-multilang-db path)
      (let [{:keys [codesystem]} (ftrm-provider/open-providers path)
            r (protos/cs-expand* codesystem
                {:system "http://example.org/cs/colours"
                 :filters [{:property "code" :op "=" :value "red"}]})]
        (is (= ["red"] (mapv :code (:concepts r)))))
      (finally (delete-quietly path)))))

(defn- build-case-insensitive-db [path]
  (ftrm-index/build! path
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
  (ftrm-index/index! path))

(defn- build-case-sensitive-db [path]
  (ftrm-index/build! path
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
  (ftrm-index/index! path))

(deftest cs-lookup-respects-case-sensitivity
  (let [path (new-temp-path)]
    (try
      (build-case-insensitive-db path)
      (let [{:keys [codesystem]} (ftrm-provider/open-providers path)]
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
      (let [{:keys [codesystem]} (ftrm-provider/open-providers path)]
        (testing "case-sensitive CS rejects different-case code"
          (is (= :unknown-code
                 (:not-found-reason
                   (protos/cs-lookup codesystem
                     {:system "http://example.org/cs/cs" :code "foo"})))))
        (testing "exact case works"
          (is (some? (protos/cs-lookup codesystem
                       {:system "http://example.org/cs/cs" :code "FOO"})))))
	      (finally (delete-quietly path)))))

(deftest cs-metadata-advertises-case-sensitivity
  (let [path (new-temp-path)]
    (try
      (build-case-insensitive-db path)
      (let [{:keys [codesystem]} (ftrm-provider/open-providers path)
            meta (first (protos/cs-metadata codesystem {:url "http://example.org/cs/ci"}))]
        (is (= false (:case-sensitive meta))))
      (finally (delete-quietly path))))
  (let [path (new-temp-path)]
    (try
      (build-case-sensitive-db path)
      (let [{:keys [codesystem]} (ftrm-provider/open-providers path)
            meta (first (protos/cs-metadata codesystem {:url "http://example.org/cs/cs"}))]
        (is (= true (:case-sensitive meta))))
      (finally (delete-quietly path)))))

(deftest cs-validate-code-case-insensitive-info-issue
  (let [path (new-temp-path)]
    (try
      (build-case-insensitive-db path)
      (let [{:keys [codesystem]} (ftrm-provider/open-providers path)]
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
      (let [{:keys [codesystem]} (ftrm-provider/open-providers path)]
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
  (ftrm-index/build! path
    (multi-version-fhir-data url versions)
    {:loader-type "synthetic-multi-version"})
  (ftrm-index/index! path))

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
          (let [{:keys [codesystem]} (ftrm-provider/open-providers path)
                r (protos/cs-lookup codesystem {:system url :code "C1"})]
            (is (= expected (:version r))
                (str "for " url " with versions " (vec versions)
                     " the bare-URL lookup must return " expected
                     " (the SemVer-latest)"))
            (is (= (str "v=" expected) (:display r))
                "the returned version's content must match"))
          (finally (delete-quietly path)))))))

;; ---------------------------------------------------------------------------
;; Expansion-only ValueSet — round-tripped through the SQLite indexer.
;;
;; Parity contract with the in-memory provider: when an ingested
;; ValueSet has a baked `:expansion` and no `:compose`, both providers
;; must serve the baked entries from `vs-expand`. Today the SQLite
;; indexer drops `:expansion` entirely (no column in the schema) and
;; the provider falls through to `compose/expand-compose` with a nil
;; compose definition.
;; ---------------------------------------------------------------------------

(def ^:private baked-vs-fhir-map
  ;; Mirrors `in_memory_test/baked-vs-map`: same shape on both sides so
  ;; the parity contract is testable against identical input. Routed
  ;; through `loaders-fhir/resource->fhir-data` here so the full
  ;; loader → indexer → SQLite → provider pipeline is exercised.
  {"resourceType" "ValueSet"
   "url"          "http://example.com/baked-vs"
   "version"      "1.0"
   "name"         "BakedVS"
   "status"       "active"
   "expansion"    {"identifier" "urn:uuid:test-baked"
                   "timestamp"  "2026-01-01T00:00:00Z"
                   "total"      5
                   "contains"   [{"system"  "http://example.com/external-cs"
                                  "code"    "alpha"
                                  "display" "Alpha"}
                                 {"system"  "http://example.com/external-cs"
                                  "code"    "bravo"
                                  "display" "Bravo"}
                                 {"system"  "http://example.com/external-cs"
                                  "code"    "charlie"
                                  "display" "Charlie"}
                                 {"system"  "http://example.com/external-cs"
                                  "code"    "delta"
                                  "display" "Delta"}
                                 {"system"  "http://example.com/external-cs"
                                  "code"    "echo"
                                  "display" "Echo"}]}})

(defn- build-baked-vs-db [path]
  (ftrm-index/build! path
    (loaders-fhir/resource->fhir-data baked-vs-fhir-map :tx-resource)
    {:loader-type "synthetic-baked"})
  (ftrm-index/index! path))

(deftest expansion-only-vs-survives-sqlite-roundtrip
  (testing "synthesised compose for an expansion-only ValueSet survives SQLite round-trip"
    (let [path (new-temp-path)]
      (try
        (build-baked-vs-db path)
        (let [{:keys [valueset]} (ftrm-provider/open-providers path)
              r (protos/vs-resource valueset {:url "http://example.com/baked-vs"
                                              :version "1.0"})
              compose (:compose r)
              include (first (get compose "include"))]
          (is (some? compose) "synthesised compose is persisted via the existing compose column")
          (is (= 1 (count (get compose "include"))))
          (is (= "http://example.com/external-cs" (get include "system")))
          (is (= 5 (count (get include "concept")))))
        (finally (delete-quietly path))))))

(deftest expansion-only-vs-expand-via-sqlite
  (testing "vs-expand returns baked entries from a SQLite-backed ValueSet"
    (let [path (new-temp-path)]
      (try
        (build-baked-vs-db path)
        (let [{:keys [valueset]} (ftrm-provider/open-providers path)
              svc (composite/from-providers [valueset])
              {:keys [concepts total]}
              (protos/vs-expand valueset svc {:url "http://example.com/baked-vs"
                                              :version "1.0"})]
          (is (= 5 (count concepts)))
          (is (= 5 total))
          (is (= #{"alpha" "bravo" "charlie" "delta" "echo"}
                 (set (map :code concepts))))
          (is (every? #(= "http://example.com/external-cs" (:system %)) concepts)))
        (finally (delete-quietly path))))))

(deftest expansion-only-vs-offset-count-via-sqlite
  (testing "offset/count slice of baked expansion (parity with in-memory)"
    (let [path (new-temp-path)]
      (try
        (build-baked-vs-db path)
        (let [{:keys [valueset]} (ftrm-provider/open-providers path)
              svc (composite/from-providers [valueset])
              {:keys [concepts total]}
              (protos/vs-expand valueset svc {:url "http://example.com/baked-vs"
                                              :version "1.0"
                                              :offset 1 :count 2})]
          (is (= 2 (count concepts)))
          (is (= 5 total))
          (is (= ["bravo" "charlie"] (mapv :code concepts))))
        (finally (delete-quietly path))))))

(deftest expansion-only-vs-filter-via-sqlite
  (testing "filter narrows baked expansion (parity with in-memory)"
    (let [path (new-temp-path)]
      (try
        (build-baked-vs-db path)
        (let [{:keys [valueset]} (ftrm-provider/open-providers path)
              svc (composite/from-providers [valueset])
              {:keys [concepts]}
              (protos/vs-expand valueset svc {:url "http://example.com/baked-vs"
                                              :version "1.0"
                                              :filter "alp"})]
          (is (= ["alpha"] (mapv :code concepts))))
        (finally (delete-quietly path))))))

;; ---------------------------------------------------------------------------
;; CodeSystem.content precedence on duplicate (url, version) — SQLite ingest.
;;
;; A second `build!` call against the same DB and the same (url, version) is
;; the SQLite analogue of the in-memory "two source rows" case. The desired
;; semantics mirror the in-memory loader:
;;   * a `not-present` stub must never override a non-stub meta;
;;   * among same-rank rows, last-wins.
;; Concept rows continue to merge per-code via INSERT OR REPLACE / OR IGNORE.
;; ---------------------------------------------------------------------------

(def ^:private dup-cs-url "http://example.org/cs/dup")

(defn- write-cs-rows!
  "Write a CodeSystem (meta + concepts) into `path` via `build!`. Each call
  is its own transaction; calling repeatedly on the same path applies the
  upsert semantics under test."
  [path content concepts loader-tag]
  (ftrm-index/build! path
    (into [{:type :codesystem-meta
            :url dup-cs-url
            :version "1.0"
            :status "active"
            :content content
            :case-sensitive true
            :name "Dup"
            :title "Dup CS"}]
          (map (fn [{:keys [code display]}]
                 {:type :concept
                  :system dup-cs-url :version "1.0"
                  :code code :display display})
               concepts))
    {:loader-type loader-tag}))

(defn- meta-content-from-sqlite [path]
  (-> (ftrm-provider/open-providers path)
      :codesystem
      (protos/cs-metadata {})
      first
      :content))

(deftest sqlite-cs-stub-then-complete-test
  (testing "stub row written first, complete row second — final meta `complete`,
            concepts present"
    (let [path (new-temp-path)]
      (try
        (write-cs-rows! path "not-present" [] "stub-first")
        (write-cs-rows! path "complete" [{:code "A" :display "Alpha"}] "complete-second")
        (ftrm-index/index! path)
        (let [{:keys [codesystem]} (ftrm-provider/open-providers path)]
          (is (= "complete" (meta-content-from-sqlite path)))
          (is (= "Alpha" (:display (protos/cs-lookup codesystem
                                     {:system dup-cs-url :code "A"})))))
        (finally (delete-quietly path))))))

(deftest sqlite-cs-complete-then-stub-test
  (testing "complete row written first, stub row second — stub must NOT
            overwrite the complete meta. Concepts inserted by the first
            call survive (concept rows are not deleted on a later meta-only
            write)."
    (let [path (new-temp-path)]
      (try
        (write-cs-rows! path "complete" [{:code "A" :display "Alpha"}] "complete-first")
        (write-cs-rows! path "not-present" [] "stub-second")
        (ftrm-index/index! path)
        (let [{:keys [codesystem]} (ftrm-provider/open-providers path)]
          (is (= "complete" (meta-content-from-sqlite path))
              "non-stub meta must survive a later stub upsert")
          (is (= "Alpha" (:display (protos/cs-lookup codesystem
                                     {:system dup-cs-url :code "A"})))))
        (finally (delete-quietly path))))))

(deftest sqlite-cs-both-complete-last-wins-test
  (testing "two `complete` writes for same (url, version) — meta replaced
            in place; concept rows merge by (url, version, code) with
            per-code last-wins"
    (let [path (new-temp-path)]
      (try
        (write-cs-rows! path "complete" [{:code "A" :display "Alpha-1"}
                                         {:code "B" :display "Beta"}]
                        "complete-first")
        (write-cs-rows! path "complete" [{:code "A" :display "Alpha-2"}
                                         {:code "C" :display "Charlie"}]
                        "complete-second")
        (ftrm-index/index! path)
        (let [{:keys [codesystem]} (ftrm-provider/open-providers path)]
          (is (= "complete" (meta-content-from-sqlite path)))
          (is (= "Alpha-2" (:display (protos/cs-lookup codesystem
                                       {:system dup-cs-url :code "A"}))))
          (is (= "Beta"    (:display (protos/cs-lookup codesystem
                                       {:system dup-cs-url :code "B"}))))
          (is (= "Charlie" (:display (protos/cs-lookup codesystem
                                       {:system dup-cs-url :code "C"})))))
        (finally (delete-quietly path))))))

;; ---------------------------------------------------------------------------
;; Unknown-system / unknown-code contract
;; ---------------------------------------------------------------------------

(defn- build-basic-db [path]
  (ftrm-index/build! path
    [{:type :codesystem-meta
      :url "http://example.org/cs/basic" :version "1.0"
      :status "active" :content "complete"}
     {:type :concept
      :system "http://example.org/cs/basic" :version "1.0"
      :code "a" :display "A"}
     {:type :concept
      :system "http://example.org/cs/basic" :version "1.0"
      :code "b" :display "B"}]
    {:loader-type "synthetic-basic"})
  (ftrm-index/index! path))

(defn- build-supplement-db [path]
  (ftrm-index/build! path
    [{:type :codesystem-meta
      :url "http://example.org/cs/supp" :version "1.0"
      :status "active" :content "supplement"
      :supplements-target "http://example.org/cs/base|1.0"}
     {:type :concept
      :system "http://example.org/cs/supp" :version "1.0"
      :code "x" :display "X"}]
    {:loader-type "synthetic-supplement"})
  (ftrm-index/index! path))

(deftest cs-metadata-surfaces-supplements
  (let [path (new-temp-path)]
    (try
      (build-supplement-db path)
      (let [{:keys [codesystem]} (ftrm-provider/open-providers path)
            entry (->> (protos/cs-metadata codesystem nil)
                       (filter #(= "http://example.org/cs/supp" (:url %)))
                       first)]
        (is (= "supplement" (:content entry)))
        (is (= "http://example.org/cs/base|1.0" (:supplements entry))))
      (finally (delete-quietly path)))))

(deftest cs-subsumes-unknown-system-signals-error
  (let [path (new-temp-path)]
    (try
      (build-basic-db path)
      (let [{:keys [codesystem]} (ftrm-provider/open-providers path)
            r (protos/cs-subsumes codesystem
                {:systemA "http://example.org/cs/nope"
                 :systemB "http://example.org/cs/nope"
                 :codeA "a" :codeB "b"})]
        (is (not= "not-subsumed" (:outcome r)))
        (is (or (:x-unknown-system r)
                (= :unknown-system (:not-found-reason r))
                (seq (:issues r)))))
      (finally (delete-quietly path)))))

(deftest cs-subsumes-requires-codes-to-exist
  (let [path (new-temp-path)]
    (try
      (build-basic-db path)
      (let [{:keys [codesystem]} (ftrm-provider/open-providers path)
            r (protos/cs-subsumes codesystem
                {:systemA "http://example.org/cs/basic"
                 :systemB "http://example.org/cs/basic"
                 :version "1.0"
                 :codeA "bogus" :codeB "bogus"})]
        (is (not= "equivalent" (:outcome r))))
      (finally (delete-quietly path)))))

(deftest cs-validate-code-unknown-system-shape
  (let [path (new-temp-path)]
    (try
      (build-basic-db path)
      (let [{:keys [codesystem]} (ftrm-provider/open-providers path)
            r (protos/cs-validate-code codesystem
                {:system "http://example.org/cs/nope"
                 :code "a"})]
        (is (s/valid? ::result/validate r) (s/explain-str ::result/validate r))
        (is (or (:x-unknown-system r) (true? (:not-found r)))))
      (finally (delete-quietly path)))))
