(ns com.eldrix.hades.fhir-valueset
  "FhirValueSet — a ValueSet backed by a compose definition (FHIR JSON).

  Implements the ValueSet protocol by delegating to the compose expansion
  engine. Used for tx-resource ValueSets and file-backed ValueSets."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [com.eldrix.hades.compose :as compose]
            [com.eldrix.hades.protocols :as protos]
            [com.eldrix.hades.registry :as registry]))

(defn- format-display-mismatch
  "Build a display-mismatch message following the FHIR spec format."
  [given-display system code primary-display designations display-language cs-language]
  (let [prefix (str "Wrong Display Name '" given-display "' for " system "#" code ". ")
        lang (or display-language "--")
        primary-lang (or (some (fn [d] (when (= (:value d) primary-display)
                                         (when-let [l (:language d)] (name l))))
                               designations)
                         cs-language)
        all-choices (cond-> []
                      primary-display
                      (conj {:display primary-display :lang primary-lang})
                      (seq designations)
                      (into (keep (fn [d] (when (and (:value d) (not= (:value d) primary-display))
                                            {:display (:value d)
                                             :lang (when-let [l (:language d)] (name l))})))
                            designations))
        has-lang-info? (some :lang all-choices)
        lang-filtered (if display-language
                        (filter #(= (:lang %) display-language) all-choices)
                        all-choices)
        unique-choices (distinct lang-filtered)]
    (cond
      (and display-language (empty? unique-choices) has-lang-info?)
      (str prefix "There are no valid display names found for language(s) '" lang
           "'. Default display is '" primary-display "'")

      (> (count unique-choices) 1)
      (let [formatted (map (fn [{:keys [display lang]}]
                             (if lang (str "'" display "' (" lang ")") (str "'" display "'")))
                           unique-choices)]
        (str prefix "Valid display is one of " (count unique-choices) " choices: "
             (str/join " or " formatted) " (for the language(s) '" lang "')"))

      (and (= 1 (count unique-choices)) (:lang (first unique-choices)))
      (let [{:keys [display lang]} (first unique-choices)]
        (str prefix "Valid display is '" display "' (" lang ") (for the language(s) '" lang "')"))

      :else
      (str prefix "Valid display is '" primary-display "' (for the language(s) '" lang "')"))))

(defn- compose-version-for-system
  "Extract the version pinned for a system in the compose definition."
  [compose-def system]
  (some (fn [inc]
          (when (= system (get inc "system"))
            (get inc "version")))
        (get compose-def "include")))

(defn- add-inactive-warning
  "Add inactive concept warning issue when the result indicates an inactive concept."
  [result]
  (if (:inactive result)
    (let [code (name (:code result))
          status (or (:inactive-status result) "inactive")
          msg (str "The concept '" code "' has a status of " status " and its use should be reviewed")
          issue {:severity     "warning"
                 :type         "business-rule"
                 :details-code "code-comment"
                 :text         msg
                 :expression   ["Coding"]}]
      (-> result
          (dissoc :inactive-status)
          (update :issues (fnil conj []) issue)
          (update :message (fn [existing]
                             (if existing
                               (str existing "; " msg)
                               msg)))))
    result))

(defn- add-version-mismatch
  "Add a version mismatch issue when the VS include version differs from the coding's.
  Severity is 'warning' when the caller's version doesn't exist (the not-found issue
  is the primary problem), 'error' otherwise."
  [result caller-version match-ver system override-pattern include-ver force? ctx]
  (if (and caller-version match-ver (not= caller-version match-ver))
    (let [caller-version-exists? (nil? (registry/unknown-version-issue ctx system caller-version))
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
  "Add check-system-version error when the resolved version doesn't match."
  [result ctx system resolved-version]
  (if-let [issue (registry/check-system-version-issue ctx system resolved-version)]
    (let [cur-msg (:message result)]
      (assoc result
             :result false
             :issues (conj (or (:issues result) []) issue)
             :message (if cur-msg (str cur-msg "; " (:text issue)) (:text issue))))
    result))

(defn- add-unknown-version-issue
  "Add UNKNOWN_CODESYSTEM_VERSION error when the caller's version doesn't exist."
  [result ctx system caller-version]
  (if-let [issue (registry/unknown-version-issue ctx system caller-version)]
    (let [cur-msg (:message result)]
      (assoc result
             :result false
             :issues (into [(assoc issue :priority 0)] (or (:issues result) []))
             :message (if cur-msg (str (:text issue) "; " cur-msg) (:text issue))
             :x-caused-by-unknown-system (registry/versioned-uri system caller-version)))
    result))

(deftype FhirValueSet [url version metadata compose-def]
  protos/ValueSet
  (vs-resource [_ _params]
    {:url          url
     :version      version
     :name         (get metadata "name")
     :title        (get metadata "title")
     :status       (get metadata "status")
     :experimental (get metadata "experimental")
     :compose      (get metadata "compose")})

  (vs-expand [_ ctx params]
    (let [expanding (conj (or (:expanding params) #{}) url)]
      (compose/expand-compose ctx compose-def (assoc params :expanding expanding))))

  (vs-validate-code [_ ctx params]
    (let [
          expanding (conj (or (:expanding params) #{}) url)
          expanded (:concepts (compose/expand-compose ctx compose-def {:expanding expanding}))
          {:keys [code system display]} params
          caller-version (:version params)
          match (or (some (fn [c]
                          (and (= code (:code c))
                               (or (nil? system) (= system (:system c)))
                               c))
                        expanded)
                    (some (fn [c]
                            (and (not= code (:code c))
                                 (= (str/lower-case code) (str/lower-case (:code c)))
                                 (or (nil? system) (= system (:system c)))
                                 (assoc c :case-differs true)))
                          expanded))
          include-ver (compose-version-for-system compose-def system)
          ;; When the include has a wildcard version and the caller's version
          ;; matches the pattern, re-lookup in the caller's CS version to get
          ;; the correct display/version for the response.
          include-is-wildcard? (and include-ver
                                    (some #(= "x" %) (str/split include-ver #"\.")))
          caller-matches-include? (and include-is-wildcard?
                                       caller-version
                                       (registry/version-matches? include-ver caller-version))
          match (if (and match caller-matches-include?
                         (not= caller-version (:version match)))
                  (let [cs-lookup (registry/codesystem-lookup ctx
                                    {:system system :code code :version caller-version})]
                    (if cs-lookup
                      (assoc match
                             :version caller-version
                             :display (:display cs-lookup))
                      match))
                  match)
          match-ver (or (:version match) include-ver)
          {:keys [force-system-version system-version check-system-version]
           :as request} (merge registry/default-request (:request ctx))
          force? (some? (get force-system-version system))
          override-pattern (or (get force-system-version system)
                               (get system-version system)
                               (get check-system-version system))]
      (if match
        (let [case-differs? (:case-differs match)
              actual-code (:code match)
              sys-ver (when (:system match)
                        (str (:system match) (when (:version match) (str "|" (:version match)))))
              result (cond-> {:result  true
                              :display (:display match)
                              :code    (keyword code)
                              :system  (:system match)}
                       (:version match) (assoc :version (:version match))
                       (:inactive match) (assoc :inactive true)
                       (:inactive-status match) (assoc :inactive-status (:inactive-status match))
                       case-differs? (assoc :normalized-code (keyword actual-code)))
              case-issue (when case-differs?
                           {:severity     "information"
                            :type         "business-rule"
                            :details-code "code-rule"
                            :text         (str "The code '" code "' differs from the correct code '"
                                               actual-code "' by case. Although the code system '"
                                               sys-ver "' is case insensitive, implementers "
                                               "are strongly encouraged to use the correct case anyway")
                            :expression   ["Coding.code"]})]
          (if (and display (not (str/blank? display))
                   (:display match)
                   (not= (str/lower-case display) (str/lower-case (:display match))))
            (let [lenient? (:lenient-display-validation request)
                  cs-impl (when (:system match) (registry/codesystem ctx (:system match)))
                  cs-lang (when cs-impl
                            (let [m (protos/cs-resource cs-impl {})]
                              (or (:language m) (get m "language"))))
                  msg (format-display-mismatch display (:system match) code
                        (:display match) (:designations match) (:displayLanguage params) cs-lang)
                  display-issue {:severity     (if lenient? "warning" "error")
                                 :type         "invalid"
                                 :details-code "invalid-display"
                                 :text         msg
                                 :expression   ["Coding.display"]}]
              (-> (assoc result :result (boolean lenient?)
                                :message msg
                                :issues (filterv some? [case-issue display-issue]))
                  (add-inactive-warning)
                  (add-version-mismatch caller-version match-ver system override-pattern include-ver force? ctx)
                  (add-unknown-version-issue ctx system caller-version)
                  (add-check-system-version-issue ctx system match-ver)
                  (registry/add-cs-status-warnings ctx system)
                  (registry/add-vs-status-warnings ctx url)))
            (-> (cond-> result
                  case-issue (assoc :issues [case-issue]))
                (add-inactive-warning)
                (add-version-mismatch caller-version match-ver system override-pattern include-ver force? ctx)
                (add-unknown-version-issue ctx system caller-version)
                (add-check-system-version-issue ctx system match-ver)
                (registry/add-cs-status-warnings ctx system)
                (registry/add-vs-status-warnings ctx url))))
        (let [code-ref (cond-> (str system "#" code)
                        display (str " ('" display "')"))
              vs-ref (if version (str url "|" version) url)
              not-in-vs-msg (str "The provided code '" code-ref "' was not found in the value set '" vs-ref "'")
              cs-result (when system
                          (registry/codesystem-validate-code ctx {:system system :code code}))
              ;; Fragment CS: code is provisionally valid even if not in VS expansion
              cs-impl (when system (registry/codesystem ctx system))
              cs-meta (when cs-impl (protos/cs-resource cs-impl {}))
              cs-fragment? (and cs-result (:result cs-result)
                               (= "fragment" (:content cs-meta)))
              cs-invalid? (and cs-result (not cs-fragment?) (false? (:result cs-result)))
              cs-issue (when cs-invalid?
                         (first (:issues cs-result)))]
          (if cs-fragment?
            (-> cs-result
                (assoc :code (keyword code))
                (registry/add-cs-status-warnings ctx system)
                (registry/add-vs-status-warnings ctx url))
            (let [base-issues [{:severity     "error"
                                :type         "code-invalid"
                                :details-code "not-in-vs"
                                :text         not-in-vs-msg
                                :expression   ["Coding.code"]}]
                  all-issues (if cs-issue (conj base-issues cs-issue) base-issues)
                  combined-msg (if cs-issue
                                 (str not-in-vs-msg "; " (:text cs-issue))
                                 not-in-vs-msg)
                  cs-display (when (and cs-result (:result cs-result)) (:display cs-result))
                  cs-version (when cs-result (:version cs-result))]
              (-> (cond-> {:result  false
                           :code    (keyword code)
                           :system  system
                           :message combined-msg
                           :issues  all-issues}
                    cs-display (assoc :display cs-display)
                    cs-version (assoc :version cs-version))
              (add-version-mismatch caller-version match-ver system override-pattern include-ver force? ctx)
              (add-unknown-version-issue ctx system caller-version)
              (add-check-system-version-issue ctx system match-ver)
              (registry/add-cs-status-warnings ctx system)
              (registry/add-vs-status-warnings ctx url)))))))))

(s/fdef make-fhir-value-set
  :args (s/cat :vs-map map?))

(defn make-fhir-value-set
  "Create a FhirValueSet from a parsed FHIR ValueSet JSON map (string keys)."
  [vs-map]
  (let [url (get vs-map "url")
        version (get vs-map "version")
        compose-def (get vs-map "compose")
        metadata (select-keys vs-map ["resourceType" "name" "title" "status"
                                      "description" "experimental" "purpose"
                                      "compose"])]
    (->FhirValueSet url version metadata compose-def)))
