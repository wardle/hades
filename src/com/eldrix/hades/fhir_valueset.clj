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

(defn- add-version-mismatch
  "Add a version mismatch error when the VS include version differs from the coding's."
  [result caller-version match-ver system override-pattern include-ver]
  (if (and caller-version match-ver (not= caller-version match-ver))
    (let [ver-desc (if override-pattern
                     (str "version '" override-pattern "' resulting from the version '"
                          (or include-ver "") "'")
                     (str "version '" match-ver "'"))
          msg (str "The code system '" system "' " ver-desc
                   " in the ValueSet include is different to the one in the value ('" caller-version "')")
          issue {:severity     "error"
                 :type         "invalid"
                 :details-code "vs-invalid"
                 :text         msg
                 :expression   ["Coding.version"]}
          cur-msg (get result "message")]
      (assoc result
             "result" false
             "issues" (conj (or (get result "issues") []) issue)
             "message" (if cur-msg (str cur-msg "; " msg) msg)))
    result))

(defn- add-check-system-version-issue
  "Add check-system-version error when the resolved version doesn't match."
  [result ctx system resolved-version]
  (if-let [issue (registry/check-system-version-issue ctx system resolved-version)]
    (let [cur-msg (get result "message")]
      (assoc result
             "result" false
             "issues" (conj (or (get result "issues") []) issue)
             "message" (if cur-msg (str cur-msg "; " (:text issue)) (:text issue))))
    result))

(defn- add-unknown-version-issue
  "Add UNKNOWN_CODESYSTEM_VERSION error when the caller's version doesn't exist."
  [result ctx system caller-version]
  (if-let [issue (registry/unknown-version-issue ctx system caller-version)]
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
          match (some (fn [c]
                        (and (= code (:code c))
                             (or (nil? system) (= system (:system c)))
                             c))
                      expanded)
          include-ver (compose-version-for-system compose-def system)
          match-ver (or (:version match) include-ver)
          override-pattern (or (get (:force-system-version ctx) system)
                               (get (:system-version ctx) system)
                               (get (:check-system-version ctx) system))]
      (if match
        (let [result (cond-> {"result"  true
                              "display" (:display match)
                              "code"    (keyword code)
                              "system"  (:system match)}
                       (:version match) (assoc "version" (:version match))
                       (:inactive match) (assoc "inactive" true
                                                "inactive-status" (:inactive-status match)))]
          (if (and display (not (str/blank? display))
                   (:display match)
                   (not= (str/lower-case display) (str/lower-case (:display match))))
            (let [lenient? (get ctx :lenient-display-validation true)
                  msg (str "Display '" display "' differs from preferred '" (:display match) "'")]
              (-> (assoc result "result" (boolean lenient?)
                                "message" msg
                                "issues" [{:severity     (if lenient? "warning" "error")
                                           :type         "invalid"
                                           :details-code "invalid-display"
                                           :text         msg
                                           :expression   ["display"]}])
                  (add-version-mismatch caller-version match-ver system override-pattern include-ver)
                  (add-unknown-version-issue ctx system caller-version)
                  (add-check-system-version-issue ctx system match-ver)))
            (-> result
                (add-version-mismatch caller-version match-ver system override-pattern include-ver)
                (add-unknown-version-issue ctx system caller-version)
                (add-check-system-version-issue ctx system match-ver))))
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
              (add-version-mismatch caller-version match-ver system override-pattern include-ver)
              (add-unknown-version-issue ctx system caller-version)
              (add-check-system-version-issue ctx system match-ver)))))))

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
