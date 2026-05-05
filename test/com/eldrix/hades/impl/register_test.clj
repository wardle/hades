(ns com.eldrix.hades.impl.register-test
  "Tests for `load-fhir/build-from-fhir-data` plus the resulting service
  shape: bare-URL binding rules, supplement wrapping, NamingSystem
  alias resolution, ConceptMap translation."
  (:require [clojure.test :refer [deftest is testing]]
            [com.eldrix.hades.core :as hades]
            [com.eldrix.hades.impl.canonical :as canonical]
            [com.eldrix.hades.impl.composite :as composite]
            [com.eldrix.hades.impl.load :as load-fhir]
            [com.eldrix.hades.impl.loaders.fhir :as loaders]
            [com.eldrix.hades.impl.protocols :as protos]))

(def ^:private cs-v1
  {"resourceType" "CodeSystem"
   "url" "http://example.com/r/cs"  "version" "1.0" "status" "active"
   "content" "complete" "caseSensitive" true
   "concept" [{"code" "A" "display" "Alpha"}]})

(def ^:private cs-v2
  {"resourceType" "CodeSystem"
   "url" "http://example.com/r/cs"  "version" "2.0" "status" "active"
   "content" "complete" "caseSensitive" true
   "concept" [{"code" "A" "display" "Alpha v2"}]})

(def ^:private vs
  {"resourceType" "ValueSet"
   "url" "http://example.com/r/vs" "version" "1.0" "status" "active"
   "compose" {"include" [{"system" "http://example.com/r/cs"}]}})

(def ^:private supp
  {"resourceType" "CodeSystem"
   "url" "http://example.com/r/cs-supp" "version" "1.0" "status" "active"
   "content" "supplement" "supplements" "http://example.com/r/cs|1.0"
   "concept" [{"code" "A"
               "designation" [{"language" "fr" "value" "Alpha (fr)"}]}]})

