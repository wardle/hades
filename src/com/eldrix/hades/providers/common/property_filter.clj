(ns com.eldrix.hades.providers.common.property-filter
  "Parses the FHIR `_property=…` request filter into a tiny predicate
  bundle consumed by every `cs-lookup` provider.

  Centralised because `sqlite/provider`, `in_memory` and `snomed` must
  emit identical sections for the same filter — drift between providers
  is a parity bug visible to any caller that fans out a multi-system
  `$lookup`.")

(set! *warn-on-reflection* true)

(def ^:private slice-keys #{"designation" "parent" "child"})

(defn parse
  "Parse `properties` (the seq of strings from a `_property=…` request
  param) into:

    `:want?`        `(string -> bool)` — include this slice / typed
                                          property code?
    `:want-typed?`  `bool`              — include any non-slice (typed)
                                          properties at all?

  `nil`, an empty seq, or a seq containing the FHIR wildcard `*` are
  all `\"return everything\"`. Slice codes `designation` / `parent` /
  `child` gate their respective result sections; any other entry is a
  typed-property code (e.g. `inactive`, `sufficientlyDefined`, a SNOMED
  attribute id)."
  [properties]
  (let [want (when (seq properties)
               (let [s (set properties)]
                 (when-not (contains? s "*") s)))]
    {:want?       (fn [k] (or (nil? want) (contains? want k)))
     :want-typed? (or (nil? want) (some #(not (slice-keys %)) want))}))
