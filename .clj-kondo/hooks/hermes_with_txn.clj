(ns hooks.hermes-with-txn
  "Kondo hook for `com.eldrix.hermes.impl.lmdb/with-txn`. The macro shape is
  `(with-txn [sym store dbi] body...)` — a single binding with three forms,
  not pairs. We rewrite it for kondo into a plain `let` so the bound symbol
  is recognised inside the body."
  (:require [clj-kondo.hooks-api :as api]))

(defn with-txn [{:keys [node]}]
  (let [[binding & body] (rest (:children node))
        [sym store _dbi] (:children binding)
        new-node (api/list-node
                   (list* (api/token-node 'let)
                          (api/vector-node [sym store])
                          body))]
    {:node new-node}))