(defn- fhir-data [resource-maps]
  (mapcat #(loaders/resource->fhir-data % "<test>") resource-maps))

(defn- svc-of
  "Build a service from `resource-maps` via `build-from-fhir-data` and
  `composite/from-providers`."
  [resource-maps]
  (let [{:keys [providers supplements]} (load-fhir/build-from-fhir-data
                                          (fhir-data resource-maps))]
    (composite/from-providers providers {:supplements supplements})))

;; ---------------------------------------------------------------------------

(deftest register-codesystem-and-valueset-test
  (let [{:keys [providers]} (load-fhir/build-from-fhir-data (fhir-data [cs-v1 vs]))
        svc (composite/from-providers providers)]
    (testing "CodeSystem reachable under bare URL and url|version"
      (is (some? (composite/find-codesystem svc "http://example.com/r/cs")))
      (is (some? (composite/find-codesystem svc "http://example.com/r/cs|1.0"))))
    (testing "implicit ValueSet of the CodeSystem is also registered"
      (is (some? (composite/find-valueset svc "http://example.com/r/cs")))
      (is (some? (composite/find-valueset svc "http://example.com/r/cs|1.0"))))
    (testing "compose-driven ValueSet reachable under its own URL"
      (is (some? (composite/find-valueset svc "http://example.com/r/vs"))))))

(deftest single-version-bare-url-binding-test
  (let [svc (svc-of [cs-v1])]
    (testing "single version binds the bare URL"
      (let [r (protos/cs-lookup (composite/find-codesystem svc "http://example.com/r/cs")
                                {:code "A"})]
        (is (= "Alpha" (:display r)))))))

(deftest multi-version-picks-semver-latest-test
  (let [svc (svc-of [cs-v1 cs-v2])]
    (testing "two semver versions — bare URL binds to the latest"
      (let [r (protos/cs-lookup (composite/find-codesystem svc "http://example.com/r/cs")
                                {:code "A"})]
        (is (= "Alpha v2" (:display r)))))
    (testing "versioned keys remain individually reachable"
      (is (some? (composite/find-codesystem svc "http://example.com/r/cs|1.0")))
      (is (some? (composite/find-codesystem svc "http://example.com/r/cs|2.0"))))))

(deftest semver-compare-nil-handling
  (testing "nil < any concrete version, two nils are equal"
    (is (zero? (canonical/semver-compare nil nil)))
    (is (neg?  (canonical/semver-compare nil "1.0")))
    (is (pos?  (canonical/semver-compare "1.0" nil)))
    (is (neg?  (canonical/semver-compare nil "http://snomed.info/sct/x/version/20250201")))
    (is (pos?  (canonical/semver-compare "http://snomed.info/sct/x/version/20250201" nil))))
  (testing "opaque strings (URIs, labels) tie at 0 — no leading digit, no inherent order"
    (is (zero? (canonical/semver-compare "http://a/x/version/1"
                                         "http://b/y/version/2")))
    (is (zero? (canonical/semver-compare "draft" "released"))
        "opaque labels must tie so the composite's tie-detection raises ambiguous-default"))
  (testing "numeric semvers still compare numerically"
    (is (neg? (canonical/semver-compare "1.9.0" "1.10.0")))
    (is (pos? (canonical/semver-compare "2.0"   "1.99")))))

(deftest semver-compare-pre-release-precedence
  (testing "SemVer 2.0.0 §11: a pre-release version sorts BELOW the same numeric version without a suffix"
    (is (neg? (canonical/semver-compare "4.0.0-rc1" "4.0.0")))
    (is (pos? (canonical/semver-compare "4.0.0" "4.0.0-rc1")))
    (is (neg? (canonical/semver-compare "4.0.0-alpha" "4.0.0-beta")))
    (is (neg? (canonical/semver-compare "4.0.0-rc1" "4.0.1"))
        "pre-release of one version is below GA of the next patch")))

(def ^:private cs-no-version
  {"resourceType" "CodeSystem"
   "url" "http://example.com/r/cs"  "status" "active"
   "content" "not-present"})

(deftest mixed-nil-and-versioned-bare-url-binds-versioned
  (testing "versionless stub + versioned content for same URL — bare URL binds the versioned entry"
    (let [svc (svc-of [cs-no-version cs-v1])
          r (protos/cs-lookup (composite/find-codesystem svc "http://example.com/r/cs")
                              {:code "A"})]
      (is (= "Alpha" (:display r))
          "versioned 1.0 must win over the nil-version stub"))))

(def ^:private cs-non-semver
  {"resourceType" "CodeSystem"
   "url" "http://example.com/r/cs2" "version" "draft" "status" "active"
   "content" "complete" "concept" [{"code" "A" "display" "Alpha"}]})

(def ^:private cs-non-semver-2
  {"resourceType" "CodeSystem"
   "url" "http://example.com/r/cs2" "version" "released" "status" "active"
   "content" "complete" "concept" [{"code" "A" "display" "Alpha released"}]})

(deftest multi-version-ambiguous-throws-test
  (let [{:keys [providers]} (load-fhir/build-from-fhir-data
                              (fhir-data [cs-non-semver cs-non-semver-2]))
        ex (try (composite/from-providers providers) nil
                (catch clojure.lang.ExceptionInfo e e))]
    (is (some? ex) "expected ambiguous-default to throw")
    (is (= :ambiguous-default (:reason (ex-data ex))))))

(deftest multi-version-explicit-default-test
  (let [{:keys [providers]} (load-fhir/build-from-fhir-data
                              (fhir-data [cs-non-semver cs-non-semver-2]))
        svc (composite/from-providers providers
              {:defaults {"http://example.com/r/cs2" "released"}})]
    (testing ":defaults binding picks the named version for the bare URL"
      (let [r (protos/cs-lookup (composite/find-codesystem svc "http://example.com/r/cs2")
                                {:code "A"})]
        (is (= "Alpha released" (:display r)))))))

(deftest duplicate-resource-fails-fast-test
  (let [ex (try
             (load-fhir/build-from-fhir-data
               (concat (fhir-data [cs-v1])
                       (fhir-data [cs-v1])))
             nil
             (catch clojure.lang.ExceptionInfo e e))]
    (is (some? ex))
    (is (= :duplicate-resource (:reason (ex-data ex))))
    (is (seq (:duplicates (ex-data ex))))))

(deftest supplement-wraps-registered-base-test
  (let [svc (svc-of [cs-v1 supp])]
    (testing "the supplement augments base lookup results"
      (let [r (protos/cs-lookup
                (composite/find-codesystem svc "http://example.com/r/cs|1.0")
                {:code "A"})
            values (set (map :value (:designations r)))]
        (is (contains? values "Alpha (fr)"))))
    (testing "the supplement also reachable under its own URL"
      (is (some? (composite/find-codesystem svc "http://example.com/r/cs-supp|1.0"))))))

(deftest end-to-end-fixture-directory-test
  (let [data (loaders/load-paths "test/resources/fhir-resources/good")
        {:keys [providers supplements totals]} (load-fhir/build-from-fhir-data data)
        svc (composite/from-providers providers {:supplements supplements})]
    (testing "fixture directory loads"
      (is (>= (:codesystems totals) 3)
          (str "totals: " totals)))
    (testing "loader-test/cs reachable; A code lookup returns Alpha"
      (let [r (protos/cs-lookup
                (composite/find-codesystem svc "http://example.com/loader-test/cs")
                {:code "A"})]
        (is (= "Alpha" (:display r)))))
    (testing "supplement augments base"
      (let [r (protos/cs-lookup
                (composite/find-codesystem svc "http://example.com/loader-test/cs")
                {:code "A"})
            values (set (map :value (:designations r)))]
        (is (contains? values "Alpha (fr)"))))
    (testing "compose-driven VS expands"
      (let [r (hades/expand svc {:url "http://example.com/loader-test/vs"})]
        (is (= 2 (count (:concepts r))))))
    (testing "ConceptMap translates"
      (let [r (hades/translate svc {:url "http://example.com/loader-test/cm"
                                    :system "http://example.com/loader-test/cs"
                                    :code "A"})]
        (is (true? (:result r)))
        (is (= "X" (:code (first (:matches r)))))))))

;; ---------------------------------------------------------------------------
;; Multi-module SNOMED in one Hermes service.
;;
;; Reproduces the production symptom: a single Hermes LMDB import that
;; contains multiple SNOMED modules (Intl + UK clinical + UK drug, or
;; UK on its own which is itself multi-module) reports one cs-metadata
;; entry per `release-information` row. The composite then thinks it
;; has N providers for the bare URL `http://snomed.info/sct` and
;; refuses with `:ambiguous-default`.
;;
;; This is wrong — there is exactly ONE Hermes service handling that
;; URL. Hermes composes modules internally; the bare URL must surface
;; as ONE entry. The version field is the composite view's own version
;; (typically nil or an opaque label) — there is no FHIR convention
;; that bare `http://snomed.info/sct` defaults to International, and
;; UK-only deployments would be broken by such a convention.
;;
;; A separate scenario — TWO independent Hermes services passed in,
;; each holding a different edition — is legitimately ambiguous and
;; that is where `--default URL=VERSION` belongs.
;; ---------------------------------------------------------------------------

(def ^:private snomed-uri "http://snomed.info/sct")
(def ^:private intl-module    "900000000000207008")
(def ^:private uk-clin-module "999000041000000102")
(def ^:private uk-drug-module "999000011000001104")

(defn- snomed-multi-module-provider
  "Reify a CodeSystem provider that mimics the current `HermesService`
  shape — one cs-metadata entry per `release-information` row. This
  is the shape we want to fix: one Hermes service should yield one
  bare-URL entry, not N."
  [release-versions]
  (reify protos/CodeSystem
    (cs-metadata [_]
      (into [{:url snomed-uri :version "*"}]
            (map (fn [v] {:url snomed-uri :version v}) release-versions)))
    (cs-resource     [_ _]      {:url snomed-uri :version (first release-versions)})
    (cs-lookup       [_ _]      {:system snomed-uri :code (keyword "73211009")
                                  :display "Diabetes mellitus"})
    (cs-validate-code [_ _]     {:result true :system snomed-uri :code (keyword "73211009")})
    (cs-subsumes     [_ _]      {:outcome "not-subsumed"})
    (cs-find-matches [_ _]      {:concepts []})))

(deftest one-hermes-service-multi-module-binds-bare-url-test
  (testing "ONE Hermes service holding multiple SNOMED modules (Intl +
            UK clinical + UK drug) must bind the bare URL
            `http://snomed.info/sct` unambiguously — Hermes already
            composes modules; hades must not double-dispatch over
            `release-information` rows."
    (let [provider (snomed-multi-module-provider
                     [(str snomed-uri "/" intl-module    "/version/20260301")
                      (str snomed-uri "/" uk-clin-module "/version/20260211")
                      (str snomed-uri "/" uk-drug-module "/version/20260211")])
          ex (try (composite/from-providers [provider]) nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (nil? ex)
          (str "one Hermes service => one cs-metadata entry under the "
               "bare URL. Got: " (some-> ex ex-message))))))

(deftest uk-only-hermes-service-multi-module-binds-bare-url-test
  (testing "UK-only deployment (UK clinical + UK drug, no International
            module) must bind bare URL — picking International by
            convention would break this scenario entirely."
    (let [provider (snomed-multi-module-provider
                     [(str snomed-uri "/" uk-clin-module "/version/20260211")
                      (str snomed-uri "/" uk-drug-module "/version/20260211")])
          ex (try (composite/from-providers [provider]) nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (nil? ex)
          (str "UK-only Hermes service must bind bare URL without "
               "needing a module-specific default. Got: "
               (some-> ex ex-message))))))

(deftest two-hermes-services-distinct-editions-is-ambiguous-test
  (testing "TWO independent Hermes services (one Intl, one UK) IS
            legitimately ambiguous for the bare URL — operators
            resolve via an explicit default. Wildcard `|*` entries
            must not collide as duplicate resources first."
    (let [intl (snomed-multi-module-provider
                 [(str snomed-uri "/" intl-module "/version/20260301")])
          uk   (snomed-multi-module-provider
                 [(str snomed-uri "/" uk-clin-module "/version/20260211")])
          ex (try (composite/from-providers [intl uk]) nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "two providers under the same URL must not silently merge")
      (is (= :ambiguous-default (:reason (ex-data ex)))
          (str "expected ambiguity signal; got "
               (:reason (ex-data ex)))))))

(deftest two-hermes-services-explicit-default-binds-bare-and-wildcard-test
  (testing "explicit default selects one provider for the bare URL and wildcard fallback"
    (let [intl-version (str snomed-uri "/" intl-module "/version/20260301")
          uk-version (str snomed-uri "/" uk-clin-module "/version/20260211")
          intl (snomed-multi-module-provider [intl-version])
          uk (snomed-multi-module-provider [uk-version])
          svc (composite/from-providers [intl uk] {:defaults {snomed-uri intl-version}})]
      (is (identical? intl (composite/find-codesystem svc snomed-uri)))
      (is (identical? intl (composite/find-codesystem svc
                            (str snomed-uri "|" intl-version))))
      (is (identical? intl (composite/find-codesystem svc
                            (str snomed-uri "|"
                                 snomed-uri "/999999999999/version/20990131")))))))

(deftest cs-only-provider-not-registered-as-vs-test
  (testing "a CodeSystem provider that doesn't satisfy ValueSet must
            not be indexed under :valuesets — otherwise the implicit-VS
            dispatch routes vs-expand into a non-implementing impl"
    (let [cs-only (reify protos/CodeSystem
                    (cs-metadata [_]
                      [{:url "http://example.com/cs-only" :version "1"}])
                    (cs-resource [_ _] {:url "http://example.com/cs-only" :version "1"})
                    (cs-lookup [_ _] nil)
                    (cs-validate-code [_ _]
                      {:result false :code (keyword "A")
                       :system "http://example.com/cs-only"})
                    (cs-subsumes [_ _] {:outcome "not-subsumed"})
                    (cs-find-matches [_ _] {:concepts []}))
          svc (composite/from-providers [cs-only])]
      (is (some? (composite/find-codesystem svc "http://example.com/cs-only|1")))
      (is (nil? (composite/find-valueset svc "http://example.com/cs-only|1"))
          "CS-only provider must not appear in the ValueSet catalogue")
      (is (nil? (composite/find-valueset svc "http://example.com/cs-only"))))))

(deftest naming-system-resolves-alias-to-canonical
  (let [base (svc-of [cs-v1])
        resolver (fn [id]
                   (case id
                     "urn:oid:1.2.3" "http://example.com/r/cs"
                     nil))
        svc (composite/from-providers [] {:naming-systems [resolver]
                            :codesystems  (:codesystems base)
                            :valuesets    (:valuesets base)})
        ;; reconstruct with naming-systems by re-opening with the providers
        ;; (open's two-arity preserves naming-systems for us)
        svc (composite/->TerminologyService
              (:codesystems base)
              (:valuesets base)
              (:conceptmaps base)
              [resolver]
              (:cs-meta-by-key base)
              (:vs-meta-by-key base)
              {} [])]
    (testing "alias resolves to canonical when no direct match exists"
      (is (some? (composite/find-codesystem svc "urn:oid:1.2.3"))))
    (testing "direct canonical lookup is unaffected"
      (is (some? (composite/find-codesystem svc "http://example.com/r/cs"))))
    (testing "unknown alias does not match"
      (is (nil? (composite/find-codesystem svc "urn:oid:9.9.9"))))
    (testing "resolve-canonical returns input on unmatched non-blank id"
      (is (= "passthrough" (composite/resolve-canonical [resolver] "passthrough"))))
    (testing "resolve-canonical returns nil on blank/nil"
      (is (nil? (composite/resolve-canonical [resolver] nil)))
      (is (nil? (composite/resolve-canonical [resolver] ""))))))
