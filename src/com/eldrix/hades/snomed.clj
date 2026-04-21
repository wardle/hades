(ns com.eldrix.hades.snomed
  "Implementation of Hades protocols for SNOMED CT.
  A thin wrapper around the Hermes SNOMED terminology service"
  (:require [clojure.string :as str]
            [com.eldrix.hades.protocols :as protos]
            [com.eldrix.hermes.core :as hermes]
            [com.eldrix.hermes.snomed :as snomed]
            [lambdaisland.uri :as uri])
  (:import (com.eldrix.hermes.snomed Description)
           (java.time.format DateTimeFormatter)))

(def snomed-system-uri "http://snomed.info/sct")

(defn- version-uri [svc]
  (let [ri (first (hermes/release-information svc))]
    (str snomed-system-uri "/" (:moduleId ri) "/version/"
         (.format DateTimeFormatter/BASIC_ISO_DATE (:effectiveTime ri)))))

(def default-use-terms
  {snomed/Synonym            {:term "Synonym"}
   snomed/FullySpecifiedName {:term "Fully specified name"}})

(defn description->params
  "Turn a SNOMED description into a parameter map.
  Parameters:
   - d             : SNOMED CT description
   - use-terms-map : A map of descriptions by typeId

   It would be usual to specify the preferred text for a particular locale,
   but this will fallback to english if not provided."
  ([^Description d]
   (description->params d default-use-terms))
  ([^Description d use-terms-map]
   {"language" (:languageCode d)
    "use"      {:system  snomed-system-uri
                :code    (str (:typeId d))
                :display (:term (get use-terms-map (:typeId d)))}
    "display"  (:term d)}))


