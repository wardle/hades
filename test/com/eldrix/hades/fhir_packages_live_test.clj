(ns com.eldrix.hades.fhir-packages-live-test
  "Live FTRM-vs-in-memory parity for real FHIR packages.

  One pair of services over identical data sources — the in-memory side
  opens the cached package tarballs (extracted on open), the FTRM side
  opens the combined SQLite container built from the same packages — with
  SNOMED CT International and LOINC mounted on both so that
  SNOMED/LOINC-referencing ValueSets expand to real concept sets rather
  than passing vacuously empty-vs-empty. Every test asserts the two
  engines enumerate, lookup, validate, translate and expand identically.

  The `us.nlm.vsac` package physically ships ~7,600 ValueSets twice (once
  at the package top level, once under `$root/`/`other/` subdirectories),
  so its raw file count (~16,700) is nearly double the distinct
  `(url, version)` count (9,071). `vs-metadata-parity` proves both engines
  collapse the duplicates to the same catalogue.

  Tagged `^:live` — needs `data/snomed-intl-20250201.db`,
  `data/loinc-2.82.db`, the cached package tarballs under
  `data/fhir-cache`, and the combined FTRM container `data/fhir-tx.db`.
  All are provisioned by the recipe in `doc/development.md`; the `--dist`
  set must match `fixtures/fhir-packages`."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.eldrix.hades.core :as hades]
            [com.eldrix.hades.fixtures :as fixtures]
            [com.eldrix.hades.composite :as composite]
            [com.eldrix.hades.protocols :as protos]))

(def ^:dynamic *mem* nil)
(def ^:dynamic *sql* nil)

(defn parity-fixture [f]
  (let [base (fixtures/paths [:sct/conformance :loinc/v2_82])
        mem  (hades/open (concat base (fixtures/fhir-package-archives!)) {:default-locale "en-US"})
        sql  (hades/open (concat base (fixtures/paths [:fhir/tx])) {:default-locale "en-US"})]
    (binding [*mem* mem *sql* sql]
      (try (f)
           (finally
             (hades/close mem)
             (hades/close sql))))))

(use-fixtures :once parity-fixture)

(defn- url-version-set [metadata]
  (->> metadata (map (juxt :url :version)) set))

