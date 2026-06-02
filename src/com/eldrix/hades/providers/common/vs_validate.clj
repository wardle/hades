(ns com.eldrix.hades.providers.common.vs-validate
  "Shared `vs-validate-code` for any ValueSet provider backed by a parsed
  compose definition.

  `validate-code` is the single public entry. It expands the ValueSet
  through the compose engine, looks for the caller's code+system in the
  expansion, applies version matching, display validation, inactive
  status, fragment-CodeSystem and status-warning logic, and returns a
  `::result/validate`.

  It depends only on `svc` (the composite, for cross-CodeSystem protocol
  callbacks) and the provider's `{:url :version :compose}` map, so every
  backend (`MemoryValueSet`, `FtrmProvider`, `LoincProvider`, …) gets
  identical behaviour by delegating here."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [com.eldrix.hades.providers.common.canonical :as canonical]
            [com.eldrix.hades.providers.common.compose :as compose]
            [com.eldrix.hades.composite :as composite]
            [com.eldrix.hades.providers.common.display :as display]
            [com.eldrix.hades.providers.common.issues :as issues]
            [com.eldrix.hades.protocols :as protos]
            [com.eldrix.hades.protocols.result :as result]))

(set! *warn-on-reflection* true)

;; Input contracts: the ValueSet (`vs-data`) and the operation `params`.

;; vs-data — the ValueSet identity each backend supplies.
(s/def ::url string?)
(s/def ::version (s/nilable string?))
(s/def ::compose (s/nilable map?))

