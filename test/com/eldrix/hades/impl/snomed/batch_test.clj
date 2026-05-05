(ns com.eldrix.hades.impl.snomed.batch-test
  "Smoke test for `snomed.batch`'s dependency on a Hermes private var.

  `batch.clj` reaches into `com.eldrix.hermes.impl.store/preferred-description*`
  to amortise LMDB transaction-open cost across N concept-id lookups. If
  Hermes ever renames or removes that var, we want to fail loudly *here*
  with a clear message — not at the first request a user issues to a
  running server."
  (:require [clojure.test :refer [deftest is testing]]
            [com.eldrix.hades.fixtures :as fixtures]
            [com.eldrix.hades.impl.snomed.batch :as batch]
            [com.eldrix.hermes.core :as hermes]))

(deftest hermes-private-var-resolves
  (testing "Hermes' private `preferred-description*` var still exists"
    (is (some? (find-var 'com.eldrix.hermes.impl.store/preferred-description*))
        (str "Hermes' internal preferred-description* var is gone — "
             "snomed.batch/preferred-terms is broken. "
             "Either upstream a public batch helper or update batch.clj."))))

(deftest ^:live preferred-terms-end-to-end
  (testing "preferred-terms returns a map keyed by concept-id"
    (with-open [svc (hermes/open (fixtures/assert-snomed-db!))]
      (let [;; UK English language refset
            lang-refsets [900000000000509007]
            ;; A small batch of well-known SNOMED concepts
            cids [73211009 22298006 195967001]
            result (batch/preferred-terms svc lang-refsets cids)]
        (is (map? result))
        (is (= (set cids) (set (keys result))))
        (is (every? (some-fn nil? string?) (vals result))
            "every value is a preferred-term string or nil (no synonym in refsets)")))))
