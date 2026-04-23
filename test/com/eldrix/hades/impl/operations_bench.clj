(ns com.eldrix.hades.impl.operations-bench
  "Criterium micro-benchmarks for Hades' FHIR terminology operations.

  Measurements are at the *registry* layer — HTTP parsing and wire
  serialisation are bypassed so changes in domain logic register
  cleanly.

  Two entry points:

  1. `clj -M:bench` — runs the whole catalogue. A single deftest
     iterates `operations` and calls `criterium.core/quick-bench` on
     each entry. The fixture opens the canonical pinned Hermes DB (see
     `com.eldrix.hades.snomed-db`; provision with `clj -X:build-db`),
     registers it as the SNOMED provider, runs the benches, then
     restores registry state.

  2. The REPL — `open-snomed!` once per session, then
     `(crit/quick-bench ((benchmarks :subsumes/unrelated)))`
     to run one by id. `close-snomed!` when done."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest use-fixtures]]
            [com.eldrix.hades.impl.compose :as compose]
            [com.eldrix.hades.impl.registry :as registry]
            [com.eldrix.hades.impl.snomed :as snomed]
            [com.eldrix.hades.impl.snomed.expansion :as expansion]
            [com.eldrix.hades.snomed-db :as snomed-db]
            [com.eldrix.hermes.core :as hermes]
            [criterium.core :as crit]))
(def ^:private snomed-uri "http://snomed.info/sct")

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

(declare ^:private state)

(defn- expand-direct
  "Call `expansion/expand` directly with the live Hermes svc and given
  ECL / active-only? flag. Bypasses the FHIR-shaping wrapper so the
  bench measures just the expansion engine. Requires `open-snomed!`
  to have run — `state` is defined further down."
  [ecl active-only?]
  (expansion/expand {:svc          (:svc @state)
                     :ecl          ecl
                     :active-only? active-only?}))

;; ─── Benchmark catalogue ───────────────────────────────────────────────────
;;
;; Each entry is `{:id namespaced-kw :fn zero-arg-fn}`. The id is the
;; human-readable label, the map key, and what the REPL filters by.
;; Group comments mark sections; order within a group is free.

