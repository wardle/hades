(ns com.eldrix.hades.compose
  "Pure compose expansion engine for FHIR ValueSet compose definitions.

  Evaluates include/exclude/filter/valueSet-import to produce an expanded
  set of concepts. No HAPI, no atoms, no mutable state."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [com.eldrix.hades.registry :as registry]))

(s/def ::filter (s/nilable string?))
(s/def ::activeOnly (s/nilable boolean?))
(s/def ::offset (s/nilable nat-int?))
(s/def ::count (s/nilable nat-int?))
(s/def ::expanding (s/nilable set?))
(s/def ::expand-params (s/keys :opt-un [::filter ::activeOnly ::offset ::count ::expanding]))

(defn- parse-filters
  "Parse a FHIR compose include/exclude filter array into internal format."
  [filter-array]
  (mapv (fn [f]
          {:property (get f "property")
           :op       (get f "op")
           :value    (get f "value")})
        filter-array))

(defn- expand-include-concepts
  "Expand an include element that has an explicit concept list.
  Enriches each concept with display from CodeSystem lookup when available."
  [ctx system concepts]
  (map (fn [c]
         (let [code (get c "code")
               provided-display (get c "display")
               looked-up (when system
                           (registry/codesystem-lookup ctx {:system system :code code}))
               display (or provided-display (get looked-up "display"))]
           {:code    code
            :system  system
            :display display}))
       concepts))

(defn- expand-include-filters
  "Expand an include element that has filters, delegating to cs-find-matches."
  [ctx system filters]
  (registry/codesystem-find-matches ctx {:system system :filters (parse-filters filters)}))

(defn- expand-include-all
  "Expand an include element with just a system (no concept list, no filters).
  Returns all concepts from the CodeSystem."
  [ctx system]
  (registry/codesystem-find-matches ctx {:system system :filters nil}))

(defn- expand-valueset-refs
  "Expand referenced ValueSets, checking for circular references."
  [ctx valueset-urls expanding]
  (mapcat (fn [vs-url]
            (when (contains? expanding vs-url)
              (throw (ex-info "Circular ValueSet reference" {:type :invalid :url vs-url})))
            (or (registry/valueset-expand ctx {:url vs-url :expanding expanding})
                []))
          valueset-urls))

(defn- expand-include
  "Expand a single include element from a compose definition."
  [ctx include params]
  (let [system (get include "system")
        concepts (get include "concept")
        filters (get include "filter")
        vs-urls (get include "valueSet")
        expanding (or (:expanding params) #{})
        system-results (cond
                         concepts (expand-include-concepts ctx system concepts)
                         filters (expand-include-filters ctx system filters)
                         system (expand-include-all ctx system)
                         :else nil)
        vs-results (when (seq vs-urls)
                     (expand-valueset-refs ctx vs-urls expanding))]
    (if (and (some? system-results) (seq vs-results))
      (let [vs-set (set (map (fn [c] [(:system c) (:code c)]) vs-results))]
        (filter (fn [c] (contains? vs-set [(:system c) (:code c)])) system-results))
      (concat system-results vs-results))))

(defn- concept-key [c]
  [(:system c) (:code c)])

(s/fdef expand-compose
  :args (s/cat :ctx ::registry/ctx :compose map? :params ::expand-params))

(defn expand-compose
  "Expand a FHIR ValueSet compose definition into a sequence of concept maps.

  Parameters:
  - ctx     — overlay context (::registry/ctx)
  - compose — parsed compose map (string keys: \"include\", \"exclude\", \"inactive\")
  - params  — {:filter :activeOnly :offset :count :expanding}"
  [ctx compose params]
  (let [includes (get compose "include")
        excludes (get compose "exclude")
        inactive-allowed (get compose "inactive" true)
        included (into {} (map (fn [c] [(concept-key c) c]))
                        (mapcat #(expand-include ctx % params) includes))
        excluded (when (seq excludes)
                   (set (map concept-key
                             (mapcat #(expand-include ctx % params) excludes))))
        after-exclude (if excluded
                        (remove (fn [[k _]] (contains? excluded k)) included)
                        included)
        concepts (map second after-exclude)
        after-inactive (if (or (false? inactive-allowed) (:activeOnly params))
                         (remove :inactive concepts)
                         concepts)
        after-filter (if-let [f (:filter params)]
                       (let [f-lower (str/lower-case f)]
                         (filter (fn [c]
                                   (or (and (:display c)
                                            (str/includes? (str/lower-case (:display c)) f-lower))
                                       (and (:code c)
                                            (str/includes? (str/lower-case (:code c)) f-lower))))
                                 after-inactive))
                       after-inactive)
        offset' (or (:offset params) 0)
        paged (cond->> after-filter
                (pos? offset') (drop offset')
                (:count params) (take (:count params)))]
    (vec paged)))
