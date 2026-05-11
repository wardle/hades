(ns com.eldrix.hades.bench
  "Criterium micro-benchmarks for Hades' FHIR terminology operations.
  Run via `clj -M:bench`; writes `target/bench-results.json`."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest]]
            [com.eldrix.hades.core :as hades]
            [com.eldrix.hades.fixtures :as fixtures]
            [com.eldrix.hades.impl.compose :as compose]
            [criterium.core :as crit]))

(set! *warn-on-reflection* true)

(def ^:private snomed-uri "http://snomed.info/sct")
(def ^:private loinc-uri  "http://loinc.org")

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
   {:id :compose/refinement-text-filter :tx-bench "EX08"
    :fn (fn [svc]
          (compose/expand-compose svc
            {"include" [{"system" snomed-uri
                         "filter" [{"property" "concept" "op" "is-a" "value" (str clinical-finding)}
                                   {"property" "116676008" "op" "=" "value" "72704001"}]}]}
            {:filter "fracture" :count 50}))}

   ;; --- $find-matches via SQLite LOINC ------------------------------------
   ;;
   ;; `cs-expand*` for SQLite catalogues fans out per surviving row to
   ;; fetch designations (when `displayLanguage` is set) and properties
   ;; (when a regex post-filter targets a property). LOINC 2.81 carries
   ;; ~66 multi-language designation rows per code, so `displayLanguage`
   ;; makes per-row designation reads dominate the cost profile (the 52%
   ;; slice flagged in todo.txt). These benches isolate that hot path.
   {:id :find-matches/loinc-text-no-lang
    :fn (fn [svc]
          (hades/find-matches svc
            {:system loinc-uri :text "glucose" :max-hits 50}))}
   {:id :find-matches/loinc-text-with-lang
    :fn (fn [svc]
          (hades/find-matches svc
            {:system loinc-uri :text "glucose" :max-hits 50 :displayLanguage "en"}))}
   {:id :find-matches/loinc-text-large-with-lang
    :fn (fn [svc]
          (hades/find-matches svc
            {:system loinc-uri :text "glucose" :max-hits 200 :displayLanguage "en"}))}
   {:id :find-matches/loinc-text-small-no-lang
    :fn (fn [svc]
          (hades/find-matches svc
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
    :fn (fn [svc] (hades/search-value-sets svc {}))}])

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

(deftest operations-bench
  (let [svc  (hades/open [fixtures/snomed-db-path
                          fixtures/loinc-db-path
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