;; params — the validate-code operation inputs. An open map; only the keys
;; the engine reads are listed. `::version` here is the caller's *coding*
;; version (reused spec, same type as the ValueSet's version in vs-data).
(s/def ::code (s/nilable string?))
(s/def ::system (s/nilable string?))
(s/def ::display (s/nilable string?))
(s/def ::displayLanguage (s/nilable string?))
(s/def ::lenient-display-validation (s/nilable boolean?))
(s/def ::expanding (s/nilable set?))
(s/def ::force-system-version (s/nilable (s/map-of string? string?)))
(s/def ::system-version (s/nilable (s/map-of string? string?)))
(s/def ::check-system-version (s/nilable (s/map-of string? string?)))

(s/def ::params
  (s/keys :opt-un [::code ::system ::display ::version ::displayLanguage
                   ::lenient-display-validation ::expanding
                   ::force-system-version ::system-version ::check-system-version]))

;; ---------------------------------------------------------------------------
;; Compose-narrowing helpers — reduce the compose to a single-code lookup
;; before expanding, so validation never materialises whole memberships.
;; ---------------------------------------------------------------------------

(defn- compose-version-for-system
  "Return the version declared for `system` in the compose `include[]`,
  preferring `preferred` when it appears among the declared versions."
  ([compose-def system] (compose-version-for-system compose-def system nil))
  ([compose-def system preferred]
   (let [matches (keep (fn [include]
                         (when (= system (get include "system"))
                           (get include "version")))
                       (get compose-def "include"))]
     (or (when preferred (some #{preferred} matches))
         (first matches)))))

(defn- code-filter
  "Build a compose filter selecting a single `code` (`code = <code>`)."
  [code]
  {"property" "code" "op" "=" "value" code})

(defn- match-driven-system-include?
  "True when `include` selects `system` by membership alone — same
  `system` and no enumerated `concept[]`."
  [include system]
  (and (= system (get include "system"))
       (not (seq (get include "concept")))))

(defn- add-code-filter
  "Push an exact-`code` filter onto `include`'s `filter[]`."
  [include code]
  (update include "filter" (fnil conj []) (code-filter code)))

(defn- narrow-concept-driven-include
  "Filter a concept-driven include's `concept[]` to entries whose code
  matches `code` case-insensitively. Keeping case variants preserves the
  case-insensitive match `find-best-match` performs downstream."
  [include code]
  (let [target (str/lower-case code)]
    (update include "concept"
            (fn [concepts]
              (filterv #(some-> (get % "code") str/lower-case (= target)) concepts)))))

(defn- narrow-includes-to-code
  "For ValueSet validation we only need to know whether one coding is in
  the compose, so narrow every include/exclude to just the caller's code
  before expanding — turning a whole-membership enrichment into a single
  candidate lookup. Match-driven includes (e.g. `{system: http://loinc.org}`)
  get the exact code predicate pushed into their filters; concept-driven
  includes have their `concept[]` filtered to the matching code. Includes
  that are neither (e.g. valueSet-only) are left intact."
  [compose-def system code]
  (if (and code (seq compose-def))
    (let [narrow (fn [include]
                   (cond
                     (match-driven-system-include? include system) (add-code-filter include code)
                     (seq (get include "concept"))                 (narrow-concept-driven-include include code)
                     :else                                         include))]
      (cond-> compose-def
        (contains? compose-def "include") (update "include" #(mapv narrow %))
        (contains? compose-def "exclude") (update "exclude" #(mapv narrow %))))
    compose-def))

;; ---------------------------------------------------------------------------
;; Issue-attaching helpers — each folds one cross-cutting issue onto an
;; in-progress `result`, reading the request facts it needs from `params`.
;; ---------------------------------------------------------------------------

(defn- add-version-mismatch
  "When the caller's version differs from the matched/included version, add
  a `vs-invalid` `Coding.version` issue (warning for a versionless include,
  error otherwise) and, for errors, extend `:message`."
  [result svc {:keys [system match-ver include-ver override-pattern force?]
               caller-version :version}]
  (if (and caller-version match-ver (not= caller-version match-ver))
    (let [caller-version-exists? (nil? (composite/unknown-version-issue svc system caller-version))
          default-mismatch? (and (not caller-version-exists?)
                                 (nil? include-ver) (nil? override-pattern))
          use-resulting-from? (and override-pattern
                                   (or force? (nil? include-ver) (= "" include-ver)))
          ver-desc (if use-resulting-from?
                     (str "version '" override-pattern "' resulting from the version '"
                          (or include-ver "") "'")
                     (str "version '" (or include-ver match-ver) "'"))
          msg (if default-mismatch?
                (str "The code system '" system "' " ver-desc
                     " for the versionless include in the ValueSet include is different to the one in the value ('" caller-version "')")
                (str "The code system '" system "' " ver-desc
                     " in the ValueSet include is different to the one in the value ('" caller-version "')"))
          severity (if default-mismatch? "warning" "error")
          issue {:severity     severity
                 :type         "invalid"
                 :details-code "vs-invalid"
                 :text         msg
                 :expression   ["Coding.version"]}
          cur-msg (:message result)]
      (cond-> (assoc result
                     :result false
                     :issues (conj (or (:issues result) []) issue))
        (= "error" severity) (assoc :message (if cur-msg (str cur-msg "; " msg) msg))))
    result))

(defn- add-check-system-version-issue
  "When the `:check-system-version` param names a version `system`'s resolved
  CodeSystem can't satisfy, add the resulting issue and extend `:message`."
  [result svc {:keys [system match-ver] :as params}]
  (if-let [issue (composite/check-system-version-issue svc system match-ver params)]
    (let [cur-msg (:message result)]
      (assoc result
             :result false
             :issues (conj (or (:issues result) []) issue)
             :message (if cur-msg (str cur-msg "; " (:text issue)) (:text issue))))
    result))

(defn- add-unknown-version-issue
  "When the caller's version names a version of `system` that isn't
  registered, prepend an unknown-version issue (priority 0), prepend its
  text to `:message`, and flag `:x-caused-by-unknown-system`."
  [result svc {:keys [system] caller-version :version}]
  (if-let [issue (composite/unknown-version-issue svc system caller-version)]
    (let [cur-msg (:message result)]
      (assoc result
             :result false
             :issues (into [(assoc issue :priority 0)] (or (:issues result) []))
             :message (if cur-msg (str (:text issue) "; " cur-msg) (:text issue))
             :x-caused-by-unknown-system (canonical/versioned-uri system caller-version)))
    result))

;; ---------------------------------------------------------------------------
;; Branch helpers — each builds one exit shape for `validate-code` from
;; svc / vs-data / params. `validate-code` does the one-time work (compose
;; expansion, match resolution), assoc'ing its results onto `params`, then
;; dispatches to exactly one of these.
;; ---------------------------------------------------------------------------

(defn- result-vs-invalid
  "Compose itself was invalid — surface the issue verbatim."
  [{:keys [vs-invalid-issue compose-issues code system]}]
  (cond-> {:result    false
           :not-found true
           :code      (when code (keyword code))
           :message   (:text vs-invalid-issue)
           :issues    (vec compose-issues)}
    system (assoc :system system)))

(defn- result-not-found
  "An included CodeSystem isn't registered. Build a `:result false` shape
  that surfaces the not-found cause and any version mismatch the caller
  introduced."
  [svc {:keys [compose]} {:keys [not-found-issue compose-issues code system]
                          caller-version :version}]
  (let [include-ver (compose-version-for-system compose system)
        include-ver-unknown? (and system include-ver
                                  (nil? (composite/find-codesystem svc
                                          (canonical/versioned-uri system include-ver))))
        cs-result (when system
                    (protos/cs-validate-code svc
                      (cond-> {:system system :code code}
                        (and caller-version
                             (nil? (composite/unknown-version-issue svc system caller-version)))
                        (assoc :version caller-version))))
        {cs-valid :result cs-disp :display cs-ver :version} cs-result
        vs-invalid-issue (when (and caller-version include-ver
                                    (not= caller-version include-ver))
                           {:severity     "error"
                            :type         "invalid"
                            :details-code "vs-invalid"
                            :text         (str "The code system '" system "' version '" include-ver
                                               "' in the ValueSet include is different to the one in the value ('"
                                               caller-version "')")
                            :expression   ["Coding.version"]})
        issues (cond-> (vec compose-issues)
                 vs-invalid-issue (conj vs-invalid-issue))
        message (cond-> (:text not-found-issue)
                  vs-invalid-issue (str "; " (:text vs-invalid-issue)))]
    (cond-> {:result  false
             :code    (when code (keyword code))
             :message message
             :issues  issues}
      system (assoc :system system)
      include-ver-unknown?
      (assoc :x-caused-by-unknown-system (canonical/versioned-uri system include-ver))
      (and cs-result cs-valid cs-disp)
      (assoc :display cs-disp)
      (and cs-result cs-ver)
      (assoc :version cs-ver))))

(defn- find-best-match
  "Walk the expanded concept list and pick the best match for the caller.
  Returns nil when no concept matches by code+system. Sets `:case-differs`
  on the returned match when the only match is case-insensitive and the
  matched CodeSystem declares caseSensitive=false."
  [svc {:keys [expanded code system display multi-version-systems]
        caller-version :version}]
  (let [case-insensitive? (fn [c]
                            (false? (:case-sensitive
                                      (composite/cs-meta svc (or (:system c) system)))))
        all-exact (filterv (fn [c]
                             (and (= code (:code c))
                                  (or (nil? system) (= system (:system c)))))
                           expanded)
        version-matching (when caller-version
                           (filterv #(= caller-version (:version %)) all-exact))
        overload? (and caller-version system
                       (contains? multi-version-systems system))
        exact-matches (cond
                        (and caller-version (seq version-matching)) version-matching
                        overload? []
                        :else all-exact)
        case-match (fn [c]
                     (and (not= code (:code c))
                          (= (str/lower-case code) (str/lower-case (:code c)))
                          (or (nil? system) (= system (:system c)))
                          (case-insensitive? c)
                          (assoc c :case-differs true)))
        parse-semver (fn [v]
                       (mapv #(try (Integer/parseInt %) (catch Exception _ 0))
                             (str/split (or v "") #"\.")))
        rank-key (fn [c]
                   [(if (and display (= display (:display c))) 1 0)
                    (parse-semver (:version c))])]
    (or (when (seq exact-matches)
          (if (> (count exact-matches) 1)
            (last (sort-by rank-key exact-matches))
            (first exact-matches)))
        (some case-match expanded))))

(defn- align-match-to-wildcard-version
  "When the include version is a wildcard pattern that matches the caller's
  version, but the matched concept came from a different concrete version,
  re-fetch the concept at the caller's version so display reflects it."
  [match svc {:keys [compose]} {:keys [system code] caller-version :version}]
  (if (nil? match)
    match
    (let [include-ver (compose-version-for-system compose system
                        (or (:version match) caller-version))
          include-is-wildcard? (and include-ver
                                    (some #(= "x" %) (str/split include-ver #"\.")))
          caller-matches-include? (and include-is-wildcard?
                                       caller-version
                                       (canonical/version-matches? include-ver caller-version))]
      (if (and caller-matches-include? (not= caller-version (:version match)))
        (let [r (protos/cs-lookup svc {:system system :code code :version caller-version})]
          (if (and r (not (:not-found r)))
            (assoc match :version caller-version :display (:display r))
            match))
        match))))

(defn- apply-cross-cutting
  "Apply the standard chain of cross-cutting concerns to a `result`. Order is
  significant: inactive warnings precede version mismatch precede
  unknown-version precede check-system-version, then the CS/VS status
  warnings sit on the outside."
  [result svc {:keys [url]} {:keys [system] :as params}]
  (-> result
      (issues/add-inactive-warning)
      (add-version-mismatch svc params)
      (add-unknown-version-issue svc params)
      (add-check-system-version-issue svc params)
      (composite/add-cs-status-warnings svc system)
      (composite/add-vs-status-warnings svc url)))

(defn- result-with-match
  "A concept was found in the expansion; return the validation success or
  the lenient/strict display-mismatch shape, with cross-cutting concerns
  applied. Cross-cutting keys on the matched concept's system, which may
  differ from the caller's (e.g. validation by code alone)."
  [svc vs-data
   {{system :system actual-code :code match-display :display match-version :version
     case-differs? :case-differs
     :keys [designations inactive inactive-status]} :match
    :keys [code display displayLanguage lenient-display-validation]
    :as params}]
  (let [display-langs (display/parse-display-language* displayLanguage)
        lang-display  (when (seq display-langs)
                        (display/find-display-for-language designations display-langs))
        best-display  (or lang-display match-display)
        result (cond-> {:result  true
                        :display best-display
                        :code    (keyword code)
                        :system  system}
                 match-version   (assoc :version match-version)
                 inactive        (assoc :inactive true)
                 inactive-status (assoc :inactive-status inactive-status)
                 case-differs?   (assoc :normalized-code (keyword actual-code)))
        case-issue (when case-differs?
                     {:severity     "information"
                      :type         "business-rule"
                      :details-code "code-rule"
                      :text         (issues/format-case-mismatch
                                     code actual-code system match-version)
                      :expression   ["Coding.code"]})
        cs-lang (when (and (not (str/blank? display)) system)
                  (let [cs-m (composite/cs-meta svc system)]
                    (when cs-m (or (:language cs-m) (get cs-m "language")))))
        disp    (display/validate-display
                 {:display         display
                  :primary-display match-display
                  :designations    designations
                  :display-langs   display-langs
                  :displayLanguage displayLanguage
                  :system          system
                  :code            code
                  :cs-language     cs-lang
                  :lenient?        lenient-display-validation})
        issues  (filter some? [case-issue (:issue disp)])
        result  (cond-> result
                  disp         (assoc :result (:result disp) :message (:text (:issue disp)))
                  (seq issues) (assoc :issues issues))]
    (apply-cross-cutting result svc vs-data (assoc params :system system))))

(defn- result-no-match
  "No concept was found in the expansion. Build the not-in-vs failure shape,
  layering cs-validate insight and (rarely) the fragment CodeSystem
  semantics on top."
  [svc {:keys [url version]} {:keys [code system display] caller-version :version :as params}]
  (let [code-ref (cond-> (str system "#" code)
                   display (str " ('" display "')"))
        vs-ref (if version (str url "|" version) url)
        not-in-vs-msg (str "The provided code '" code-ref "' was not found in the value set '" vs-ref "'")
        cs-result (when system (protos/cs-validate-code svc {:system system :code code}))
        {cs-valid :result cs-disp :display cs-ver :version cs-issues :issues
         cs-unknown :x-unknown-system} cs-result
        cs-m (when system (composite/cs-meta svc system))
        cs-fragment? (and cs-result cs-valid (= "fragment" (:content cs-m)))
        cs-invalid? (and cs-result (not cs-fragment?) (false? cs-valid))
        cs-issue (when cs-invalid?
                   (let [i (first cs-issues)]
                     ;; When the caller pinned a version, the version-specific
                     ;; unknown-version issue (added below) supersedes the bare
                     ;; "system not found" issue from cs-result. Drop the latter
                     ;; to avoid duplicate not-found entries.
                     (when-not (and caller-version (= "not-found" (:details-code i)))
                       i)))]
    (if cs-fragment?
      (-> cs-result
          (assoc :code (keyword code))
          (composite/add-cs-status-warnings svc system)
          (composite/add-vs-status-warnings svc url))
      (let [base-issues [{:severity     "error"
                          :type         "code-invalid"
                          :details-code "not-in-vs"
                          :message-id   "None_of_the_provided_codes_are_in_the_value_set_one"
                          :text         not-in-vs-msg
                          :expression   ["Coding.code"]}]
            all-issues  (if cs-issue (conj base-issues cs-issue) base-issues)
            combined-msg (if cs-issue (str not-in-vs-msg "; " (:text cs-issue)) not-in-vs-msg)
            cs-display  (when (and cs-result cs-valid) cs-disp)
            cs-version  (when cs-result cs-ver)
            result (cond-> {:result  false
                            :code    (keyword code)
                            :message combined-msg
                            :issues  all-issues}
                     system (assoc :system system)
                     cs-unknown (assoc :x-unknown-system cs-unknown)
                     cs-display (assoc :display cs-display)
                     cs-version (assoc :version cs-version))]
        ;; No inactive warning here (unlike `apply-cross-cutting`, which leads
        ;; with one): no match means there is no `:inactive` flag to surface.
        (-> result
            (add-version-mismatch svc params)
            (add-unknown-version-issue svc params)
            (add-check-system-version-issue svc params)
            (composite/add-cs-status-warnings svc system)
            (composite/add-vs-status-warnings svc url))))))

(s/fdef validate-code
  :args (s/cat :svc some?
               :vs-data (s/keys :req-un [::url] :opt-un [::version ::compose])
               :params ::params))

(defn validate-code
  "Compose-driven `vs-validate-code` shared by every backend: is the
  caller's coding a member of this ValueSet?

  Parameters:

    svc      The composite TerminologyService. Compose calls its protocol
             methods for cross-CodeSystem lookups (display, version
             resolution, status).

    vs-data  The ValueSet to validate against:
               :url      canonical URL (used in messages, and as the
                         cycle-detection key while expanding)
               :version  the ValueSet's version (optional)
               :compose  the parsed FHIR `compose` (string-keyed JSON)

    params   The operation inputs. An open map; the keys read here:
               :code     the code to validate
               :system   its CodeSystem URL
               :display  display to check against the concept (optional)
               :version  the caller's *coding* version — NOT the ValueSet's
               :displayLanguage             requested display language(s)
               :lenient-display-validation  warn rather than fail on a
                                            display mismatch
               :force-system-version /
               :system-version /
               :check-system-version  {system-url -> version} maps that
                         pin / default / assert a CodeSystem's version
                         during expansion

  `:expanding` is not a caller parameter: it's an internal cycle guard — the
  set of ValueSet URLs already being expanded in this request — threaded
  through nested `valueSet[]` imports to break circular references. It seeds
  to #{url} and grows as imports recurse.

  Returns a `::result/validate`. Internally threads svc / vs-data / params,
  assoc'ing each phase's findings onto params, and dispatches to one of
  `result-vs-invalid`, `result-not-found`, `result-with-match` or
  `result-no-match`.

  Example — is SNOMED 73211009 in a ValueSet that includes all of SNOMED CT?

      (validate-code svc
        {:url     \"http://example.org/fhir/ValueSet/all-snomed\"
         :compose {\"include\" [{\"system\" \"http://snomed.info/sct\"}]}}
        {:code \"73211009\" :system \"http://snomed.info/sct\"})
      ;; => {:result  true
      ;;     :code    :73211009
      ;;     :system  \"http://snomed.info/sct\"
      ;;     :display \"Diabetes mellitus\"
      ;;     :version \"http://snomed.info/sct/900000000000207008/version/20250201\"}"
  [svc {:keys [url compose] :as vs-data}
   {:keys [code system force-system-version system-version check-system-version]
    caller-version :version
    :as params}]
  (let [expanding (conj (or (:expanding params) #{}) url)
        compose-result (compose/expand-compose svc
                         (narrow-includes-to-code compose system code)
                         (assoc params :expanding expanding))
        compose-issues (:issues compose-result)
        params (assoc params
                      :expanded              (:concepts compose-result)
                      :compose-issues        compose-issues
                      :multi-version-systems (or (:multi-version-systems compose-result) #{})
                      :vs-invalid-issue      (first (filter #(= "vs-invalid" (:details-code %)) compose-issues))
                      :not-found-issue       (first (filter #(= "not-found"  (:details-code %)) compose-issues)))]
    (s/assert ::result/validate
      (cond
        (:vs-invalid-issue params)
        (result-vs-invalid params)

        (:not-found-issue params)
        (result-not-found svc vs-data params)

        :else
        (let [match (-> (find-best-match svc params)
                        (align-match-to-wildcard-version svc vs-data params))
              include-ver (compose-version-for-system compose system
                            (or (:version match) caller-version))
              params (assoc params
                            :match            match
                            :include-ver      include-ver
                            :match-ver        (or (:version match) include-ver)
                            :force?           (some? (get force-system-version system))
                            :override-pattern (or (get force-system-version system)
                                                  (get system-version system)
                                                  (get check-system-version system)))]
          (if match
            (result-with-match svc vs-data params)
            (result-no-match svc vs-data params)))))))
