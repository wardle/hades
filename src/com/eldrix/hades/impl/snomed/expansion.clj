(ns com.eldrix.hades.impl.snomed.expansion
  "SNOMED concept-level $expand for Hades.

  Dispatches on `:limit`:

  - **Non-paginated** (no `:limit`) — one Lucene query for active
    concepts, a second for inactives (only when `active-only?` is
    false). Each stream is dedup'd and row-shaped via a single fused
    transducer; display comes from the description's stored
    preferred-term field.
  - **Paginated** (`:limit` supplied) — two phases. Phase 1 collects
    matching concept-ids via Hermes' DocValues-only longset collector
    (no stored-field reads). Sort primitive `long[]`, slice
    `[offset, offset+limit)`. Phase 2 runs a second Lucene query
    restricted to the page's concept-ids and reads preferred synonyms
    from the description-doc stored field.

  Every Hades description doc stores the concept's preferred synonym
  keyed by each language-refset-id, so any representative doc per
  concept yields the correct display — no LMDB in either path.

  This is the only Hades namespace allowed to import from
  `com.eldrix.hermes.impl.*` — see plan/concept-expansion.md §3."
  (:require [clojure.string :as str]
            [com.eldrix.hermes.core :as hermes]
            [com.eldrix.hermes.impl.ecl :as hermes.ecl]
            [com.eldrix.hermes.impl.search :as hermes.search])
  (:import (com.eldrix.hermes.impl.lucene IntoLongSetCollectorManager)
           (com.eldrix.hermes.snomed Result)
           (java.util Arrays)
           (org.apache.lucene.document LongPoint)
           (org.apache.lucene.internal.hppc LongHashSet)
           (org.apache.lucene.search IndexSearcher Query)))

(set! *warn-on-reflection* true)

(defn- concept-ids-longs
  "Phase 1 collector that returns matching concept-ids as a primitive
  `long[]` directly from Lucene's `LongHashSet.toArray()` — no Clojure
  set round-trip, no Long boxing per element."
  ^longs [^IndexSearcher searcher ^Query q]
  (let [^LongHashSet s (.search searcher q (IntoLongSetCollectorManager. "concept-id"))]
    (.toArray s)))

(defn- inactive-in
  "Set of concept-ids from `cids` whose concept is inactive.
  DocValues-only — no stored-field reads."
  [^IndexSearcher searcher ^longs cids]
  (if (zero? (alength cids))
    #{}
    (hermes.search/do-query-for-concept-ids
     searcher
     (hermes.search/q-and [(LongPoint/newSetQuery "concept-id" cids)
                           (hermes.search/q-concept-active false)]))))

(defn- result->row
  [^Result r inactive-ids]
  (cond-> {:conceptId (.conceptId r)}
    (.preferredTerm r) (assoc :display (.preferredTerm r))
    (contains? inactive-ids (.conceptId r)) (assoc :inactive true)))

(defn- result->row-active
  "Row builder for the active stream — never inactive."
  [^Result r]
  (cond-> {:conceptId (.conceptId r)}
    (.preferredTerm r) (assoc :display (.preferredTerm r))))

(defn- result->row-inactive
  "Row builder for the inactive stream — always inactive."
  [^Result r]
  (cond-> {:conceptId (.conceptId r), :inactive true}
    (.preferredTerm r) (assoc :display (.preferredTerm r))))

(defn- build-query
  "Compose ECL ∩ filter ∩ active into a single Lucene Query.

  `filter` is interpreted as autocomplete-style token-prefix match (each
  token must appear in the description either exactly or as a prefix —
  see `hermes.search/q-term` and `make-autocomplete-tokens-query`).
  This is the consensus interpretation among open-source FHIR terminology
  servers; FHIR R4 leaves the mechanism implementation-defined (see
  `plan/concept-expansion.md §2.1`).

  The filter is Unicode-folded via `hermes.ecl/fold` before tokenisation
  so it matches the pre-folded `nterm` field (StandardAnalyzer lowercases
  but does not strip accents). Mirrors Hermes' own ECL `matchSearchTerm`
  handling in `impl.ecl`."
  ^Query [svc ecl {:keys [filter active-only?]}]
  (let [q1       (hermes.ecl/parse svc ecl)
        filter-q (when-not (str/blank? filter)
                   (hermes.search/q-term (hermes.ecl/fold svc filter)))
        active-q (when active-only? (hermes.search/q-concept-active true))
        clauses  (cond-> [q1]
                   filter-q (conj filter-q)
                   active-q (conj active-q))]
    (hermes.search/q-and clauses)))

