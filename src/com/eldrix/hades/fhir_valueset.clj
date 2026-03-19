(ns com.eldrix.hades.fhir-valueset
  "FhirValueSet — a ValueSet backed by a compose definition (FHIR JSON).

  Implements the ValueSet protocol by delegating to the compose expansion
  engine. Used for tx-resource ValueSets and file-backed ValueSets."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [com.eldrix.hades.compose :as compose]
            [com.eldrix.hades.protocols :as protos]
            [com.eldrix.hades.registry :as registry]))

(defn- compose-version-for-system
  "Extract the version pinned for a system in the compose definition."
  [compose-def system]
  (some (fn [inc]
          (when (= system (get inc "system"))
            (get inc "version")))
        (get compose-def "include")))

(defn- version-expression
  [input-mode coding-index]
  (case input-mode
    :code "version"
    :coding "Coding.version"
    :codeableConcept (str "CodeableConcept.coding[" (or coding-index 0) "].version")
    "version"))

(defn- add-version-mismatch
  "Add a version mismatch error when the VS include version differs from the coding's."
  [result caller-version match-ver system override-pattern include-ver force? input-mode coding-index]
  (if (and caller-version match-ver (not= caller-version match-ver))
    (let [use-resulting-from? (and override-pattern
                                   (or force? (nil? include-ver) (= "" include-ver)))
          ver-desc (if use-resulting-from?
                     (str "version '" override-pattern "' resulting from the version '"
                          (or include-ver "") "'")
                     (str "version '" (or include-ver match-ver) "'"))
          msg (str "The code system '" system "' " ver-desc
                   " in the ValueSet include is different to the one in the value ('" caller-version "')")
          expr (version-expression input-mode coding-index)
          issue {:severity     "error"
                 :type         "invalid"
                 :details-code "vs-invalid"
                 :text         msg
                 :expression   [expr]}
          cur-msg (get result "message")]
      (assoc result
             "result" false
             "issues" (conj (or (get result "issues") []) issue)
             "message" (if cur-msg (str cur-msg "; " msg) msg)))
    result))

(defn- fix-expression-for-input-mode
  "Adjust issue expression paths based on input mode.
  Code-mode uses bare property names; coding-mode uses Coding.* paths;
  codeableConcept mode uses CodeableConcept.coding[n].* paths."
  ([issue input-mode] (fix-expression-for-input-mode issue input-mode 0))
  ([issue input-mode coding-index]
   (case input-mode
     :code (update issue :expression
                   (fn [exprs]
                     (mapv #(clojure.string/replace % #"^Coding\." "") exprs)))
     :codeableConcept (update issue :expression
                              (fn [exprs]
                                (mapv #(clojure.string/replace
                                         % #"^Coding\."
                                         (str "CodeableConcept.coding[" (or coding-index 0) "]."))
                                      exprs)))
     issue)))

(defn- add-check-system-version-issue
  "Add check-system-version error when the resolved version doesn't match."
  [result ctx system resolved-version input-mode coding-index]
  (if-let [issue (some-> (registry/check-system-version-issue ctx system resolved-version)
                         (fix-expression-for-input-mode input-mode coding-index))]
    (let [cur-msg (get result "message")]
      (assoc result
             "result" false
             "issues" (conj (or (get result "issues") []) issue)
             "message" (if cur-msg (str cur-msg "; " (:text issue)) (:text issue))))
    result))

(defn- add-unknown-version-issue
  "Add UNKNOWN_CODESYSTEM_VERSION error when the caller's version doesn't exist."
  [result ctx system caller-version input-mode coding-index]
  (if-let [issue (some-> (registry/unknown-version-issue ctx system caller-version)
                         (fix-expression-for-input-mode input-mode coding-index))]
    (let [cur-msg (get result "message")]
      (assoc result
             "result" false
             "issues" (into [(assoc issue :priority 0)] (or (get result "issues") []))
             "message" (if cur-msg (str (:text issue) "; " cur-msg) (:text issue))
             "x-caused-by-unknown-system" (registry/versioned-uri system caller-version)))
    result))

(deftype FhirValueSet [url version metadata compose-def]
  protos/ValueSet
  (vs-resource [_ _params]
    (assoc metadata "url" url "version" version))

  (vs-expand [_ params]
    (let [ctx (:ctx params)
          expanding (conj (or (:expanding params) #{}) url)]
      (compose/expand-compose ctx compose-def (assoc params :expanding expanding))))

  (vs-validate-code [_ params]
    (let [ctx (:ctx params)
          expanding (conj (or (:expanding params) #{}) url)
          expanded (compose/expand-compose ctx compose-def {:expanding expanding})
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
                             :display (get cs-lookup "display"))
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
              result (cond-> {"result"  true
                              "display" (:display match)
                              "code"    (keyword code)
                              "system"  (:system match)}
                       (:version match) (assoc "version" (:version match))
                       (:inactive match) (assoc "inactive" true
                                                "inactive-status" (:inactive-status match))
                       case-differs? (assoc "normalized-code" (keyword actual-code)))
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
                  msg (str "Display '" display "' differs from preferred '" (:display match) "'")
                  display-issue {:severity     (if lenient? "warning" "error")
                                 :type         "invalid"
                                 :details-code "invalid-display"
                                 :text         msg
                                 :expression   ["display"]}]
              (-> (assoc result "result" (boolean lenient?)
                                "message" msg
                                "issues" (filterv some? [case-issue display-issue]))
                  (add-version-mismatch caller-version match-ver system override-pattern include-ver force? (:input-mode params) (:coding-index params))
                  (add-unknown-version-issue ctx system caller-version (:input-mode params) (:coding-index params))
                  (add-check-system-version-issue ctx system match-ver (:input-mode params) (:coding-index params))))
            (-> (cond-> result
                  case-issue (assoc "issues" [case-issue]))
                (add-version-mismatch caller-version match-ver system override-pattern include-ver force? (:input-mode params) (:coding-index params))
                (add-unknown-version-issue ctx system caller-version (:input-mode params) (:coding-index params))
                (add-check-system-version-issue ctx system match-ver (:input-mode params) (:coding-index params)))))
        (let [code-ref (cond-> (str system "#" code)
                        display (str " ('" display "')"))
              vs-ref (if version (str url "|" version) url)
              not-in-vs-msg (str "The provided code '" code-ref "' was not found in the value set '" vs-ref "'")]
          (-> {"result"  false
               "code"    (keyword code)
               "system"  system
               "message" not-in-vs-msg
               "issues"  [{:severity     "error"
                           :type         "code-invalid"
                           :details-code "not-in-vs"
                           :text         not-in-vs-msg
                           :expression   ["code"]}]}
              (add-version-mismatch caller-version match-ver system override-pattern include-ver force? (:input-mode params) (:coding-index params))
              (add-unknown-version-issue ctx system caller-version (:input-mode params) (:coding-index params))
              (add-check-system-version-issue ctx system match-ver (:input-mode params) (:coding-index params))))))))

(s/fdef make-fhir-value-set
  :args (s/cat :vs-map map?))

(defn make-fhir-value-set
  "Create a FhirValueSet from a parsed FHIR ValueSet JSON map (string keys)."
  [vs-map]
  (let [url (get vs-map "url")
        version (get vs-map "version")
        compose-def (get vs-map "compose")
        metadata (select-keys vs-map ["resourceType" "name" "title" "status"
                                      "description" "experimental" "purpose"])]
    (->FhirValueSet url version metadata compose-def)))