(def operations
  [ ;; --- $lookup -----------------------------------------------------------
   {:id :lookup/minimal
    :fn #(registry/codesystem-lookup {:system snomed-uri :code (str multiple-sclerosis)})}
   {:id :lookup/full
    :fn #(registry/codesystem-lookup {:system snomed-uri :code (str multiple-sclerosis)
                                      :property ["designation" "parent" "child" "definition"]})}

   ;; --- $validate-code ----------------------------------------------------
   {:id :validate-code/plain
    :fn #(registry/codesystem-validate-code {:system snomed-uri :code (str diabetes-mellitus)})}
   {:id :validate-code/display
    :fn #(registry/codesystem-validate-code {:system snomed-uri :code (str diabetes-mellitus)
                                             :display "Diabetes mellitus"})}
   {:id :validate-code/against-valueset
    :fn #(registry/valueset-validate-code
           {:url (expand-url (str "ecl/<<" disease))
            :system snomed-uri :code (str diabetes-mellitus)})}

   ;; --- $subsumes — all four branches -------------------------------------
   {:id :subsumes/equivalent
    :fn #(registry/codesystem-subsumes {:systemA snomed-uri :codeA (str diabetes-mellitus)
                                        :systemB snomed-uri :codeB (str diabetes-mellitus)})}
   {:id :subsumes/subsumes
    :fn #(registry/codesystem-subsumes {:systemA snomed-uri :codeA (str disease)
                                        :systemB snomed-uri :codeB (str influenza)})}
   {:id :subsumes/subsumed-by
    :fn #(registry/codesystem-subsumes {:systemA snomed-uri :codeA (str type-2-dm)
                                        :systemB snomed-uri :codeB (str diabetes-mellitus)})}
   ;; Historical-equivalence fallback — the branch that previously threw
   ;; 'Don't know how to create ISeq from: java.lang.Long' on unrelated pairs.
   {:id :subsumes/unrelated
    :fn #(registry/codesystem-subsumes {:systemA snomed-uri :codeA (str asthma)
                                        :systemB snomed-uri :codeB (str influenza)})}

   ;; --- $translate --------------------------------------------------------
   ;; 56485000 is a retired concept with a SAME-AS pointing to 398390002 —
   ;; exercises the real historical-association lookup rather than the
   ;; no-hits fast path. Uses the REPLACED-BY historical refset
   ;; (900000000000526001) to match the implicit ConceptMap shape that
   ;; tx-benchmark's CM01 sends.
   {:id :translate/historical
    :fn #(registry/conceptmap-translate
           {:url    (str snomed-uri "?fhir_cm=900000000000526001")
            :system snomed-uri :code "56485000"
            :target snomed-uri})}

   ;; --- $expand via implicit-VS URLs --------------------------------------
   {:id :expand/ecl-small
    :fn #(registry/valueset-expand {:url (expand-url (str "ecl/<<" diabetes-mellitus)) :count 50})}
   {:id :expand/ecl-medium
    :fn #(registry/valueset-expand {:url (expand-url (str "ecl/<<" asthma)) :count 100})}
   {:id :expand/ecl-large
    :fn #(registry/valueset-expand {:url (expand-url (str "ecl/<<" clinical-finding)) :count 100})}
   {:id :expand/isa
    :fn #(registry/valueset-expand {:url (expand-url (str "isa/" clinical-finding)) :count 100})}
   {:id :expand/text-filter-rare
    :fn #(registry/valueset-expand {:url (expand-url (str "ecl/<<" clinical-finding))
                                    :filter "asthma" :count 20})}
   {:id :expand/text-filter-common
    :fn #(registry/valueset-expand {:url (expand-url (str "ecl/<<" clinical-finding))
                                    :filter "pain" :count 20})}
   {:id :expand/paged
    :fn #(registry/valueset-expand {:url (expand-url (str "ecl/<<" clinical-finding))
                                    :offset 1000 :count 20})}
   ;; Mirrors tx-benchmark's EX03 shape: implicit "all of SNOMED" VS +
   ;; text filter. This is the shape that regressed from p95 128 ms to
   ;; 11 s at 50 VUs in the preflight comparison.
   {:id :expand/all-snomed-text-filter
    :fn #(registry/valueset-expand {:url (str snomed-uri "?fhir_vs")
                                    :filter "diabetes" :count 100})}

   ;; --- Non-paginated direct calls to expansion/expand --------------------
   ;; Three ECL sizes × two active-only modes. Naming: :np/<size>-<active>
   {:id :np/asthma-active-only          :fn #(expand-direct (str "<<" asthma) true)}
   {:id :np/asthma-incl-inactive        :fn #(expand-direct (str "<<" asthma) false)}
   {:id :np/diabetes-active-only        :fn #(expand-direct (str "<<" diabetes-mellitus) true)}
   {:id :np/diabetes-incl-inactive      :fn #(expand-direct (str "<<" diabetes-mellitus) false)}
   {:id :np/disease-active-only         :fn #(expand-direct (str "<<" disease) true)}
   {:id :np/disease-incl-inactive       :fn #(expand-direct (str "<<" disease) false)}

   ;; --- $expand via hand-built compose (bypasses URL parsing) -------------
   {:id :compose/is-a
    :fn #(compose/expand-compose
           nil
           {"include" [{"system" snomed-uri
                        "filter" [{"property" "concept" "op" "is-a" "value" (str clinical-finding)}]}]}
           {:count 100})}
   ;; Mirrors tx-benchmark's EX05 shape: focus concept + attribute refinement.
   {:id :compose/refinement
    :fn #(compose/expand-compose
           nil
           {"include" [{"system" snomed-uri
                        "filter" [{"property" "concept" "op" "is-a" "value" (str asthma)}
                                  {"property" "363698007" "op" "=" "value" "89187006"}]}]}
           {:count 50})}
   {:id :compose/multi-include
    :fn #(compose/expand-compose
           nil
           {"include" [{"system" snomed-uri
                        "filter" [{"property" "concept" "op" "is-a" "value" (str asthma)}]}
                       {"system" snomed-uri
                        "filter" [{"property" "concept" "op" "is-a" "value" (str procedure)}]}]}
           {:count 100})}
   {:id :compose/include-exclude
    :fn #(compose/expand-compose
           nil
           {"include" [{"system" snomed-uri
                        "filter" [{"property" "concept" "op" "is-a" "value" (str clinical-finding)}]}]
            "exclude" [{"system" snomed-uri
                        "filter" [{"property" "concept" "op" "is-a" "value" (str asthma)}]}]}
           {:count 100})}

   ;; Mirrors tx-benchmark EX07's shape — system-only include + text filter,
   ;; no ECL bound. Exercises "all of SNOMED" text search.
   {:id :compose/all-snomed-text-filter
    :fn #(compose/expand-compose
           nil
           {"include" [{"system" snomed-uri}]}
           {:filter "pain" :count 200})}

   ;; Mirrors tx-benchmark EX08 — is-a + attribute refinement + text filter.
   {:id :compose/refinement-text-filter
    :fn #(compose/expand-compose
           nil
           {"include" [{"system" snomed-uri
                        "filter" [{"property" "concept" "op" "is-a" "value" (str clinical-finding)}
                                  {"property" "116676008" "op" "=" "value" "72704001"}]}]}
           {:filter "fracture" :count 50})}])

