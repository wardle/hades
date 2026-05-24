(ns com.eldrix.hades.bench
  "Criterium micro-benchmarks for Hades' FHIR terminology operations.
  Run via `clj -M:bench`; writes `target/bench-results.json`."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest]]
            [com.eldrix.hades.composite :as composite]
            [com.eldrix.hades.core :as hades]
            [com.eldrix.hades.fixtures :as fixtures]
            [com.eldrix.hades.impl.load :as load-fhir]
            [com.eldrix.hades.providers.common.compose :as compose]
            [com.eldrix.hades.providers.loinc.model :as loinc-model]
            [com.eldrix.hades.protocols :as protos]
            [criterium.core :as crit]))

(set! *warn-on-reflection* true)

(def ^:private snomed-uri "http://snomed.info/sct")
(def ^:private loinc-uri  "http://loinc.org")
(def ^:private ieee-uri "urn:iso:std:iso:11073:10101")
(def ^:private radlex-uri "http://www.radlex.org")
(def ^:private rsna-playbook-uri "http://www.rsna.org/RadLex_Playbook")

(def ^:private loinc-lk02-pool
  ["8867-4" "718-7" "4548-4" "11556-8" "1009-0"
   "8310-5" "8302-2" "8480-6" "8462-4" "9279-1"
   "29463-7" "39156-5" "2339-0" "6690-2"])

;; ─── Well-known SNOMED anchors ─────────────────────────────────────────────

(def ^:private diabetes-mellitus  73211009)
(def ^:private type-2-dm          44054006)
(def ^:private disease            64572001)
(def ^:private influenza          6142004)
(def ^:private clinical-finding   404684003)
(def ^:private asthma             195967001)
(def ^:private multiple-sclerosis 24700007)
(def ^:private procedure          71388002)

(defn- expand-url [suffix] (str snomed-uri "?fhir_vs=" suffix))

(def ^:private stored-vs-url "http://example.com/bench/stored-vs")

(defonce ^:private stored-vs-svc
  (let [vs (load-fhir/from-fhir
            {"resourceType" "ValueSet"
             "url" stored-vs-url
             "status" "active"
             "expansion" {"contains"
                          (mapv (fn [n]
                                  {"system" "http://example.com/bench/cs"
                                   "code" (str "code-" n)
                                   "display" (str "Display " n)})
                                (range 20000))}})]
    (composite/from-providers [vs])))

(def ^:private missing-display-vs-url "http://example.com/bench/missing-display-snomed-vs")

(def ^:private missing-display-overlay
  "Build the missing-display cliff fixture, layered on the base `svc`.

  Takes the member codes of a large SNOMED-backed VSAC set, strips their
  displays, and re-registers them as a stored extensional ValueSet
  overlaid on the fixture service. Because the members lack a stored
  display, `stored-extensional-answerable?` is false and expand/validate
  fall back to `expand-include-concepts`, which looks up EVERY member
  against the real Hermes CodeSystem to backfill the display. Layering
  over the live service (not a synthetic CodeSystem whose lookups miss for
  free) is what makes those lookups cost real I/O — the genuine cliff.

  Returns `{:svc overlaid-service :sample a-member-code}`. Memoised on the
  base service so the one-time build stays out of the timed loop."
  (memoize
   (fn [svc]
     (let [members (->> (hades/expand svc {:url "http://cts.nlm.nih.gov/fhir/ValueSet/2.16.840.1.113762.1.4.1018.240"
                                           :count 100000})
                        :concepts
                        (mapv (fn [{:keys [system code]}] {"system" system "code" code})))
           vs (load-fhir/from-fhir
               {"resourceType" "ValueSet"
                "url" missing-display-vs-url
                "status" "active"
                "expansion" {"contains" members}})]
       {:svc    (composite/with-overlays svc [vs])
        :sample (get (first members) "code")}))))

;; ─── Benchmark catalogue ───────────────────────────────────────────────────