(defn- match-code?
  [result code]
  (some #(= code (:code %)) (:matches result)))

(defn- first-code-from-cs
  "Pull one code from a CodeSystem via `cs-expand*` with no filters,
  bounded by `:max-hits 1`. Returns nil when the CS exposes no concepts
  (e.g. content=not-present)."
  [svc system]
  (when-let [cs (composite/find-codesystem svc system)]
    (-> (protos/cs-expand* cs {:system system :max-hits 1})
        :concepts first :code)))

(deftest ^:live cs-metadata-parity
  (testing "in-memory and FTRM engines list the same CodeSystems"
    (let [mem-cs (url-version-set (protos/cs-metadata *mem* {}))
          sql-cs (url-version-set (protos/cs-metadata *sql* {}))]
      (is (pos? (count mem-cs)) "expected non-empty CodeSystem catalogue")
      (is (= mem-cs sql-cs)
          (str "Engines disagree on CodeSystem catalogue. "
               "in-memory only: " (pr-str (set/difference mem-cs sql-cs))
               "; ftrm only: "    (pr-str (set/difference sql-cs mem-cs)))))))

(def ^:private known-alias
  ;; v2-0203's OID — present in both engines, resolvable by bare + urn forms.
  {:bare "2.16.840.1.113883.18.108"
   :urn  "urn:oid:2.16.840.1.113883.18.108"
   :canonical "http://terminology.hl7.org/CodeSystem/v2-0203"
   :code "AM"})

(deftest ^:live cs-alias-resolution-parity
  (testing "both engines resolve a CodeSystem OID alias identically"
    (let [{:keys [bare urn canonical code]} known-alias
          ;; The canonical lookup is the control; both alias forms must
          ;; match it on each engine, and the engines must match each other.
          via (fn [svc system] (:display (hades/lookup svc {:system system :code code})))]
      (doseq [[label svc] [["in-memory" *mem*] ["ftrm" *sql*]]]
        (let [c (via svc canonical)]
          (is (some? c) (str label ": canonical lookup must resolve"))
          (is (= c (via svc bare)) (str label ": bare-OID alias must match canonical"))
          (is (= c (via svc urn))  (str label ": urn:oid: alias must match canonical"))))
      (is (= (via *mem* urn) (via *sql* urn))
          "engines disagree resolving the urn:oid: alias"))))

(deftest ^:live vs-metadata-parity
  (testing "in-memory and FTRM engines list the same ValueSets"
    (let [mem-vs (url-version-set (protos/vs-metadata *mem* {}))
          sql-vs (url-version-set (protos/vs-metadata *sql* {}))]
      (is (pos? (count mem-vs)) "expected non-empty ValueSet catalogue")
      (is (= mem-vs sql-vs)
          (str "Engines disagree on ValueSet catalogue. "
               "in-memory only: " (pr-str (take 5 (set/difference mem-vs sql-vs)))
               "; ftrm only: "    (pr-str (take 5 (set/difference sql-vs mem-vs))))))))

(deftest ^:live cm-metadata-parity
  (testing "in-memory and FTRM engines list the same ConceptMaps"
    (is (= (url-version-set (protos/cm-metadata *mem* {}))
           (url-version-set (protos/cm-metadata *sql* {}))))))

(deftest ^:live cm-translate-by-system-target
  (let [cases [{:label "administrative gender to v3 by target CodeSystem"
                :params {:system "http://hl7.org/fhir/administrative-gender"
                         :target "http://terminology.hl7.org/CodeSystem/v3-AdministrativeGender"
                         :code "male"}
                :code "M"}
               {:label "administrative gender to v3 by target ValueSet"
                :params {:system "http://hl7.org/fhir/administrative-gender"
                         :target "http://terminology.hl7.org/ValueSet/v3-AdministrativeGender"
                         :code "male"}
                :code "M"}
               {:label "address-use to v3 by target"
                :params {:system "http://hl7.org/fhir/address-use"
                         :target "http://terminology.hl7.org/ValueSet/v3-AddressUse"
                         :code "home"}
                :code "H"}
               {:label "contact-point-use to v2 by target"
                :params {:system "http://hl7.org/fhir/contact-point-use"
                         :target "http://terminology.hl7.org/CodeSystem/v2-0201"
                         :code "mobile"}
                :code "PRS"}
               {:label "IPS pregnancy LOINC answer to SNOMED by target"
                :params {:system "http://loinc.org"
                         :target "http://snomed.info/sct"
                         :code "LA15173-0"}
                :code "77386006"}
               {:label "IPS smoking LOINC answer to SNOMED by target"
                :params {:system "http://loinc.org"
                         :target "http://snomed.info/sct"
                         :code "LA18976-3"}
                :code "449868002"}]]
    (doseq [{:keys [label params code]} cases]
      (testing (str label " (in-memory)")
        (let [result (hades/translate *mem* params)]
          (is (true? (:result result)) (pr-str result))
          (is (match-code? result code) (pr-str (:matches result)))))
      (testing (str label " (FTRM)")
        (let [result (hades/translate *sql* params)]
          (is (true? (:result result)) (pr-str result))
          (is (match-code? result code) (pr-str (:matches result))))))))

(deftest ^:live cs-lookup-parity-sampled
  (testing "$lookup agrees on the first concept of a sample of CodeSystems"
    ;; Sample the first 10 CodeSystems that expose at least one concept.
    ;; Skip empty / not-present CSes — there's nothing to lookup. We rely
    ;; on metadata-parity above to prove the catalogue itself matches;
    ;; here we exercise the read path.
    (let [samples (->> (protos/cs-metadata *mem* {})
                       (keep (fn [{:keys [url version]}]
                               (when-let [code (first-code-from-cs *mem* url)]
                                 [url version code])))
                       (take 10))]
      (is (pos? (count samples))
          "expected at least one CodeSystem with concepts to sample")
      (doseq [[system version code] samples]
        (let [params {:system system :version version :code code}
              m (hades/lookup *mem* params)
              s (hades/lookup *sql* params)]
          (is (= (:display m) (:display s))
              (str "display mismatch for " system "|" version " #" code
                   ": mem=" (pr-str (:display m)) " sql=" (pr-str (:display s))))
          (is (= (:code m) (:code s))
              (str "code echo mismatch for " system "|" version " #" code)))))))

(deftest ^:live cs-validate-code-parity-sampled
  (testing "$validate-code agrees on a sample of known-good codes"
    (let [samples (->> (protos/cs-metadata *mem* {})
                       (keep (fn [{:keys [url version]}]
                               (when-let [code (first-code-from-cs *mem* url)]
                                 [url version code])))
                       (take 10))]
      (doseq [[system version code] samples]
        (let [params {:system system :version version :code code}
              m (hades/validate-code *mem* params)
              s (hades/validate-code *sql* params)]
          (is (= (boolean (:result m)) (boolean (:result s)))
              (str "result mismatch for " system "|" version " #" code))
          (is (= (:display m) (:display s))
              (str "display mismatch for " system "|" version " #" code)))))))

(defn- vsac-url? [url] (and url (.contains ^String url "cts.nlm.nih.gov")))

(defn- vsac-urls
  "Sorted distinct VSAC ValueSet URLs visible to `svc`. VSAC supplies the
  large extensional/intensional, cross-terminology ValueSets that make
  expansion parity non-trivial."
  [svc]
  (->> (protos/vs-metadata svc {})
       (map :url)
       (filter vsac-url?)
       distinct sort))

(defn- expansion
  ([svc url] (expansion svc url nil))
  ([svc url params]
   (let [r (hades/expand svc (merge {:url url :count 1000000} params))]
     {:total (:total r)
      :codes (set (map (juxt :system :code) (:concepts r)))})))

(deftest ^:live vs-expansion-parity-sampled
  (testing "both engines expand a sample of VSAC ValueSets to identical totals and concept sets"
    (let [urls    (->> (vsac-urls *mem*) (take-nth 100) (take 50))
          results (for [u urls]
                    (let [a (expansion *mem* u)
                          b (expansion *sql* u)]
                      {:url u :a a :b b
                       :match (and (= (:total a) (:total b))
                                   (= (:codes a) (:codes b)))}))
          nonempty (filter #(pos? (or (get-in % [:a :total]) 0)) results)
          mismatches (remove :match results)]
      (is (pos? (count urls)) "expected VSAC ValueSets to sample")
      (is (>= (count nonempty) 5)
          (str "guard against a vacuous pass: fewer than 5 non-empty expansions "
               "in the sample (" (count nonempty) "/" (count results) "). "
               "Are SNOMED/LOINC fixtures loaded?"))
      (is (empty? mismatches)
          (str "Engines disagree on expansion for: "
               (pr-str (for [m (take 10 mismatches)]
                         [(:url m) :mem-total (get-in m [:a :total])
                          :sql-total (get-in m [:b :total])])))))))

(deftest ^:live vs-enrichment-parity-sampled
  (testing "both engines agree under activeOnly / properties — the lazy
            enrichment path (`expand-include-concepts`), which the
            stored-extensional fast path deliberately does not serve"
    (let [urls (->> (vsac-urls *mem*) (take-nth 200) (take 12))]
      (doseq [params [{:activeOnly true} {:properties ["inactive"]}]]
        (let [results    (for [u urls]
                           (let [a (expansion *mem* u params)
                                 b (expansion *sql* u params)]
                             {:url u :a a :b b
                              :match (and (= (:total a) (:total b))
                                          (= (:codes a) (:codes b)))}))
              ;; `:total` is omitted on this path, so the vacuous-pass
              ;; guard keys off the concept set, not the total.
              nonempty   (filter #(seq (get-in % [:a :codes])) results)
              mismatches (remove :match results)]
          (is (>= (count nonempty) 3)
              (str "guard against a vacuous pass under " (pr-str params)
                   ": fewer than 3 non-empty expansions ("
                   (count nonempty) "/" (count results) ")"))
          (is (empty? mismatches)
              (str "Engines disagree under " (pr-str params) " for: "
                   (pr-str (for [m (take 10 mismatches)]
                             [(:url m) :mem-codes (count (get-in m [:a :codes]))
                              :sql-codes (count (get-in m [:b :codes]))])))))))))
