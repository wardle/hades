(ns com.eldrix.hades.impl.snomed.batch
  "Batched SNOMED helpers that reach into Hermes' private implementation
  namespaces. Isolated here so `snomed.clj` depends only on Hermes' public API."
  (:require [com.eldrix.hermes.impl.lmdb :as lmdb]
            [com.eldrix.hermes.impl.store :as hermes.store]
            [com.eldrix.hermes.snomed :as snomed])
  (:import (com.eldrix.hermes.impl.lmdb LmdbStore)))

(set! *warn-on-reflection* true)

;; Hermes exposes `preferred-synonym*` only as a one-concept-at-a-time public
;; API, and internally each call opens its own pair of LMDB transactions
;; (`:core` + `:refsets`). A single `$lookup` needs synonyms for ~15–40 related
;; concepts, so the naive loop pays N pairs of txn-open/close just to iterate
;; — dominant under concurrency. We reach into Hermes' (currently private)
;; `preferred-description*` so we can run the whole batch under one shared
;; txn pair. Move to a public Hermes helper once one exists.
(def ^:private preferred-description*
  @#'hermes.store/preferred-description*)

(defn preferred-terms
  "Resolve preferred synonyms for a collection of concept-ids under a single
  pair of LMDB transactions. Returns `{concept-id preferred-term-string}`;
  concepts with no preferred synonym in `lang-refset-ids` are recorded as
  nil-valued so repeat ids don't re-query. Returns nil when either collection
  is empty.

  See also: hermes `preferred-synonym*` for the single-id API."
  [svc lang-refset-ids concept-ids]
  (when (and (seq lang-refset-ids) (seq concept-ids))
    (let [^LmdbStore store (:store svc)
          synonym snomed/Synonym
          miss    ::miss]
      (lmdb/with-txn [core-txn store :core]
        (lmdb/with-txn [refsets-txn store :refsets]
          (persistent!
           (reduce (fn [m cid]
                     (if (identical? miss (get m cid miss))
                       (assoc! m cid
                               (:term (loop [rids lang-refset-ids]
                                        (when-let [rid (first rids)]
                                          (or (preferred-description*
                                               store core-txn refsets-txn
                                               cid synonym rid)
                                              (recur (rest rids)))))))
                       m))
                   (transient {})
                   concept-ids)))))))
