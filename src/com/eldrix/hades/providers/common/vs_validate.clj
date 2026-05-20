(ns com.eldrix.hades.providers.common.vs-validate
  "Pure compose-driven `vs-validate-code` implementation, shared by every
  ValueSet provider that's backed by a parsed compose definition.

  `validate-code` runs the compose engine to expand the ValueSet, finds
  the caller's code+system within the expansion, applies version
  matching / display validation / inactive-status / fragment-CS /
  status-warning logic, and returns a `::result/validate`.

  Provider impls (`MemoryValueSet`, `FtrmValueSetCatalogue`, …) call
  this with their own `vs-data` `{:url :version :compose :metadata}`
  map. Behaviour is identical across backends because the function only
  depends on the svc and the compose def."
  (:require [clojure.string :as str]
            [com.eldrix.hades.providers.common.canonical :as canonical]
            [com.eldrix.hades.providers.common.compose :as compose]
            [com.eldrix.hades.composite :as composite]
            [com.eldrix.hades.providers.common.display :as display]
            [com.eldrix.hades.providers.common.issues :as issues]
            [com.eldrix.hades.protocols :as protos]))

(set! *warn-on-reflection* true)

(defn- compose-version-for-system
  ([compose-def system] (compose-version-for-system compose-def system nil))
  ([compose-def system preferred]
   (let [matches (keep (fn [inc]
                         (when (= system (get inc "system"))
                           (get inc "version")))
                       (get compose-def "include"))]
     (or (when preferred (some #{preferred} matches))
         (first matches)))))

(defn- code-filter [code]
  {"property" "code" "op" "=" "value" code})

(defn- match-driven-system-include?
  [include system]
  (and (= system (get include "system"))
       (not (seq (get include "concept")))))

(defn- add-code-filter
  [include code]
  (update include "filter" (fnil conj []) (code-filter code)))

(defn- narrow-match-driven-includes
  "For ValueSet validation we only need to know whether one coding is in
  the compose. Match-driven includes such as `{system: http://loinc.org}`
  can be too large for provider-default expansion windows, so push the
  exact code predicate into matching includes/excludes before expanding.
  Explicit `concept[]` includes are already bounded and are left intact."
  [compose-def system code]
  (if (and system code
           (some #(match-driven-system-include? % system)
                 (concat (get compose-def "include")
                         (get compose-def "exclude"))))
    (let [narrow (fn [include]
                   (if (match-driven-system-include? include system)
                     (add-code-filter include code)
                     include))]
      (cond-> compose-def
        (contains? compose-def "include") (update "include" #(mapv narrow %))
        (contains? compose-def "exclude") (update "exclude" #(mapv narrow %))))
    compose-def))

(defn- add-version-mismatch
  [result caller-version match-ver system override-pattern include-ver force? svc]
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
  [result svc system resolved-version params]
  (if-let [issue (composite/check-system-version-issue svc system resolved-version params)]
    (let [cur-msg (:message result)]
      (assoc result
             :result false
             :issues (conj (or (:issues result) []) issue)
             :message (if cur-msg (str cur-msg "; " (:text issue)) (:text issue))))
    result))

(defn- add-unknown-version-issue
  [result svc system caller-version]
  (if-let [issue (composite/unknown-version-issue svc system caller-version)]
    (let [cur-msg (:message result)]
      (assoc result
             :result false
             :issues (into [(assoc issue :priority 0)] (or (:issues result) []))
             :message (if cur-msg (str (:text issue) "; " cur-msg) (:text issue))
             :x-caused-by-unknown-system (canonical/versioned-uri system caller-version)))
    result))

;; ---------------------------------------------------------------------------
;; Branch helpers — each handles one exit shape for `validate-code`.
;; All take pre-computed inputs: the public entry `validate-code` does the
;; one-time work (compose expansion, parameter destructuring) and dispatches.
;; ---------------------------------------------------------------------------

(defn- result-vs-invalid
  "Compose itself was invalid — surface the issue verbatim."
  [vs-invalid-issue compose-issues code system]
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
  [svc compose not-found-issue compose-issues code system caller-version]
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
      (and cs-result (:result cs-result) (:display cs-result))
      (assoc :display (:display cs-result))
      (and cs-result (:version cs-result))
      (assoc :version (:version cs-result)))))

(defn- find-best-match
  "Walk the expanded concept list and pick the best match for the caller.
  Returns nil when no concept matches by code+system. Sets `:case-differs`
  on the returned match when the only match is case-insensitive and the
  matched CodeSystem declares caseSensitive=false."
  [svc expanded code system display caller-version multi-version-systems]
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
  [match svc compose system code caller-version]
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
  "Apply the standard chain of cross-cutting concerns to a result map.
  Order is significant: inactive warnings precede version mismatch
  precede unknown-version precede check-system-version, then the
  CS/VS status warnings sit on the outside. Used by both the match
  and no-match exits."
  [result svc system url caller-version match-ver override-pattern include-ver force? params]
  (-> result
      (issues/add-inactive-warning)
      (add-version-mismatch caller-version match-ver system override-pattern include-ver force? svc)
      (add-unknown-version-issue svc system caller-version)
      (add-check-system-version-issue svc system match-ver params)
      (composite/add-cs-status-warnings svc system)
      (composite/add-vs-status-warnings svc url)))

(defn- result-with-match
  "A concept was found in the expansion; return the validation success or
  the lenient/strict display-mismatch shape, with cross-cutting concerns
  applied."
  [match svc url params code display caller-version include-ver match-ver override-pattern force?]
  (let [system (:system match)
        case-differs? (:case-differs match)
        actual-code  (:code match)
        display-langs (display/parse-display-language (:displayLanguage params))
        lang-display  (when (seq display-langs)
                        (display/find-display-for-language (:designations match) display-langs))
        best-display  (or lang-display (:display match))
        result (cond-> {:result  true
                        :display best-display
                        :code    (keyword code)
                        :system  system}
                 (:version match) (assoc :version (:version match))
                 (:inactive match) (assoc :inactive true)
                 (:inactive-status match) (assoc :inactive-status (:inactive-status match))
                 case-differs? (assoc :normalized-code (keyword actual-code)))
        case-issue (when case-differs?
                     {:severity     "information"
                      :type         "business-rule"
                      :details-code "code-rule"
                      :text         (issues/format-case-mismatch
                                     code actual-code system (:version match))
                      :expression   ["Coding.code"]})
        display-mismatch? (and display (not (str/blank? display))
                               (:display match)
                               (not (display/display-matches? match display display-langs)))
        result (if display-mismatch?
                 (let [lenient?  (let [v (:lenient-display-validation params)]
                                   (if (contains? params :lenient-display-validation) v true))
                       cs-m      (when system (composite/cs-meta svc system))
                       cs-lang   (when cs-m (or (:language cs-m) (get cs-m "language")))
                       msg       (issues/format-display-mismatch display system code
                                   (:display match) (:designations match) (:displayLanguage params) cs-lang)
                       display-issue {:severity     (if lenient? "warning" "error")
                                      :type         "invalid"
                                      :details-code "invalid-display"
                                      :text         msg
                                      :expression   ["Coding.display"]}]
                   (assoc result :result (boolean lenient?)
                                 :message msg
                                 :issues (filterv some? [case-issue display-issue])))
                 (cond-> result case-issue (assoc :issues [case-issue])))]
    (apply-cross-cutting result svc system url caller-version match-ver override-pattern include-ver force? params)))

(defn- result-no-match
  "No concept was found in the expansion. Build the not-in-vs failure
  shape, layering cs-validate insight and (rarely) the fragment
  CodeSystem semantics on top."
  [svc compose url version params code system display caller-version override-pattern force?]
  (let [code-ref (cond-> (str system "#" code)
                   display (str " ('" display "')"))
        vs-ref (if version (str url "|" version) url)
        not-in-vs-msg (str "The provided code '" code-ref "' was not found in the value set '" vs-ref "'")
        cs-result (when system (protos/cs-validate-code svc {:system system :code code}))
        cs-m (when system (composite/cs-meta svc system))
        cs-fragment? (and cs-result (:result cs-result) (= "fragment" (:content cs-m)))
        cs-invalid? (and cs-result (not cs-fragment?) (false? (:result cs-result)))
        cs-issue (when cs-invalid?
                   (let [i (first (:issues cs-result))]
                     ;; When the caller pinned a version, the version-specific
                     ;; unknown-version issue (added below) supersedes the bare
                     ;; "system not found" issue from cs-result. Drop the latter
                     ;; to avoid duplicate not-found entries.
                     (when-not (and caller-version (= "not-found" (:details-code i)))
                       i)))
        include-ver (compose-version-for-system compose system caller-version)
        match-ver include-ver]
    (if cs-fragment?
      (-> cs-result
          (assoc :code (keyword code))
          (composite/add-cs-status-warnings svc system)
          (composite/add-vs-status-warnings svc url))
      (let [base-issues [{:severity     "error"
                          :type         "code-invalid"
                          :details-code "not-in-vs"
                          :text         not-in-vs-msg
                          :expression   ["Coding.code"]}]
            all-issues  (if cs-issue (conj base-issues cs-issue) base-issues)
            combined-msg (if cs-issue (str not-in-vs-msg "; " (:text cs-issue)) not-in-vs-msg)
            cs-display  (when (and cs-result (:result cs-result)) (:display cs-result))
            cs-version  (when cs-result (:version cs-result))
            cs-unknown-sys (:x-unknown-system cs-result)
            result (cond-> {:result  false
                            :code    (keyword code)
                            :message combined-msg
                            :issues  all-issues}
                     system (assoc :system system)
                     cs-unknown-sys (assoc :x-unknown-system cs-unknown-sys)
                     cs-display (assoc :display cs-display)
                     cs-version (assoc :version cs-version))]
        ;; result-no-match deliberately skips `add-inactive-warning` (no
        ;; match means no `:inactive` flag to surface).
        (-> result
            (add-version-mismatch caller-version match-ver system override-pattern include-ver force? svc)
            (add-unknown-version-issue svc system caller-version)
            (add-check-system-version-issue svc system match-ver params)
            (composite/add-cs-status-warnings svc system)
            (composite/add-vs-status-warnings svc url))))))

(defn validate-code
  "Compose-driven `vs-validate-code` shared by every backend.

  Args:
    svc      — the TerminologyService (composite); compose calls its
               protocol methods for cross-CodeSystem lookups.
    vs-data  — `{:url :version :compose}` for the ValueSet.
    params   — the operation params: `{:code :system :display :version
               :displayLanguage :force-system-version :system-version
               :check-system-version :lenient-display-validation
               :expanding}`.

  Returns a `::result/validate`. No backend-private state is
  consulted: this is pure transformation over `compose/expand-compose`
  and protocol callbacks on `svc`. Branches are split into
  `result-vs-invalid`, `result-not-found`, `result-with-match` and
  `result-no-match`."
  [svc {:keys [url version compose]} params]
  (let [expanding (conj (or (:expanding params) #{}) url)
        {:keys [code system display]} params
        validation-compose (narrow-match-driven-includes compose system code)
        ;; Forward request flags (force-/system-/check-system-version) to
        ;; compose so version overrides apply during the validation expand.
        compose-params (-> (select-keys params [:force-system-version :system-version
                                                :check-system-version :displayLanguage])
                           (assoc :expanding expanding))
        compose-result (compose/expand-compose svc validation-compose compose-params)
        compose-issues (:issues compose-result)
        expanded (:concepts compose-result)
        multi-version-systems (or (:multi-version-systems compose-result) #{})
        caller-version (:version params)
        vs-invalid-issue (first (filter #(= "vs-invalid" (:details-code %)) compose-issues))
        not-found-issue  (first (filter #(= "not-found"  (:details-code %)) compose-issues))]
    (cond
      vs-invalid-issue
      (result-vs-invalid vs-invalid-issue compose-issues code system)

      not-found-issue
      (result-not-found svc compose not-found-issue compose-issues code system caller-version)

      :else
      (let [match (-> (find-best-match svc expanded code system display caller-version multi-version-systems)
                      (align-match-to-wildcard-version svc compose system code caller-version))
            include-ver (compose-version-for-system compose system
                          (or (:version match) caller-version))
            match-ver (or (:version match) include-ver)
            {:keys [force-system-version system-version check-system-version]} params
            force? (some? (get force-system-version system))
            override-pattern (or (get force-system-version system)
                                 (get system-version system)
                                 (get check-system-version system))]
        (if match
          (result-with-match match svc url params code display caller-version
                             include-ver match-ver override-pattern force?)
          (result-no-match svc compose url version params code system display
                           caller-version override-pattern force?))))))