(defn ^:private parse-snomed-uri
  "Parses a SNOMED uri into a map of properties."
  [uri]
  (let [parsed (uri/parse uri)
        query-map (uri/query-string->map (:query parsed))
        [_ _ edition-refset-id _ _ version] (re-matches #"/sct(/(\d*))?(/(version)/(\d{8}))?" (:path parsed))]
    (assoc (into {} parsed)
      :query query-map
      :edition edition-refset-id
      :version version)))

(defn parse-implicit-value-set
  "Parse a FHIR value set from a single URL into an expression constraint.
  If parsing was successful, returns a map containing:
     |- :query  : one of [:all :isa :refsets :in-refset :ecl]
     |- :ecl    : an ECL constraint
  Returns nil if the URL could not be parsed.
  FHIR provides implicit value sets to reference a value set based on reference
  set or by subsumption. See https://www.hl7.org/fhir/snomedct.html#implicit
  The URI may contain specific *edition* information.
  - http://snomed.info/sct   - latest edition
  - http://snomed.info/sct/900000000000207008 - International edition
  - http://snomed.info/sct/900000000000207008/version/20130731 - International edition, 2013-07-31
  but these examples use the latest:
  - http://snomed.info/sct?fhir_vs  - all concepts
  - http://snomed.info/sct?fhir_vs=isa/[sctid] - all concepts subsumed by specified concept
  - http://snomed.info/sct?fhir_vs=refset - all installed reference sets
  - http://snomed.info/sct?fhir_vs=refset/[sctid] - all concepts in the reference set
  - http://snomed.info/sct?fhir_vs=ecl/[ecl] - all concepts matching the ECL."
  [^String uri]
  (let [{:keys [edition version query]} (parse-snomed-uri uri)
        fhir-vs (:fhir_vs query)]
    (cond
      (or edition version)
      {:error   :not-implemented
       :message "Implicit value sets with edition/version not supported."}
      (nil? fhir-vs)
      nil
      (= fhir-vs "")
      {:query :all, :ecl "*"}
      (str/starts-with? fhir-vs "isa/")
      {:query :isa, :ecl (str "<" (subs fhir-vs 4))}
      (= "refset" fhir-vs)
      {:query :refsets, :ecl (str "<" snomed/ReferenceSetConcept)}
      (str/starts-with? fhir-vs "refset/")
      {:query :in-refset, :ecl (str "^" (subs fhir-vs 7))}
      (str/starts-with? fhir-vs "ecl/")
      {:query :ecl, :ecl (subs fhir-vs 4)}
      :else
      {:error   :not-implemented
       :message (str "Implicit valueset for '" uri "' not implemented")})))

(defn- expression?
  "Returns true if code contains SNOMED expression syntax."
  [code]
  (and code (or (str/includes? code ":") (str/includes? code "{"))))

(defn- numeric-property-id?
  "Returns true if the property string looks like a SNOMED concept id —
  a non-empty run of digits. Used to distinguish attribute-id refinement
  filters from named filters like \"concept\"/\"parent\"/\"child\"."
  [property]
  (boolean (and (string? property) (re-matches #"\d+" property))))

(defn- compose-filter->ecl-part
  "Translate a single FHIR compose filter into an ECL fragment.

  Returns one of:
  - `{:hierarchy \"<< 73211009\"}` — a top-level concept-set constraint
  - `{:refinement \"116676008 = 72704001\"}` — an attribute refinement
  - `{:unsupported <msg>}` — filter shape is not representable in ECL

  FHIR compose filter properties for SNOMED CT:
  - `concept`  : hierarchical ops (`is-a`, `descendent-of`, `is-not-a`,
                 `generalizes`, `in`, `=`)
  - `parent`   : direct-parent exact match (`=`)
  - `child`    : direct-child exact match (`=`)
  - `expression`: raw ECL string (`=` or `in`)
  - `<sctid>`  : attribute refinement (`=` only)"
  [{:keys [property op value]}]
  (if (or (nil? value) (and (string? value) (str/blank? value)))
    ;; vs-invalid is caught upstream in compose/broken-filter-issue; still
    ;; guard here so a malformed filter can never poison the ECL string.
    {:unsupported (str "filter property=" (or property "?")
                       " op=" (or op "?") " missing value")}
    (cond
      (= property "concept")
      (case op
        "=" {:hierarchy value}
        "is-a" {:hierarchy (str "<< " value)}
        ("descendent-of" "descendant-of") {:hierarchy (str "< " value)}
        "is-not-a" {:hierarchy (str "* MINUS << " value)}
        "generalizes" {:hierarchy (str ">> " value)}
        "in" {:hierarchy (str "^ " value)}
        {:unsupported (str "filter property=concept op=" op " not supported")})

      (= property "parent")
      (if (= op "=")
        {:hierarchy (str ">! " value)}
        {:unsupported (str "filter property=parent op=" op " not supported")})

      (= property "child")
      (if (= op "=")
        {:hierarchy (str "<! " value)}
        {:unsupported (str "filter property=child op=" op " not supported")})

      (= property "expression")
      (if (contains? #{"=" "in"} op)
        {:hierarchy value}
        {:unsupported (str "filter property=expression op=" op " not supported")})

      (numeric-property-id? property)
      (if (= op "=")
        {:refinement (str property " = " value)}
        {:unsupported (str "filter property=" property " op=" op " not supported")})

      :else
      {:unsupported (str "filter property=" (or property "?")
                         " op=" (or op "?") " not supported")})))

(defn compose-filters->ecl
  "Combine a seq of compose filters into a single ECL expression.

  Hierarchy parts are AND'd; refinement parts become a comma-separated
  refinement clause attached to the hierarchy. Returns `{:ecl <string>}`
  on success, or `{:issues [{:severity \"error\" ...}]}` when any filter
  is unsupported — callers should return zero results plus the issue."
  [filters]
  (if-not (seq filters)
    {:ecl "*"}
    (let [parts (mapv compose-filter->ecl-part filters)
          unsupported (keep :unsupported parts)
          hierarchies (keep :hierarchy parts)
          refinements (keep :refinement parts)]
      (if (seq unsupported)
        {:issues (mapv (fn [msg]
                         {:severity "error" :type "not-supported"
                          :details-code "filter-not-supported" :text msg})
                       unsupported)}
        (let [base (if (seq hierarchies)
                     (str/join " AND " hierarchies)
                     "*")]
          {:ecl (if (seq refinements)
                  (str "(" base ") : " (str/join ", " refinements))
                  base)})))))

;; FHIR R4 ConceptMap equivalence codes for the SNOMED historical
;; association reference sets. See
;; https://confluence.ihtsdotools.org/display/DOCRELFMT/5.2.5.1
(def ^:private historical-association-maps
  "SNOMED→SNOMED forward-only ConceptMaps based on the historical
  association reference sets. Entries are filtered by what Hermes
  actually has installed at registration time."
  [{:refset-id snomed/SameAsReferenceSet
    :title       "SNOMED CT SAME AS association"
    :equivalence "equal"}
   {:refset-id snomed/ReplacedByReferenceSet
    :title       "SNOMED CT REPLACED BY association"
    :equivalence "equivalent"}
   {:refset-id snomed/PossiblyEquivalentToReferenceSet
    :title       "SNOMED CT POSSIBLY EQUIVALENT TO association"
    :equivalence "inexact"}
   {:refset-id snomed/PartiallyEquivalentToReferenceSet
    :title       "SNOMED CT PARTIALLY EQUIVALENT TO association"
    :equivalence "inexact"}
   {:refset-id snomed/WasAReferenceSet
    :title       "SNOMED CT WAS A association"
    :equivalence "specializes"}
   {:refset-id snomed/MovedToReferenceSet
    :title       "SNOMED CT MOVED TO association"
    :equivalence "equal"}
   {:refset-id snomed/SimilarToReferenceSet
    :title       "SNOMED CT SIMILAR TO association"
    :equivalence "inexact"}
   {:refset-id snomed/AlternativeReferenceSet
    :title       "SNOMED CT ALTERNATIVE association"
    :equivalence "inexact"}
   {:refset-id snomed/RefersToReferenceSet
    :title       "SNOMED CT REFERS TO association"
    :equivalence "relatedto"}])

(def ^:private historical-refset->equivalence
  (into {} (map (juxt :refset-id :equivalence) historical-association-maps)))

(def ^:private external-map-refsets
  "SNOMED map reference sets that target an external CodeSystem. The
  implicit SNOMED→external url is the canonical; the reverse
  (external→SNOMED) is advertised at the same url with `reverse=true`
  so both directions resolve through the same provider."
  [{:refset-id   446608001  ;; SNOMED CT to ICD-O simple map
    :target      "http://hl7.org/fhir/sid/icd-o"
    :title       "SNOMED CT to ICD-O simple map"
    :equivalence "equivalent"}
   {:refset-id   447562003  ;; SNOMED CT to ICD-10 extended map
    :target      "http://hl7.org/fhir/sid/icd-10"
    :title       "SNOMED CT to ICD-10 extended map"
    :equivalence "relatedto"}
   {:refset-id   6011000124106 ;; US edition SNOMED→ICD-10-CM
    :target      "http://hl7.org/fhir/sid/icd-10-cm"
    :title       "SNOMED CT (US) to ICD-10-CM extended map"
    :equivalence "relatedto"}])

(def ^:private external-refset->entry
  (into {} (map (juxt :refset-id identity) external-map-refsets)))

(defn- implicit-cm-url
  ([refset-id] (str snomed-system-uri "?fhir_cm=" refset-id))
  ([refset-id reverse?]
   (cond-> (implicit-cm-url refset-id)
     reverse? (str "&reverse=true"))))

(defn- installed-refset-ids [svc]
  (set (hermes/installed-reference-sets svc)))

(defn- parse-cm-url
  "Extract `{:refset-id :reverse?}` from an implicit SNOMED ConceptMap url.
  Returns nil when the url is not of the expected shape."
  [url]
  (when url
    (let [q   (:query (parse-snomed-uri url))
          rid (parse-long (str (:fhir_cm q)))
          rev (:reverse q)]
      (when rid
        {:refset-id rid
         :reverse?  (or (= "true" rev) (= "1" rev))}))))

(defn- resolve-cm
  "Given request params, work out which of our known ConceptMaps applies.
  Returns `{:refset-id :direction :kind :target}` where :kind is
  `:historical` or `:external` and :direction is `:forward` or `:reverse`.
  Prefers the explicit url; falls back to the (system, target) pair."
  [{:keys [url system target]}]
  (if-let [{:keys [refset-id reverse?]} (parse-cm-url url)]
    (cond
      (historical-refset->equivalence refset-id)
      {:refset-id refset-id :direction :forward :kind :historical}

      (external-refset->entry refset-id)
      (let [{ext-target :target} (external-refset->entry refset-id)]
        {:refset-id refset-id
         :direction (if reverse? :reverse :forward)
         :kind      :external
         :target    ext-target}))
    ;; No usable url — try the (system, target) pair
    (some (fn [{:keys [refset-id] ext-target :target}]
            (cond
              (and (= system snomed-system-uri) (= target ext-target))
              {:refset-id refset-id :direction :forward :kind :external
               :target ext-target}
              (and (= target snomed-system-uri) (= system ext-target))
              {:refset-id refset-id :direction :reverse :kind :external
               :target ext-target}))
          external-map-refsets)))

(defn- preferred-terms
  "Resolve preferred synonyms for concept ids in one pass. Returns a map
  of concept-id → preferred term (or nil for concepts whose preferred
  synonym can't be resolved in the given locale). Returns nil when
  `lang-refset-ids` is empty. Deduplicates via the accumulating map, so
  repeats in `concept-ids` cost only one LMDB call."
  [svc lang-refset-ids concept-ids]
  (when (seq lang-refset-ids)
    (persistent!
      (reduce (fn [m cid]
                (if (contains? m cid)
                  m
                  (assoc! m cid (:term (hermes/preferred-synonym* svc cid lang-refset-ids)))))
              (transient {})
              concept-ids))))

(defn- translate-historical
  "SNOMED→SNOMED historical-association forward translation."
  [svc refset-id code-id]
  (let [items      (get (hermes/historical-associations svc code-id) refset-id)
        eq         (historical-refset->equivalence refset-id)
        target-ids (into [] (comp (filter :active) (map :targetComponentId)) items)
        displays   (preferred-terms svc (hermes/match-locale svc) target-ids)
        matches    (mapv (fn [tid] {:equivalence eq
                                    :system      snomed-system-uri
                                    :code        (str tid)
                                    :display     (get displays tid)})
                         target-ids)]
    {:result (boolean (seq matches)) :matches matches}))

(defn- translate-external-forward
  "SNOMED→external translation. `code-id` is a SNOMED concept id; we
  return the external codes it maps to via the map refset."
  [svc refset-id code-id target-system equivalence]
  (let [items  (hermes/component-refset-items svc code-id refset-id)
        matches (vec (for [item items
                           :let [t (:mapTarget item)]
                           :when (and t (not= "" t))]
                       {:equivalence equivalence
                        :system      target-system
                        :code        t}))]
    {:result (boolean (seq matches)) :matches matches}))

(defn- translate-external-reverse
  "external→SNOMED translation for a given map refset. `code` is the
  external (target) code string; we emit matching SNOMED concepts."
  [svc refset-id code equivalence]
  (let [snomed-ids (hermes/member-field svc refset-id "mapTarget" code)
        displays   (preferred-terms svc (hermes/match-locale svc) snomed-ids)
        matches    (mapv (fn [sid] {:equivalence equivalence
                                    :system      snomed-system-uri
                                    :code        (str sid)
                                    :display     (get displays sid)})
                         snomed-ids)]
    {:result (boolean (seq matches)) :matches matches}))

(deftype HermesService
  [svc]
  protos/CodeSystem
  (cs-resource [_ _params])
  (cs-lookup
    [_ {:keys [code version displayLanguage]}]
    (if (expression? code)
      (let [lang-ids (hermes/match-locale svc displayLanguage true)
            rendered (try (hermes/render-expression* svc code
                            {:terms :update :definition-status :auto
                             :language-refset-ids lang-ids})
                          (catch Exception _ nil))]
        (when rendered
          {:name    "SNOMED CT"
           :version (or version (version-uri svc))
           :display rendered
           :system  snomed-system-uri
           :code    (keyword code)}))
      (when-let [code' (parse-long code)]
        (when-let [ec (hermes/extended-concept svc code')]
          (let [lang-refset-ids (hermes/match-locale svc displayLanguage true)
                ver             (or version (version-uri svc))
                parents         (get-in ec [:directParentRelationships snomed/IsA])
                children        (hermes/child-relationships-of-type svc code' snomed/IsA)
                attrs           (dissoc (:directParentRelationships ec) snomed/IsA)
                concrete        (hermes/concrete-values svc code')
                display-ids     (-> #{code' snomed/Synonym snomed/FullySpecifiedName}
                                    (into parents)
                                    (into children)
                                    (into (keys attrs))
                                    (into (mapcat val) attrs)
                                    (into (map :typeId) concrete))
                displays        (preferred-terms svc lang-refset-ids display-ids)]
            {:name         "SNOMED CT"
             :version      ver
             :display      (get displays code')
             :system       snomed-system-uri
             :code         (keyword code)
             :properties   (concat
                             [{:code :inactive :value (not (get-in ec [:concept :active]))}
                              {:code :sufficientlyDefined :value (= snomed/Defined (get-in ec [:concept :definitionStatusId]))}]
                             (map (fn [pid] {:code        :parent
                                             :value       (keyword (str pid))
                                             :description (get displays pid)})
                                  parents)
                             (map (fn [cid] {:code        :child
                                             :value       (keyword (str cid))
                                             :description (get displays cid)})
                                  children)
                             (mapcat (fn [[type-id target-ids]]
                                       (map (fn [tid] {:code         (keyword (str type-id))
                                                       :code-display (get displays type-id)
                                                       :value        (keyword (str tid))
                                                       :description  (get displays tid)})
                                            target-ids))
                                     attrs)
                             (map (fn [{:keys [typeId value]}]
                                    {:code         (keyword (str typeId))
                                     :code-display (get displays typeId)
                                     :value        value})
                                  concrete))
             :designations (map (fn [d] {:language (keyword (:languageCode d))
                                         :use      {:system  snomed-system-uri
                                                    :code    (str (:typeId d))
                                                    :display (get displays (:typeId d))}
                                         :value    (:term d)})
                                (:descriptions ec))})))))

  (cs-validate-code [_ {:keys [code display version displayLanguage]}]
    (let [ver (or version (version-uri svc))]
      (if (expression? code)
        ;; Post-coordinated expression: full validation including MRCM
        ;; Structural errors (concept-not-found, attribute-invalid) → result: false
        ;; MRCM constraint issues (attribute-not-in-domain, value-out-of-range) → result: true, informational
        (let [all-errors (hermes/validate-expression svc code)
              structural-types #{:concept-not-found :concept-inactive :attribute-invalid :syntax-error}
              structural (filter #(structural-types (:error %)) all-errors)
              mrcm-issues (remove #(structural-types (:error %)) all-errors)
              lang-ids (hermes/match-locale svc displayLanguage true)
              rendered (try (hermes/render-expression* svc code
                              {:terms :update :definition-status :auto
                               :language-refset-ids lang-ids})
                            (catch Exception _ nil))]
          (if (seq structural)
            (let [msg (str "Unknown code '" code "' in the CodeSystem '" snomed-system-uri "' version '" ver "'")]
              {:result  false
               :code    (keyword code)
               :system  snomed-system-uri
               :version ver
               :message msg
               :issues  (into [{:severity "error" :type "code-invalid" :details-code "invalid-code"
                                :text msg :expression ["code"]}]
                              (map (fn [e]
                                     (let [detail (case (:error e)
                                                    :attribute-invalid
                                                    (str "Concept " (:concept-id e)
                                                         " is not valid in this context (must be a descendent of one of 410662002,106237007)")
                                                    (:message e))]
                                       {:severity "information" :type "code-invalid"
                                        :details-code "invalid-code"
                                        :text (str "Not a valid expression: " detail)
                                        :expression ["code"]})))
                              structural)})
            {:result  true
             :code    (keyword code)
             :system  snomed-system-uri
             :version ver
             :display (or rendered code)
             :issues  [{:severity "information" :type "informational" :details-code "process-note"
                        :text (if (seq mrcm-issues)
                                "The expression is grammatically correct and the concepts are valid, but the expression has not been checked against the SNOMED CT concept model (MRCM)"
                                "The expression is grammatically correct and the concepts are valid")
                        :expression ["code"]}]}))
        ;; Simple concept code validation
        (when-let [code' (parse-long code)]
          (if-let [concept (hermes/concept svc code')]
            (let [lang-refset-ids (hermes/match-locale svc displayLanguage true)
                  preferred (:term (hermes/preferred-synonym* svc code' lang-refset-ids))
                  inactive? (not (:active concept))
                  result (cond-> {:result true :display preferred :code (keyword code)
                                  :system snomed-system-uri :version ver}
                           inactive? (assoc :inactive true))]
              (if (and display (not= display preferred))
                (let [descriptions (:descriptions (hermes/extended-concept svc code'))
                      matching-desc (first (filter #(= display (:term %)) descriptions))
                      active-displays (->> descriptions
                                           (filter :active)
                                           (map :term)
                                           distinct)
                      lang (or displayLanguage "--")]
                  (if (and matching-desc (not (:active matching-desc)))
                    (let [quoted (str/join "," (map #(str "\"" % "\"") active-displays))
                          msg (str "'" display "' is no longer considered a correct display for code '"
                                   code "' (status = inactive). The correct display is one of " quoted ".")]
                      (assoc result :message msg
                                    :issues [{:severity     "warning"
                                              :type         "invalid"
                                              :details-code "display-comment"
                                              :text         msg
                                              :expression   ["display"]}]))
                    (let [msg (str "Wrong Display Name '" display "' for " snomed-system-uri "#" code
                                   " - should be '" preferred "'"
                                   " (for the language(s) '" lang "')")]
                      (assoc result :result false
                                    :message msg
                                    :issues [{:severity     "error"
                                              :type         "invalid"
                                              :details-code "invalid-display"
                                              :text         msg
                                              :expression   ["display"]}]))))
                result))
            (let [msg (str "Unknown code '" code "' in the CodeSystem '" snomed-system-uri "' version '" ver "'")]
              {:result  false
               :code    (keyword code)
               :system  snomed-system-uri
               :version ver
               :message msg
               :issues  [{:severity     "error"
                          :type         "code-invalid"
                          :details-code "invalid-code"
                          :text         msg
                          :expression   ["code"]}]}))))))

  (cs-subsumes
    [_ {:keys [systemA codeA systemB codeB]}]
    (let [codeA' (Long/parseLong codeA)
          codeB' (Long/parseLong codeB)]
      {:outcome
       (cond
         ;; simple if they are the same
         (and (= systemA systemB) (= codeA' codeB')) "equivalent"
         (hermes/subsumed-by? svc codeA' codeB') "subsumed-by" ;; A is subsumed by B
         (hermes/subsumed-by? svc codeB' codeA') "subsumes" ;; A subsumes B
         ;; look up historical associations, and check for equivalence  - this catches SAME-AS and REPLACED-BY etc.
         (and (= systemA systemB) ((hermes/with-historical svc [codeA']) codeB')) "equivalent"
         :else "not-subsumed")}))
  (cs-find-matches [_ query]
    (let [{:keys [version filters max-hits text active-only displayLanguage]} query
          ver (or version (version-uri svc))
          {:keys [ecl issues]} (compose-filters->ecl filters)]
      (if (seq issues)
        {:concepts [] :issues issues}
        (let [search-params (cond-> {:constraint ecl}
                              max-hits                       (assoc :max-hits max-hits)
                              (not (str/blank? text))        (assoc :s text)
                              (not active-only)              (assoc :inactive-concepts? true)
                              (not (str/blank? displayLanguage)) (assoc :accept-language displayLanguage))
              concepts (map (fn [{:keys [conceptId preferredTerm]}]
                              {:code    (str conceptId)
                               :system  snomed-system-uri
                               :version ver
                               :display preferredTerm})
                            (hermes/search svc search-params))]
          {:concepts concepts}))))
  protos/ValueSet
  (vs-resource [_ _params])
  (vs-expand [_ _ctx {:keys [url filter activeOnly] cnt :count ofs :offset}]
    (when-let [{:keys [ecl error message]} (parse-implicit-value-set url)]
      (if error
        {:concepts [] :total 0
         :issues   [{:severity     "error" :type "not-supported"
                     :details-code "not-implemented" :text message}]}
        (let [ver      (version-uri svc)
              ofs'     (or ofs 0)
              max-hits (when cnt (+ cnt ofs'))
              search-params (cond-> {:constraint         ecl
                                     :inactive-concepts? (if (false? activeOnly) true false)}
                              filter   (assoc :s filter)
                              max-hits (assoc :max-hits max-hits))
              results  (cond->> (hermes/search svc search-params)
                         (pos? ofs') (drop ofs'))
              concepts (mapv (fn [{:keys [conceptId term preferredTerm]}]
                               {:code         (str conceptId)
                                :system       snomed-system-uri
                                :version      ver
                                :display      preferredTerm
                                :designations [{:value term}]})
                             results)]
          (cond-> {:concepts         concepts
                   :used-codesystems [{:uri (str snomed-system-uri "|" ver) :status "active"}]
                   :compose-pins     []}
            (nil? cnt) (assoc :total (count concepts)))))))
  (vs-validate-code [_ _ctx {:keys [url code system display displayLanguage]}]
    (when (= system snomed-system-uri)
      (let [code' (parse-long code)
            ver (version-uri svc)
            {:keys [ecl query error message]} (parse-implicit-value-set url)]
        (when (or code' (expression? code))
          (cond
            error
            {:result  false :code (keyword code) :system system
             :message message
             :issues  [{:severity     "error" :type "not-supported"
                        :details-code "not-implemented" :text message}]}

            ecl
            (let [;; Validate that the implicit VS root concept/refset exists
                  vs-valid? (case query
                              :isa (let [root (parse-long (subs ecl 1))]
                                     (and root (hermes/concept svc root)))
                              :in-refset (let [refset-id (parse-long (subs ecl 1))]
                                           (and refset-id
                                                (hermes/concept svc refset-id)
                                                (hermes/subsumed-by? svc refset-id snomed/ReferenceSetConcept)))
                              true)]
              (if-not vs-valid?
                (let [msg (str "A definition for the value Set '" url "' could not be found")]
                  {:result    false
                   :not-found true
                   :code      (keyword code)
                   :system    system
                   :message   msg
                   :issues    [{:severity "error" :type "not-found" :details-code "not-found"
                                :text msg}]})
                (let [lang-refset-ids (hermes/match-locale svc displayLanguage true)
                      focus-id (if code'
                                 code'
                                 (some-> (try (hermes/parse-expression svc code) (catch Exception _ nil))
                                         (get-in [:subExpression :focusConcepts])
                                         first :conceptId))
                      preferred (when focus-id (:term (hermes/preferred-synonym* svc focus-id lang-refset-ids)))
                      expr-display (when (expression? code)
                                     (try (hermes/render-expression* svc code
                                            {:terms :update :definition-status :auto
                                             :language-refset-ids lang-refset-ids})
                                          (catch Exception _ nil)))
                      display-val (or expr-display preferred)
                      member? (when focus-id (seq (hermes/intersect-ecl svc [focus-id] ecl)))]
                  (if member?
                    (let [result {:result true :display display-val :code (keyword code)
                                  :system system :version ver}]
                      (if (and display (not= display display-val))
                        (let [lang (or displayLanguage "--")
                              msg (str "Wrong Display Name '" display "' for " system "#" code
                                       " - should be '" display-val "'"
                                       " (for the language(s) '" lang "')")]
                          (assoc result :message msg
                                        :issues [{:severity     "warning"
                                                  :type         "invalid"
                                                  :details-code "invalid-display"
                                                  :text         msg
                                                  :expression   ["display"]}]))
                        result))
                    (let [code-ref (cond-> (str system "#" code)
                                     display (str " ('" display "')"))
                          msg (str "The provided code '" code-ref "' was not found in the value set '" url "'")]
                      {:result  false
                       :code    (keyword code)
                       :system  system
                       :version ver
                       :display display-val
                       :message msg
                       :issues  [{:severity     "error"
                                  :type         "code-invalid"
                                  :details-code "not-in-vs"
                                  :text         msg
                                  :expression   ["code"]}]})))))

            :else
            (let [msg (str "Cannot parse value set URL: " url)]
              {:result  false
               :message msg
               :issues  [{:severity     "error"
                          :type         "not-supported"
                          :details-code "not-found"
                          :text         msg
                          :expression   ["url"]}]}))))))
  protos/ConceptMap
  (cm-describe
    [_]
    (let [installed (installed-refset-ids svc)]
      (concat
        (for [{:keys [refset-id title]} historical-association-maps
              :when (installed refset-id)]
          {:url    (implicit-cm-url refset-id)
           :system snomed-system-uri
           :target snomed-system-uri
           :title  title})
        (mapcat
          (fn [{:keys [refset-id target title]}]
            (when (installed refset-id)
              [{:url    (implicit-cm-url refset-id)
                :system snomed-system-uri
                :target target
                :title  (str title " (SNOMED CT → target)")}
               {:url    (implicit-cm-url refset-id true)
                :system target
                :target snomed-system-uri
                :title  (str title " (target → SNOMED CT)")}]))
          external-map-refsets))))
  (cm-resource [_ _params])
  (cm-translate
    [_ {:keys [code] :as params}]
    (let [resolved (resolve-cm params)
          code-id  (when code (parse-long code))
          unmapped (fn [msg] {:result false :message msg})]
      (cond
        (nil? resolved)
        (unmapped "No mapping available for this source/target combination")

        (nil? code)
        (unmapped "No source code provided")

        (= :historical (:kind resolved))
        (if code-id
          (translate-historical svc (:refset-id resolved) code-id)
          (unmapped (str "SNOMED CT historical associations require a numeric code, got '" code "'")))

        (and (= :external (:kind resolved)) (= :forward (:direction resolved)))
        (if code-id
          (translate-external-forward svc (:refset-id resolved) code-id
                                      (:target resolved)
                                      (:equivalence (external-refset->entry (:refset-id resolved))))
          (unmapped (str "SNOMED CT forward map requires a numeric source code, got '" code "'")))

        (and (= :external (:kind resolved)) (= :reverse (:direction resolved)))
        (translate-external-reverse svc (:refset-id resolved) code
                                    (:equivalence (external-refset->entry (:refset-id resolved))))

        :else
        (unmapped (str "Cannot translate — unsupported ConceptMap " (or (:url params) "")))))))