(defn- expand-non-paginated
  "Single-pass expansion for callers that don't set `:limit`.

  Parses the ECL once, then issues one Lucene query for active
  concepts and (when `active-only?` is false) a second for inactives.
  Each stream feeds a single fused transducer — `distinct-by` +
  row-builder in one pass — so there's no intermediate vector.
  Display is read from the description's `preferredTerm` stored
  field; every description doc of a concept stores that concept's
  preferred synonym for each refset, so whichever representative
  wins the dedupe yields the same display."
  [svc ecl {:keys [active-only? filter language-range]}]
  (let [lang-ids  (hermes/match-locale svc language-range true)
        ^IndexSearcher searcher (:searcher svc)
        ecl-q     (hermes.ecl/parse svc ecl)
        filter-q  (when-not (str/blank? filter)
                    (hermes.search/q-term (hermes.ecl/fold svc filter)))
        base-cls  (cond-> [ecl-q]
                    filter-q (conj filter-q))
        run       (fn [active? row-fn]
                    (let [q (hermes.search/q-and
                             (conj base-cls (hermes.search/q-concept-active active?)))]
                      (into [] (comp (hermes.search/distinct-by #(.conceptId ^Result %))
                                     (map row-fn))
                            (hermes.search/do-query-for-results searcher q lang-ids))))
        active    (run true result->row-active)
        concepts  (if active-only?
                    active
                    (into active (run false result->row-inactive)))]
    {:concepts concepts :total (count concepts)}))

(defn- expand-paginated
  "Two-phase paginated expansion.

  Phase 1: collect matching concept-ids (DocValues-only, no stored-field
  reads), primitive `long[]` sort, slice `[offset, offset+limit)`.

  Phase 2: Lucene re-query restricted to the page's concept-ids;
  dedupe and pick display from the first representative description
  doc per concept. No LMDB in either phase."
  [svc ecl {:keys [active-only? offset limit language-range] :as opts}]
  (let [lang-ids  (hermes/match-locale svc language-range true)
        ^IndexSearcher searcher (:searcher svc)
        q         (build-query svc ecl opts)
        all-arr   (concept-ids-longs searcher q)
        _         (Arrays/sort all-arr)
        total     (alength all-arr)
        from      (int (min (max 0 (int (or offset 0))) total))
        to        (int (min total (+ from (int limit))))
        plen      (- to from)
        page      (long-array plen)
        _         (System/arraycopy all-arr from page 0 plen)]
    (if (zero? plen)
      {:concepts [] :total total}
      (let [page-q   (LongPoint/newSetQuery "concept-id" page)
            by-cid   (persistent!
                      (reduce (fn [m ^Result r]
                                (let [cid (.conceptId r)]
                                  (if (contains? m cid) m (assoc! m cid r))))
                              (transient {})
                              (hermes.search/do-query-for-results searcher page-q lang-ids)))
            inactive (if active-only? #{} (inactive-in searcher page))
            rows     (loop [i 0, acc (transient [])]
                       (if (< i plen)
                         (let [cid (aget page i)
                               r   (get by-cid cid)]
                           (recur (inc i)
                                  (if r
                                    (conj! acc (result->row r inactive))
                                    acc)))
                         (persistent! acc)))]
        {:concepts rows :total total}))))

(defn expand
  "Expand an ECL expression to a FHIR-shaped concept-level result.

  Dispatches on `:limit`:
  - omitted  → single-pass stream + dedupe (`expand-non-paginated`).
  - supplied → two-phase; Phase 1 builds the concept-id set, Phase 2
    re-queries for the page's displays (`expand-paginated`).

  Params (map):
    :svc             Hermes Svc                              (required)
    :ecl             ECL expression string                   (required)
    :filter          FHIR `filter` substring (case-insensitive)
    :active-only?    Exclude inactive concepts (default false)
    :offset          Integer, default 0 (ignored when :limit omitted)
    :limit           Integer or nil
    :language-range  Accept-Language-style string

  Returns:
    {:concepts [{:conceptId <long>
                 :display   <string or nil>
                 :inactive  true  (only when concept is inactive)} ...]
     :total    <long>}"
  [{:keys [svc ecl limit] :as opts}]
  (if limit
    (expand-paginated svc ecl opts)
    (expand-non-paginated svc ecl opts)))

(comment
  ;; Rapid-iteration bench harness for `expand-non-paginated`.
  ;; Start an nREPL with criterium on the classpath (`clj -M:bench:nrepl`),
  ;; then evaluate below.

  #_{:clj-kondo/ignore [:duplicate-require]}
  (require '[com.eldrix.hermes.core :as hermes]
           '[criterium.core :as crit])

  (def svc (hermes/open "/Users/mark/Dev/hermes/snomed.db"))

  (def asthma            195967001)
  (def clinical-finding  404684003)

  (defn run [ecl opts]
    (expand (merge {:svc svc :ecl ecl} opts)))

  (:total (run (str "<<" asthma) {:active-only? true}))

  (crit/quick-bench (run (str "<<" asthma) {:active-only? true}))
  (crit/quick-bench (run (str "<<" clinical-finding)
                         {:filter "epilepsy" :active-only? true}))

  (hermes/close svc))