(def benchmarks
  "Map of id → zero-arg fn. Usage:
    (crit/quick-bench ((benchmarks :subsumes/unrelated)))"
  (reduce (fn [m {:keys [id fn]}] (assoc m id fn)) {} operations))

;; ─── Runner + service management ───────────────────────────────────────────

(defn run-benchmarks
  "Run every operation in catalogue order through criterium/quick-bench."
  []
  (doseq [{:keys [id fn]} operations]
    (println "\n***" id)
    (crit/quick-bench (fn))))

(defonce ^:private state (atom nil))

(defn open-snomed!
  "Open Hermes and register it as the SNOMED provider. Idempotent. Safe
  from the REPL or from a fixture."
  ([] (open-snomed! (snomed-db/assert-pinned-db!)))
  ([db-path]
   (or @state
       (let [svc (hermes/open db-path)
             snomed-svc (snomed/->HermesService svc)
             saved {:svc svc
                    :prior-cs @registry/codesystems
                    :prior-vs @registry/valuesets
                    :prior-cm @registry/conceptmaps}]
         (registry/register-codesystem snomed-uri snomed-svc)
         (registry/register-codesystem "http://snomed.info/sct|*" snomed-svc)
         (registry/register-codesystem "sct" snomed-svc)
         (registry/register-valueset snomed-uri snomed-svc)
         (registry/register-valueset "http://snomed.info/sct|*" snomed-svc)
         (registry/register-valueset "sct" snomed-svc)
         (registry/register-concept-map-provider snomed-svc)
         (reset! state saved)
         svc))))

(defn close-snomed!
  "Close Hermes and restore registry state. Idempotent."
  []
  (when-let [{:keys [svc prior-cs prior-vs prior-cm]} @state]
    (reset! registry/codesystems prior-cs)
    (reset! registry/valuesets   prior-vs)
    (reset! registry/conceptmaps prior-cm)
    (hermes/close svc)
    (reset! state nil)))

;; ─── Test-runner entry point (clj -M:bench) ───────────────────────────────

(defn- live-fixture [f]
  (if-not (.exists (io/file snomed-db/pinned-db-path))
    (println (str "\n*** Skipping benchmarks: no SNOMED DB at "
                  snomed-db/pinned-db-path
                  ". Run `clj -X:build-db` first.\n"))
    (try (open-snomed!) (f) (finally (close-snomed!)))))

(use-fixtures :once live-fixture)

(deftest ^:benchmark operations-bench
  (run-benchmarks))