(def operations
  [;; --- $lookup -----------------------------------------------------------
   {:id :lookup/minimal :tx-bench "LK01"
    :fn (fn [svc]
          (hades/lookup svc {:system snomed-uri :code (str multiple-sclerosis)}))}
   ;; FHIR `_property=*` wildcard — same observable output as no
   ;; filter, but goes through the `property-filter/parse` predicate
   ;; bundle on every section. A wide gap vs `:lookup/minimal` would
   ;; flag the gating overhead.
   {:id :lookup/property-wildcard :tx-bench "LK01"
    :fn (fn [svc]
          (hades/lookup svc {:system snomed-uri :code (str multiple-sclerosis)
                             :properties ["*"]}))}
   ;; FHIR `_property=` filter — slice-only path. Skips designations,
   ;; concrete-values and attribute relationships entirely. Should be
   ;; meaningfully cheaper than `:lookup/full` if the filter is doing
   ;; its job.
   {:id :lookup/property-parent-only :tx-bench "LK01"
    :fn (fn [svc]
          (hades/lookup svc {:system snomed-uri :code (str multiple-sclerosis)
                             :properties ["parent"]}))}
   {:id :lookup/loinc :tx-bench "LK02"
    :fn (fn [svc]
          (hades/lookup svc {:system loinc-uri :code "8867-4"}))}
   {:id :lookup/loinc-all :tx-bench "LK02"
    :fn (fn [svc]
          (run! (fn [code]
                  (hades/lookup svc {:system loinc-uri :code code}))
                loinc-lk02-pool))}
   {:id :lookup/nonexistent :tx-bench "LK05"
    :fn (fn [svc]
          (hades/lookup svc {:system snomed-uri :code "999973211009"}))}

   ;; --- $validate-code ----------------------------------------------------
   {:id :validate-code/plain :tx-bench "VC01"
    :fn (fn [svc]
          (hades/validate-code svc {:system snomed-uri :code (str diabetes-mellitus)}))}
   {:id :validate-code/display :tx-bench "VC02"
    :fn (fn [svc]
          (hades/validate-code svc {:system snomed-uri :code (str diabetes-mellitus)
                                    :display "Diabetes mellitus"}))}
   {:id :validate-code/against-valueset :tx-bench "VC03"
    :fn (fn [svc]
          (hades/validate-code svc
            {:url    (expand-url (str "ecl/<<" disease))
             :system snomed-uri :code (str diabetes-mellitus)}))}
   ;; Display-mismatch path — exercises the descriptions scan + active
   ;; display enumeration + invalid-display issue building. Hot when
   ;; clients send displays drifted from the current release.
   {:id :validate-code/wrong-display :tx-bench "VC02"
    :fn (fn [svc]
          (hades/validate-code svc {:system snomed-uri :code (str diabetes-mellitus)
                                    :display "completely-wrong-label"}))}
   ;; CodeableConcept aggregation — the composite layer fans out to
   ;; per-coding $validate-code and selects a winner. Mirrors the
   ;; common HAPI/IPS workload that ships several codings per
   ;; observation. Three codings: one valid SNOMED, one wrong-system,
   ;; one wrong-code.
   {:id :validate-code/codeable-concept
    :fn (fn [svc]
          (hades/validate-codeable-concept
            svc
            [{:system snomed-uri :code (str diabetes-mellitus)}
             {:system "http://example.com/fake" :code "x"}
             {:system snomed-uri :code "999999999"}]
            {:url (expand-url (str "ecl/<<" disease))}))}

   ;; --- $subsumes — all four branches -------------------------------------
   {:id :subsumes/equivalent :tx-bench "SS01"
    :fn (fn [svc]
          (hades/subsumes svc {:systemA snomed-uri :codeA (str diabetes-mellitus)
                               :systemB snomed-uri :codeB (str diabetes-mellitus)}))}
   {:id :subsumes/subsumes :tx-bench "SS01"
    :fn (fn [svc]
          (hades/subsumes svc {:systemA snomed-uri :codeA (str disease)
                               :systemB snomed-uri :codeB (str influenza)}))}
   {:id :subsumes/subsumed-by :tx-bench "SS01"
    :fn (fn [svc]
          (hades/subsumes svc {:systemA snomed-uri :codeA (str type-2-dm)
                               :systemB snomed-uri :codeB (str diabetes-mellitus)}))}
   ;; Historical-equivalence fallback — exercises the unrelated-pair branch
   ;; that walks the historical-association refsets.
   {:id :subsumes/unrelated :tx-bench "SS01"
    :fn (fn [svc]
          (hades/subsumes svc {:systemA snomed-uri :codeA (str asthma)
                               :systemB snomed-uri :codeB (str influenza)}))}

   ;; --- $translate --------------------------------------------------------
   ;; 56485000 is retired with a SAME-AS pointing to 398390002 — exercises
   ;; the real historical-association lookup, not the no-hits fast path.
   {:id :translate/historical :tx-bench "CM01"
    :fn (fn [svc]
          (hades/translate svc
            {:url    (str snomed-uri "?fhir_cm=900000000000526001")
             :system snomed-uri :code "56485000"
             :target snomed-uri}))}
   {:id :translate/loinc-map-to
    :fn (fn [svc]
          (hades/translate svc
            {:url (:url (loinc-model/conceptmap :map-to))
             :system loinc-uri :code "1009-0"
             :target loinc-uri}))}
   {:id :translate/loinc-part-related
    :fn (fn [svc]
          (hades/translate svc
            {:url (loinc-model/part-related-conceptmap-url snomed-uri)
             :system loinc-uri :code "LP14449-0"
             :target snomed-uri}))}
   {:id :translate/loinc-ieee
    :fn (fn [svc]
          (hades/translate svc
            {:url (:url (loinc-model/conceptmap :ieee-medical-device))
             :system loinc-uri :code "11556-8"
             :target ieee-uri}))}
   {:id :translate/loinc-rsna-rid
    :fn (fn [svc]
          (hades/translate svc
            {:url (:url (loinc-model/conceptmap :rsna-rid))
             :system loinc-uri :code "24531-6"
             :target radlex-uri}))}
   {:id :translate/loinc-rsna-rpid-reverse
    :fn (fn [svc]
          (hades/translate svc
            {:url (:url (loinc-model/conceptmap :rsna-rpid))
             :system rsna-playbook-uri :code "RPID2142"
             :target loinc-uri}))}

   ;; --- $expand via implicit-VS URLs --------------------------------------
   {:id :expand/ecl-small :tx-bench "EX01"
    :fn (fn [svc]
          (hades/expand svc {:url (expand-url (str "ecl/<<" diabetes-mellitus)) :count 50}))}
   {:id :expand/ecl-medium :tx-bench "EX01"
    :fn (fn [svc]
          (hades/expand svc {:url (expand-url (str "ecl/<<" asthma)) :count 100}))}
   {:id :expand/ecl-large :tx-bench "EX01"
    :fn (fn [svc]
          (hades/expand svc {:url (expand-url (str "ecl/<<" clinical-finding)) :count 100}))}
   {:id :expand/isa :tx-bench "EX01"
    :fn (fn [svc]
          (hades/expand svc {:url (expand-url (str "isa/" clinical-finding)) :count 100}))}
   {:id :expand/text-filter-rare
    :fn (fn [svc]
          (hades/expand svc {:url (expand-url (str "ecl/<<" clinical-finding))
                             :filter "asthma" :count 20}))}
   {:id :expand/text-filter-common
    :fn (fn [svc]
          (hades/expand svc {:url (expand-url (str "ecl/<<" clinical-finding))
                             :filter "pain" :count 20}))}
   {:id :expand/paged :tx-bench "EX01"
    :fn (fn [svc]
          (hades/expand svc {:url (expand-url (str "ecl/<<" clinical-finding))
                             :offset 1000 :count 20}))}
   {:id :expand/all-snomed-text-filter :tx-bench "EX03"
    :fn (fn [svc]
          (hades/expand svc {:url (str snomed-uri "?fhir_vs")
                             :filter "diabetes" :count 100}))}
   ;; Refset-driven implicit VS — different Lucene path from ECL/isa
   ;; (`q-memberOf` + per-page inactive check). 900000000000527005 is
   ;; the SAME-AS historical-association refset; ships in every
   ;; International release and contains thousands of members.
   {:id :expand/refset
    :fn (fn [svc]
          (hades/expand svc {:url (expand-url "refset/900000000000527005")
                             :count 100}))}
   ;; Paginated ECL with `displayLanguage` — exercises locale-specific
   ;; designation selection at scale. Different cost profile from
   ;; `:lookup/full` (single concept) because designations are read
   ;; per row across the page.
   {:id :expand/with-display-language
    :fn (fn [svc]
          (hades/expand svc {:url (expand-url (str "ecl/<<" clinical-finding))
                             :count 100 :displayLanguage "en-US"}))}

   ;; --- $expand via hand-built compose (bypasses URL parsing) -------------
   {:id :compose/is-a :tx-bench "EX02"
    :fn (fn [svc]
          (compose/expand-compose svc
            {"include" [{"system" snomed-uri
                         "filter" [{"property" "concept" "op" "is-a" "value" (str clinical-finding)}]}]}
            {:count 100}))}

   ;; --- 2×2 page-10 comparison for EX01-vs-EX02 investigation ------------
   ;; Same SCTID (clinical-finding), same page size (10), three paths into
   ;; SNOMED expansion. The implicit `isa/{id}` URL hits the
   ;; expand-paginated-query fast path (PagingDedupCollector early-term).
   ;; Compose paths translate to ECL (<< or <) and go through the generic
   ;; expand-paginated which materialises the full subtree to compute
   ;; :total. Expected: URL ≪ both compose entries.
   {:id :expand/isa-page-10 :tx-bench "EX01"
    :fn (fn [svc]
          (hades/expand svc {:url (expand-url (str "isa/" clinical-finding)) :count 10}))}
   {:id :compose/is-a-page-10 :tx-bench "EX02"
    :fn (fn [svc]
          (compose/expand-compose svc
            {"include" [{"system" snomed-uri
                         "filter" [{"property" "concept" "op" "is-a" "value" (str clinical-finding)}]}]}
            {:count 10}))}
   {:id :compose/descendent-of-page-10 :tx-bench "EX02"
    :fn (fn [svc]
          (compose/expand-compose svc
            {"include" [{"system" snomed-uri
                         "filter" [{"property" "concept" "op" "descendent-of" "value" (str clinical-finding)}]}]}
            {:count 10}))}
   {:id :compose/refinement :tx-bench "EX05"
    :fn (fn [svc]
          (compose/expand-compose svc
            {"include" [{"system" snomed-uri
                         "filter" [{"property" "concept" "op" "is-a" "value" (str asthma)}
                                   {"property" "363698007" "op" "=" "value" "89187006"}]}]}
            {:count 50}))}
   {:id :compose/multi-include :tx-bench "EX07"
    :fn (fn [svc]
          (compose/expand-compose svc
            {"include" [{"system" snomed-uri
                         "filter" [{"property" "concept" "op" "is-a" "value" (str asthma)}]}
                        {"system" snomed-uri
                         "filter" [{"property" "concept" "op" "is-a" "value" (str procedure)}]}]}
            {:count 100}))}
   {:id :compose/include-exclude
    :fn (fn [svc]
          (compose/expand-compose svc
            {"include" [{"system" snomed-uri
                         "filter" [{"property" "concept" "op" "is-a" "value" (str clinical-finding)}]}]
             "exclude" [{"system" snomed-uri
                         "filter" [{"property" "concept" "op" "is-a" "value" (str asthma)}]}]}
            {:count 100}))}
   {:id :compose/all-snomed-text-filter :tx-bench "EX07"
    :fn (fn [svc]
          (compose/expand-compose svc
            {"include" [{"system" snomed-uri}]}
            {:filter "pain" :count 200}))}
   {:id :expand/stored-extensional-page
    :fn (fn [_]
          (hades/expand stored-vs-svc {:url stored-vs-url
                                       :offset 10000
                                       :count 50}))}
   {:id :expand/stored-extensional-display-language
    :fn (fn [_]
          (hades/expand stored-vs-svc {:url stored-vs-url
                                       :displayLanguage "en"
                                       :offset 10000
                                       :count 50}))}
   {:id :expand/stored-extensional-filter
    :fn (fn [_]
          (hades/expand stored-vs-svc {:url stored-vs-url
                                       :filter "1999"
                                       :count 50}))}
   {:id :compose/refinement-text-filter :tx-bench "EX08"
    :fn (fn [svc]
          (compose/expand-compose svc
            {"include" [{"system" snomed-uri
                         "filter" [{"property" "concept" "op" "is-a" "value" (str clinical-finding)}
                                   {"property" "116676008" "op" "=" "value" "72704001"}]}]}
            {:filter "fracture" :count 50}))}

   ;; --- Concept discovery via SQLite LOINC -----------------------------------
   ;;
   ;; `cs-expand*` for SQLite catalogues fans out per surviving row to
   ;; fetch designations (when `displayLanguage` is set) and properties
   ;; (when a regex post-filter targets a property). LOINC 2.81 carries
   ;; ~66 multi-language designation rows per code, so `displayLanguage`
   ;; makes per-row designation reads dominate the cost profile (the 52%
   ;; slice flagged in todo.txt). These benches isolate that hot path.
     {:id :search-concepts/loinc-text-no-lang
      :fn (fn [svc]
            (protos/cs-expand* svc
              {:system loinc-uri :text "glucose" :max-hits 50}))}
     {:id :search-concepts/loinc-text-with-lang
      :fn (fn [svc]
            (protos/cs-expand* svc
              {:system loinc-uri :text "glucose" :max-hits 50 :displayLanguage "en"}))}
     {:id :search-concepts/loinc-text-large-with-lang
      :fn (fn [svc]
            (protos/cs-expand* svc
              {:system loinc-uri :text "glucose" :max-hits 200 :displayLanguage "en"}))}
     {:id :search-concepts/loinc-text-small-no-lang
      :fn (fn [svc]
            (protos/cs-expand* svc
              {:system loinc-uri :text "glucose" :max-hits 10}))}

   ;; --- FHIR REST search (FS01) -----------------------------------------
   ;;
   ;; The four shapes the FS01 pool exercises:
   ;;   *-by-url   — `?url=<canonical>` (1400/1800 of the pool).
   ;;                Pre-filter on metadata tuples collapses each
   ;;                provider to ≤1 surviving entry; one *-resource
   ;;                call per provider thereafter.
   ;;   *-browse   — unfiltered. Bounded by the per-provider walk;
   ;;                SQLite VS catalogue (~2.5k entries in smoke)
   ;;                drives the worst case.
   {:id :fs01/cs-by-url :tx-bench "FS01"
    :fn (fn [svc]
          (hades/search-code-systems svc
            {:url "http://hl7.org/fhir/sid/icd-10"}))}
   {:id :fs01/vs-by-url :tx-bench "FS01"
    :fn (fn [svc]
          (hades/search-value-sets svc
            {:url "http://hl7.org/fhir/ValueSet/administrative-gender"}))}
   {:id :fs01/cs-browse :tx-bench "FS01"
    :fn (fn [svc] (hades/search-code-systems svc {}))}
   {:id :fs01/vs-browse :tx-bench "FS01"
    :fn (fn [svc] (hades/search-value-sets svc {}))}

   ;; --- $expand on VSAC extensional ValueSets (EX04) ---------------------
   ;;
   ;; Provider: FTRM (SQLite) — the fixture opens `.hades/vsac-0.24.db`.
   ;; The local tx-benchmark recipe instead drives the in-memory provider
   ;; (the unpacked package dir); both serve the same 9,071 ValueSets
   ;; (guarded by vsac_parity_live_test) but differ in latency, so don't
   ;; cross-compare these numbers with an in-memory EX04 run.
   ;;
   ;; Mirrors three large VSAC ValueSets from tx-benchmark's EX04 pool —
   ;; SNOMED CT US Core Problem List (.1018.240), LOINC results
   ;; (.1267.17), and microbiology organisms (.24.7.14). Together they
   ;; cover the cost classes exposed by the 2026-05-20 diagnose run:
   ;; small page, count=1000 (large extensional materialisation), and
   ;; text filter (rare vs common pre-trim).
   {:id :expand/vsac-snomed-small :tx-bench "EX04"
    :fn (fn [svc]
          (hades/expand svc {:url "http://cts.nlm.nih.gov/fhir/ValueSet/2.16.840.1.113762.1.4.1018.240"
                             :count 10}))}
   {:id :expand/vsac-snomed-1000 :tx-bench "EX04"
    :fn (fn [svc]
          (hades/expand svc {:url "http://cts.nlm.nih.gov/fhir/ValueSet/2.16.840.1.113762.1.4.1018.240"
                             :count 1000}))}
   {:id :expand/vsac-snomed-filter-rare :tx-bench "EX04"
    :fn (fn [svc]
          (hades/expand svc {:url "http://cts.nlm.nih.gov/fhir/ValueSet/2.16.840.1.113762.1.4.1018.240"
                             :filter "ergotamine" :count 20}))}
   {:id :expand/vsac-snomed-filter-common :tx-bench "EX04"
    :fn (fn [svc]
          (hades/expand svc {:url "http://cts.nlm.nih.gov/fhir/ValueSet/2.16.840.1.113762.1.4.1018.240"
                             :filter "hypertension" :count 100}))}
   {:id :expand/vsac-loinc-filter :tx-bench "EX04"
    :fn (fn [svc]
          (hades/expand svc {:url "http://cts.nlm.nih.gov/fhir/ValueSet/2.16.840.1.113762.1.4.1267.17"
                             :filter "hemoglobin" :count 100}))}
   {:id :expand/vsac-microbiology-filter :tx-bench "EX04"
    :fn (fn [svc]
          (hades/expand svc {:url "http://cts.nlm.nih.gov/fhir/ValueSet/2.16.840.1.114222.24.7.14"
                             :filter "streptococcus" :count 100}))}

   ;; --- $expand enrichment cliff (EX04) ----------------------------------
   ;;
   ;; The same two large stored-extensional VSAC ValueSets under four
   ;; request shapes, all `count=50`. `plain` and `filter` are answerable
   ;; from stored membership and take the `stored-extensional-expand` fast
   ;; path (sub-ms / single-digit ms). `activeOnly` and `properties`
   ;; request data the stored concepts don't carry, so they fall back to
   ;; `expand-include-concepts`, which issues one `cs-lookup` PER MEMBER
   ;; over the ENTIRE membership before paging — turning a 50-row page into
   ;; thousands of lookups (~1–9 s). This is the enrichment cliff; Phase 1
   ;; (restoring laziness) should collapse the fallback columns back to
   ;; single-digit ms. The slowness here is the recorded finding, not a
   ;; regression — these ops run for seconds on current HEAD.
   ;;
   ;;   .1018.240  ~6,134 SNOMED members
   ;;   .1267.17  ~18,566 LOINC members
   {:id :expand/cliff-snomed-plain :tx-bench "EX04"
    :fn (fn [svc]
          (hades/expand svc {:url "http://cts.nlm.nih.gov/fhir/ValueSet/2.16.840.1.113762.1.4.1018.240"
                             :count 50}))}
   {:id :expand/cliff-snomed-filter :tx-bench "EX04"
    :fn (fn [svc]
          (hades/expand svc {:url "http://cts.nlm.nih.gov/fhir/ValueSet/2.16.840.1.113762.1.4.1018.240"
                             :filter "a" :count 50}))}
   {:id :expand/cliff-snomed-active-only :tx-bench "EX04"
    :fn (fn [svc]
          (hades/expand svc {:url "http://cts.nlm.nih.gov/fhir/ValueSet/2.16.840.1.113762.1.4.1018.240"
                             :activeOnly true :count 50}))}
   {:id :expand/cliff-snomed-properties :tx-bench "EX04"
    :fn (fn [svc]
          (hades/expand svc {:url "http://cts.nlm.nih.gov/fhir/ValueSet/2.16.840.1.113762.1.4.1018.240"
                             :properties ["inactive"] :count 50}))}
   {:id :expand/cliff-loinc-plain :tx-bench "EX04"
    :fn (fn [svc]
          (hades/expand svc {:url "http://cts.nlm.nih.gov/fhir/ValueSet/2.16.840.1.113762.1.4.1267.17"
                             :count 50}))}
   {:id :expand/cliff-loinc-filter :tx-bench "EX04"
    :fn (fn [svc]
          (hades/expand svc {:url "http://cts.nlm.nih.gov/fhir/ValueSet/2.16.840.1.113762.1.4.1267.17"
                             :filter "a" :count 50}))}
   {:id :expand/cliff-loinc-active-only :tx-bench "EX04"
    :fn (fn [svc]
          (hades/expand svc {:url "http://cts.nlm.nih.gov/fhir/ValueSet/2.16.840.1.113762.1.4.1267.17"
                             :activeOnly true :count 50}))}
   {:id :expand/cliff-loinc-properties :tx-bench "EX04"
    :fn (fn [svc]
          (hades/expand svc {:url "http://cts.nlm.nih.gov/fhir/ValueSet/2.16.840.1.113762.1.4.1267.17"
                             :properties ["inactive"] :count 50}))}
   ;; Missing-display trigger (see `missing-display-overlay`). Real SNOMED
   ;; codes with displays stripped, so even a plain page and a single-code
   ;; validate must look every member up against Hermes to backfill the
   ;; display. `validate-code` matters as much as `expand` here: it reuses
   ;; the same `expand-compose` path (it does NOT narrow explicit
   ;; `concept[]` includes), so validating one code against this set pays
   ;; the full N-member enrichment — the Phase 3 payoff this op records.
   {:id :expand/cliff-missing-display :tx-bench "EX04"
    :fn (fn [svc]
          (hades/expand (:svc (missing-display-overlay svc))
                        {:url missing-display-vs-url :count 50}))}
   {:id :validate-code/cliff-missing-display :tx-bench "EX04"
    :fn (fn [svc]
          (let [{ov :svc sample :sample} (missing-display-overlay svc)]
            (hades/validate-code ov {:url     missing-display-vs-url
                                     :system  snomed-uri
                                     :code    sample
                                     :display "deliberately drifted label"})))}])

