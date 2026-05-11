(ns com.eldrix.hades.impl.composite-test
  "Composite-layer unit tests. `register_test` covers
  `load-fhir/build-from-fhir-data` and the construction-time invariants
  (bare-URL binding, supplement wrapping, naming-system resolution).
  This file targets the dispatch and cross-cutting behaviour itself:

    - precomputed `cs-meta` / `vs-meta` cache
    - `with-overlays` precedence
    - ConceptMap candidate selection (single, ambiguous)
    - version-availability + check-system-version issue construction"
  (:require [clojure.test :refer [deftest is testing]]
            [com.eldrix.hades.core :as hades]
            [com.eldrix.hades.impl.composite :as composite]
            [com.eldrix.hades.impl.load :as load-fhir]
            [com.eldrix.hades.impl.loaders.fhir :as loaders]
            [com.eldrix.hades.impl.protocols :as protos]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(def ^:private cs-v1
  {"resourceType" "CodeSystem"
   "url" "http://example.com/r/cs" "version" "1.0" "status" "active"
   "content" "complete" "caseSensitive" true
   "concept" [{"code" "A" "display" "Alpha"}]})

(def ^:private cs-v2
  {"resourceType" "CodeSystem"
   "url" "http://example.com/r/cs" "version" "2.0" "status" "draft"
   "content" "complete" "caseSensitive" true
   "experimental" true
   "concept" [{"code" "A" "display" "Alpha v2"}]})

(def ^:private cs-other
  {"resourceType" "CodeSystem"
   "url" "http://example.com/r/cs-other" "version" "1.0" "status" "active"
   "content" "complete" "caseSensitive" true
   "concept" [{"code" "X" "display" "ex"}]})

(def ^:private vs-cs
  {"resourceType" "ValueSet"
   "url" "http://example.com/r/vs" "version" "1.0" "status" "retired"
   "compose" {"include" [{"system" "http://example.com/r/cs"}]}})

(def ^:private cm-a-to-b
  {"resourceType" "ConceptMap"
   "url" "http://example.com/r/cm-1" "version" "1.0" "status" "active"
   "sourceUri" "http://example.com/r/cs"
   "targetUri" "http://example.com/r/cs-other"
   "group" [{"source" "http://example.com/r/cs"
             "target" "http://example.com/r/cs-other"
             "element" [{"code" "A"
                         "target" [{"code" "X" "equivalence" "equivalent"}]}]}]})

(def ^:private cm-a-to-b-alt
  (assoc cm-a-to-b "url" "http://example.com/r/cm-2"))

;; ---------------------------------------------------------------------------

(defn- fhir-data [resource-maps]
  (mapcat #(loaders/resource->fhir-data % "<test>") resource-maps))

(defn- svc-of [resource-maps]
  (let [{:keys [providers supplements]} (load-fhir/build-from-fhir-data
                                          (fhir-data resource-maps))]
    (composite/from-providers providers {:supplements supplements})))

;; ---------------------------------------------------------------------------
;; Precomputed metadata cache
;; ---------------------------------------------------------------------------

(deftest cs-meta-precomputed-cache-test
  (let [svc (svc-of [cs-v1 cs-other])]
    (testing "bare URL hits the cache"
      (is (= "1.0" (:version (composite/cs-meta svc "http://example.com/r/cs"))))
      (is (= "active" (:status (composite/cs-meta svc "http://example.com/r/cs")))))
    (testing "versioned URL hits the cache"
      (is (= "1.0" (:version (composite/cs-meta svc "http://example.com/r/cs|1.0")))))
    (testing "unknown URL returns nil — does not throw"
      (is (nil? (composite/cs-meta svc "http://example.com/r/missing"))))))

(deftest vs-meta-precomputed-cache-test
  (let [svc (svc-of [cs-v1 vs-cs])]
    (testing "VS metadata is cached"
      (is (= "retired" (:status (composite/vs-meta svc "http://example.com/r/vs")))))
    (testing "implicit VS for a CodeSystem is cached too"
      (is (some? (composite/vs-meta svc "http://example.com/r/cs"))))))

;; ---------------------------------------------------------------------------
;; Multi-resource provider — regression for the cache-build bug where
;; `cs-meta-by-key` was populated by calling `(cs-resource impl {})`
;; with empty params. Single-resource impls (in-memory CS, Hermes)
;; ignore params and so worked by accident; multi-resource impls
;; (SQLite catalogues serving thousands of CSs through one impl)
;; dispatch on `:url` and silently returned nil for every registered
;; key — leaving `composite/cs-meta` to return nil, which in turn
;; suppressed status warnings on `$validate-code` / `$expand` against
;; any SQLite-backed CodeSystem. The cache is now built by parsing
;; each registration key into `{:url :system :version}`; the live-call
;; fallback in `cs-meta` / `vs-meta` does the same.
;; ---------------------------------------------------------------------------

(defn- multi-cs-provider
  "Mock provider that serves multiple CodeSystems through one impl —
  mirrors `SqliteCodeSystemCatalogue/cs-resource` (sqlite/provider.clj),
  which dispatches on `(:url params)`. Returns nil from `cs-resource`
  unless the call carries a `:url` matching one of the configured
  entries. Empty params → nil, which is exactly the failure mode the
  bug-fixed cache build now avoids."
  [entries]
  (let [by-url (into {} (map (juxt :url identity)) entries)]
    (reify protos/CodeSystem
      (cs-metadata [_ {url-q :url ver-q :version}]
        (eduction
         (filter (fn [{:keys [url version]}]
                   (and (or (nil? url-q) (= url-q url))
                        (or (nil? ver-q) (= ver-q version)))))
         (map (fn [{:keys [url version]}]
                (cond-> {:url url}
                  (some? version) (assoc :version version))))
         entries))
      (cs-resource [_ {:keys [url]}]
        (when-let [m (get by-url url)]
          (select-keys m [:url :version :name :title :status :description]))))))

(defn- multi-vs-provider
  "Mock provider that serves multiple ValueSets through one impl —
  mirrors `SqliteValueSetCatalogue/vs-resource`."
  [entries]
  (let [by-url (into {} (map (juxt :url identity)) entries)]
    (reify protos/ValueSet
      (vs-metadata [_ {url-q :url ver-q :version}]
        (eduction
         (filter (fn [{:keys [url version]}]
                   (and (or (nil? url-q) (= url-q url))
                        (or (nil? ver-q) (= ver-q version)))))
         (map (fn [{:keys [url version]}]
                (cond-> {:url url}
                  (some? version) (assoc :version version))))
         entries))
      (vs-resource [_ {:keys [url]}]
        (when-let [m (get by-url url)]
          (select-keys m [:url :version :name :title :status :description]))))))

(deftest cs-meta-by-key-built-with-url-params
  (testing "cs-meta-by-key is populated for every registration of a
            multi-resource provider — without the fix, every value is
            nil and `cs-meta` returns nil for any registered URL"
    (let [entries [{:url "http://x/cs/a" :version "1.0" :status "active"
                    :name "Aye"}
                   {:url "http://x/cs/b" :version "1.0" :status "draft"
                    :name "Bee"}
                   {:url "http://x/cs/c" :version "1.0" :status "retired"
                    :name "Cee"}]
          svc     (composite/from-providers [(multi-cs-provider entries)])]
      (is (= "active"  (:status (composite/cs-meta svc "http://x/cs/a"))))
      (is (= "draft"   (:status (composite/cs-meta svc "http://x/cs/b"))))
      (is (= "retired" (:status (composite/cs-meta svc "http://x/cs/c"))))
      (testing "versioned-key lookup also resolves"
        (is (= "active" (:status (composite/cs-meta svc "http://x/cs/a|1.0"))))))))

(deftest vs-meta-by-key-built-with-url-params
  (testing "vs-meta-by-key is populated for every registration of a
            multi-resource provider"
    (let [entries [{:url "http://x/vs/p" :version "1.0" :status "active"
                    :name "Pee"}
                   {:url "http://x/vs/q" :version "1.0" :status "draft"
                    :name "Queue"}]
          svc     (composite/from-providers [(multi-vs-provider entries)])]
      (is (= "active" (:status (composite/vs-meta svc "http://x/vs/p"))))
      (is (= "draft"  (:status (composite/vs-meta svc "http://x/vs/q")))))))

(deftest cs-meta-honours-naming-system-aliases-test
  (let [base (svc-of [cs-v1])
        resolver (fn [id]
                   (when (= id "urn:oid:1.2.3") "http://example.com/r/cs"))
        svc (composite/->TerminologyService
              (:codesystems base)
              (:valuesets base)
              (:conceptmaps base)
              [resolver]
              (:cs-meta-by-key base)
              (:vs-meta-by-key base)
              {} [])]
    (testing "alias is resolved against the precomputed map"
      (is (= "1.0" (:version (composite/cs-meta svc "urn:oid:1.2.3")))))
    (testing "unknown alias returns nil"
      (is (nil? (composite/cs-meta svc "urn:oid:9.9.9"))))))

;; ---------------------------------------------------------------------------
;; with-overlays
;; ---------------------------------------------------------------------------

(deftest with-overlays-precedence-test
  (let [base (svc-of [cs-v1])
        ;; overlay introduces a *different* version-2 of the same URL
        {overlay-providers :providers}
        (load-fhir/build-from-fhir-data (fhir-data [cs-v2]))
        layered (hades/with-overlays base overlay-providers)]
    (testing "base codes still reachable at the versioned key"
      (is (some? (composite/find-codesystem layered "http://example.com/r/cs|1.0"))))
    (testing "overlay codes reachable at the new versioned key"
      (is (some? (composite/find-codesystem layered "http://example.com/r/cs|2.0"))))
    (testing "overlay metadata is in the merged cache"
      (is (= "draft" (:status (composite/cs-meta layered "http://example.com/r/cs|2.0")))))
    (testing "base service still works after layering (view, not mutation)"
      (is (some? (composite/find-codesystem base "http://example.com/r/cs|1.0")))
      (is (nil? (composite/find-codesystem base "http://example.com/r/cs|2.0"))))))

;; ---------------------------------------------------------------------------
;; ConceptMap candidate selection
;; ---------------------------------------------------------------------------

(deftest cm-translate-single-match-test
  (let [svc (svc-of [cs-v1 cs-other cm-a-to-b])
        result (hades/translate svc {:url "http://example.com/r/cm-1" :code "A"})]
    (testing "single ConceptMap match dispatches without ambiguity"
      (is (true? (:result result)))
      (is (some? (seq (:matches result)))))))

(deftest cm-translate-ambiguous-system-target-test
  (let [svc (svc-of [cs-v1 cs-other cm-a-to-b cm-a-to-b-alt])
        result (hades/translate svc {:system "http://example.com/r/cs"
                                     :target "http://example.com/r/cs-other"
                                     :code "A"})]
    (testing "two ConceptMaps for the same (source, target) pair forces :url"
      (is (false? (:result result)))
      (is (some #(= "ambiguous-target" (:details-code %)) (:issues result))))))

(deftest cm-translate-no-match-test
  (let [svc (svc-of [cs-v1 cs-other cm-a-to-b])]
    (testing "no matching ConceptMap returns nil (falls through to caller)"
      (is (nil? (hades/translate svc {:url "http://example.com/r/cm-missing"
                                      :code "A"}))))))

;; ---------------------------------------------------------------------------
;; Version availability + check-system-version
;; ---------------------------------------------------------------------------

(deftest available-versions-test
  (let [svc (svc-of [cs-v1 cs-v2])]
    (testing "lists every registered version for a system"
      (is (= #{"1.0" "2.0"}
             (set (composite/available-versions svc "http://example.com/r/cs")))))
    (testing "returns empty for unknown systems"
      (is (empty? (composite/available-versions svc "http://example.com/r/missing"))))))

(deftest unknown-version-issue-test
  (let [svc (svc-of [cs-v1 cs-v2])]
    (testing "known version yields no issue"
      (is (nil? (composite/unknown-version-issue svc "http://example.com/r/cs" "1.0"))))
    (testing "unknown version yields a not-found issue with valid-versions hint"
      (let [issue (composite/unknown-version-issue svc "http://example.com/r/cs" "9.9.9")]
        (is (= "error" (:severity issue)))
        (is (= "not-found" (:details-code issue)))
        (is (re-find #"Valid versions" (:text issue)))))
    (testing "different :purpose tweaks the trailing clause"
      (let [issue (composite/unknown-version-issue svc "http://example.com/r/cs" "9.9.9" :expand)]
        (is (re-find #"value set cannot be expanded" (:text issue)))
        (is (nil? (:expression issue)))))))

(deftest check-system-version-issue-test
  (let [svc (svc-of [cs-v1])
        sys "http://example.com/r/cs"
        params {:check-system-version {sys "1.0"}}]
    (testing "matching pattern yields no issue"
      (is (nil? (composite/check-system-version-issue svc sys "1.0" params))))
    (testing "mismatch yields a version-error issue"
      (let [issue (composite/check-system-version-issue svc sys "2.0" params)]
        (is (= "error" (:severity issue)))
        (is (= "version-error" (:details-code issue)))
        (is (re-find #"required to be '1.0'" (:text issue)))))))

;; ---------------------------------------------------------------------------
;; CodeableConcept aggregation across providers
;;
;; Mirrors `validation/complex-codeableconcept-full` from the tx-ecosystem
;; conformance suite — a CC carries codings from two different code systems
;; against a ValueSet that includes both. The composite must dispatch each
;; coding to the right provider, then aggregate per the FHIR spec.
;; ---------------------------------------------------------------------------

(def ^:private cs-fruit
  {"resourceType" "CodeSystem"
   "url"          "http://example.com/r/fruit" "version" "1.0" "status" "active"
   "content"      "complete" "caseSensitive" true
   "concept"      [{"code" "apple"  "display" "Apple"}
                   {"code" "banana" "display" "Banana"}]})

(def ^:private cs-veg
  {"resourceType" "CodeSystem"
   "url"          "http://example.com/r/veg" "version" "1.0" "status" "active"
   "content"      "complete" "caseSensitive" true
   "concept"      [{"code" "carrot" "display" "Carrot"}]})

(def ^:private vs-produce
  {"resourceType" "ValueSet"
   "url"          "http://example.com/r/produce" "version" "1.0" "status" "active"
   "compose"      {"include" [{"system" "http://example.com/r/fruit"}
                              {"system" "http://example.com/r/veg"}]}})

(deftest validate-cc-cross-provider-mix-valid-and-invalid
  (testing "FHIR semantics: any invalid coding makes overall result=false even if another coding is valid; the valid coding's metadata still surfaces"
    (let [svc (svc-of [cs-fruit cs-veg vs-produce])
          codings [{:system "http://example.com/r/fruit" :code "apple"}
                   {:system "http://example.com/r/veg"   :code "potato"}]
          r (composite/validate-codeable-concept svc codings
              {:url "http://example.com/r/produce"})]
      (is (false? (:result r))
          "matches the conformance fixture validation/complex-codeableconcept-full-response: any invalid coding flips result to false")
      (is (= :apple (:code r))
          "valid coding's code surfaces (in-memory provider returns codes as keywords)")
      (is (= "Apple" (:display r)))
      (testing "the invalid coding's per-coding issue carries the demoted details-code"
        (is (some #(and (= "this-code-not-in-vs" (:details-code %))
                        (= 1 (:coding-index %))
                        (= "information" (:severity %)))
                  (:issues r)))))))

(deftest validate-cc-cross-provider-all-valid
  (testing "every coding valid → result=true"
    (let [svc (svc-of [cs-fruit cs-veg vs-produce])
          codings [{:system "http://example.com/r/fruit" :code "apple"}
                   {:system "http://example.com/r/veg"   :code "carrot"}]
          r (composite/validate-codeable-concept svc codings
              {:url "http://example.com/r/produce"})]
      (is (true? (:result r))))))

(deftest validate-cc-cross-provider-all-invalid
  (testing "a CC where every coding is rejected returns result=false with a combined message"
    (let [svc (svc-of [cs-fruit cs-veg vs-produce])
          codings [{:system "http://example.com/r/fruit" :code "kumquat"}
                   {:system "http://example.com/r/veg"   :code "potato"}]
          r (composite/validate-codeable-concept svc codings
              {:url "http://example.com/r/produce"})]
      (is (false? (:result r)))
      (is (string? (:message r)))
      (is (some #(= "not-in-vs" (:details-code %)) (:issues r))
          "issues include the synthesised \"no valid coding\" not-in-vs"))))
