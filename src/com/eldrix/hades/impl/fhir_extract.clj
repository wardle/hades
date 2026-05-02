(ns com.eldrix.hades.impl.fhir-extract
  "FHIR-resource extraction helpers shared between the FHIR loader and
  in-memory provider. All inputs are parsed FHIR JSON maps with string
  keys; outputs are keyword-keyed Clojure data, with one exception:
  concept `:properties` stay as raw FHIR string-keyed property maps so
  every consumer applies the same value-extraction rules.")

(def value-keys
  "FHIR `value[x]` polymorphic keys, in priority order."
  ["valueCode" "valueCoding" "valueString" "valueInteger"
   "valueBoolean" "valueDecimal" "valueDateTime"])

(def standards-status-ext
  "http://hl7.org/fhir/StructureDefinition/structuredefinition-standards-status")

(def valueset-supplement-ext
  "http://hl7.org/fhir/StructureDefinition/valueset-supplement")

(defn extract-standards-status
  "Read the standards-status extension (deprecated/withdrawn/trial-use/etc.)
  from a FHIR resource's top-level extensions. Returns nil when absent."
  [metadata]
  (some (fn [ext]
          (when (= standards-status-ext (get ext "url"))
            (get ext "valueCode")))
        (get metadata "extension")))

(defn extract-vs-supplements
  "Read all valueset-supplement extension canonicals from VS metadata.
  Returns nil when the VS declares no supplement extensions."
  [metadata]
  (let [refs (keep (fn [ext]
                     (when (= valueset-supplement-ext (get ext "url"))
                       (get ext "valueCanonical")))
                   (get metadata "extension"))]
    (when (seq refs) (vec refs))))

(defn extract-property-value
  "Extract the value from a FHIR concept property, which uses one of
  several typed keys: valueCode, valueCoding, valueString, valueInteger,
  etc."
  [prop]
  (reduce (fn [_ k]
            (let [v (get prop k ::not-found)]
              (if (not= v ::not-found)
                (reduced v)
                nil)))
          nil value-keys))

(defn typed-property-value
  "Like `extract-property-value` but returns the value with a Clojure
  type that encodes the original FHIR `value[x]` field, so downstream
  rendering can choose CodeType vs StringType vs IntegerType etc. Coded
  values come back as keywords; everything else keeps its native type."
  [prop]
  (reduce (fn [_ k]
            (let [v (get prop k ::not-found)]
              (if (not= v ::not-found)
                (reduced (case k
                           "valueCode" (if (string? v) (keyword v) v)
                           v))
                nil)))
          nil value-keys))

(def ^:const max-concept-depth
  "Maximum nesting depth for FHIR CodeSystem.concept[].concept[] trees.
  Real CodeSystems are flat or shallow; deeper trees are almost
  certainly an attack or a malformed file."
  64)

(defn flatten-concepts
  "Recursively flatten nested FHIR CodeSystem concepts into a flat
  sequence, tracking the parent code for hierarchy building. Each output
  entry is keyword-keyed; :properties stays in raw FHIR form.

  Throws `ex-info` `:reason :max-depth-exceeded` if nesting exceeds
  `max-concept-depth` — guards both stack and pathological inputs."
  ([concepts] (flatten-concepts concepts nil 0))
  ([concepts parent-code] (flatten-concepts concepts parent-code 0))
  ([concepts parent-code depth]
   (when (> depth max-concept-depth)
     (throw (ex-info (str "CodeSystem concept nesting exceeds the configured limit of "
                          max-concept-depth)
                     {:reason :max-depth-exceeded :depth depth})))
   (mapcat (fn [c]
             (let [code (get c "code")
                   designations (mapv (fn [d]
                                        (let [use-map (get d "use")]
                                          (cond-> {:value (get d "value")}
                                            (get d "language")
                                            (assoc :language (keyword (get d "language")))
                                            use-map
                                            (assoc :use (cond-> {:system (get use-map "system")
                                                                 :code   (get use-map "code")}
                                                          (get use-map "display")
                                                          (assoc :display (get use-map "display")))))))
                                      (get c "designation"))
                   entry {:code        code
                          :display     (get c "display")
                          :definition  (get c "definition")
                          :designations designations
                          :properties  (get c "property")
                          :parent-code parent-code}
                   children (get c "concept")]
               (cons entry (when children (flatten-concepts children code (inc depth))))))
           concepts)))

(defn behavioural-fields
  "Return the behavioural-field sub-map plucked from a FHIR CodeSystem
  resource (parsed JSON, string keys)."
  [cs-map]
  (cond-> {}
    (contains? cs-map "url") (assoc :url (get cs-map "url"))
    (contains? cs-map "version") (assoc :version (get cs-map "version"))
    (contains? cs-map "name") (assoc :name (get cs-map "name"))
    (contains? cs-map "title") (assoc :title (get cs-map "title"))
    (contains? cs-map "status") (assoc :status (get cs-map "status"))
    (contains? cs-map "experimental") (assoc :experimental (get cs-map "experimental"))
    (contains? cs-map "description") (assoc :description (get cs-map "description"))
    (contains? cs-map "content") (assoc :content (get cs-map "content"))
    (contains? cs-map "caseSensitive") (assoc :case-sensitive (get cs-map "caseSensitive"))
    (contains? cs-map "hierarchyMeaning") (assoc :hierarchy-meaning (get cs-map "hierarchyMeaning"))
    (contains? cs-map "supplements") (assoc :supplements-target (get cs-map "supplements"))
    true (assoc :standards-status (extract-standards-status cs-map))))

(defn cs-passthrough-metadata
  "FHIR pass-through metadata (string-keyed) preserved on the provider
  for callers that need round-trip access to the original resource. The
  behavioural fields are excluded because they live as keyword-keyed
  slots on the provider directly."
  [cs-map]
  (select-keys cs-map ["resourceType" "name" "title" "status"
                       "description" "experimental" "hierarchyMeaning"
                       "content" "caseSensitive" "compositional" "versionNeeded"
                       "count" "language" "extension" "supplements"]))

(defn vs-passthrough-metadata
  "FHIR pass-through metadata for ValueSet."
  [vs-map]
  (select-keys vs-map ["resourceType" "name" "title" "status"
                       "description" "experimental" "purpose"
                       "compose" "extension"]))

(defn build-property-uri-map
  "Build a {uri → concept-property-code} map from a CodeSystem's
  property definitions."
  [cs-map]
  (reduce (fn [m prop-def]
            (let [uri (get prop-def "uri")
                  code (get prop-def "code")]
              (if (and uri code)
                (assoc m uri code)
                m)))
          {}
          (get cs-map "property")))