;; ─── Test entry point (clj -M:bench) ──────────────────────────────────────
;;
;; Two modes, picked via JVM system property `hades.bench.mode`:
;;
;;   :quick (default)  full criterium quick-bench — ~5s per entry.
;;   :smoke            criterium quick-bench with `:samples 1`,
;;                     `:warmup-jit-period 0` and a 100ms target
;;                     execution window — fast sanity check (~hundreds
;;                     of ms per entry).
;;
;;   clj -M:bench -J-Dhades.bench.mode=smoke

(def ^:private smoke-opts
  ;; Criterium needs ≥3 samples to compute quartiles/outliers; trim
  ;; warmup and per-sample window to the floor instead.
  [:samples 3
   :warmup-jit-period (* 50 1000000)
   :target-execution-time (* 50 1000000)
   :max-gc-attempts 0])

(defn- bench-mode []
  (keyword (System/getProperty "hades.bench.mode" "quick")))

(defn- run-one [mode f svc]
  (case mode
    :quick (crit/quick-benchmark* (fn [] (f svc)) {})
    :smoke (crit/quick-benchmark* (fn [] (f svc)) (apply hash-map smoke-opts))))

(def ^:private results-path "target/bench-results.json")

(defn- summarise [{:keys [id tx-bench]} r]
  ;; Criterium reports :mean / :lower-q / :upper-q as [value variance]
  ;; pairs in seconds. We keep the central value — variance is just
  ;; noise in a per-op delta table.
  (cond-> {:id      (str id)
           :mean    (first (:mean r))
           :lower-q (first (:lower-q r))
           :upper-q (first (:upper-q r))
           :samples (count (:samples r))}
    tx-bench (assoc :tx-bench tx-bench)))

(defn- git-sha []
  (try
    (let [{:keys [exit out]} (shell/sh "git" "rev-parse" "--short" "HEAD")]
      (if (zero? exit) (str/trim out) "unknown"))
    (catch Exception _ "unknown")))

(defn- write-results! [mode results]
  (let [doc {:meta    {:mode      (name mode)
                       :sha       (git-sha)
                       :timestamp (str (java.time.Instant/now))}
             :results results}
        f   (io/file results-path)]
    (io/make-parents f)
    (with-open [w (io/writer f)]
      (json/write doc w))
    (println "wrote" results-path)))

(defn operation
  "Return the benchmark operation with `id`."
  [id]
  (let [id (keyword id)]
    (or (some #(when (= id (:id %)) %) operations)
        (throw (ex-info "Unknown benchmark operation"
                        {:id id :available (mapv :id operations)})))))

(defn bench-operation
  "Run one benchmark operation against an already-open service."
  ([svc id] (bench-operation svc id (bench-mode)))
  ([svc id mode]
   (let [{:keys [fn] :as entry} (operation id)
         r (run-one mode fn svc)]
     (crit/report-result r)
     (summarise entry r))))

(defn bench-one!
  "Open the standard fixture service and run one benchmark operation.

  Intended for REPL microbenchmarking:
    (bench-one! :translate/loinc-part-related)"
  ([id] (bench-one! (if (map? id) (:id id) id) (bench-mode)))
  ([id mode]
   (let [svc (hades/open [fixtures/snomed-db-path
                          fixtures/loinc-db-path
                          fixtures/vsac-db-path
                          fixtures/fhir-smoke-db-path])]
     (try
       (bench-operation svc id mode)
       (finally
         (hades/close svc))))))

(deftest operations-bench
  (let [svc  (hades/open [fixtures/snomed-db-path
                          fixtures/loinc-db-path
                          fixtures/vsac-db-path
                          fixtures/fhir-smoke-db-path])
        mode (bench-mode)]
    (try
      (let [results (mapv (fn [{:keys [id fn] :as entry}]
                            (println "\n***" id)
                            (let [r (run-one mode fn svc)]
                              (crit/report-result r)
                              (summarise entry r)))
                          operations)]
        (write-results! mode results))
      (finally
        (hades/close svc)))))
