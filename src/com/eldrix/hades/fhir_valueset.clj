(ns com.eldrix.hades.fhir-valueset
  "FhirValueSet — a ValueSet backed by a compose definition (FHIR JSON).

  Implements the ValueSet protocol by delegating to the compose expansion
  engine. Used for tx-resource ValueSets and file-backed ValueSets."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [com.eldrix.hades.compose :as compose]
            [com.eldrix.hades.protocols :as protos]))

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
          match (some (fn [c]
                        (and (= code (:code c))
                             (or (nil? system) (= system (:system c)))
                             c))
                      expanded)]
      (if match
        (let [result (cond-> {"result"  true
                              "display" (:display match)
                              "code"    (keyword code)
                              "system"  (:system match)}
                       (:version match) (assoc "version" (:version match)))]
          (if (and display (not (str/blank? display))
                   (:display match)
                   (not= (str/lower-case display) (str/lower-case (:display match))))
            (let [lenient? (get ctx :lenient-display-validation true)
                  msg (str "Display '" display "' differs from preferred '" (:display match) "'")]
              (assoc result "result" (boolean lenient?)
                            "message" msg
                            "issues" [{:severity     (if lenient? "warning" "error")
                                       :type         "invalid"
                                       :details-code "invalid-display"
                                       :text         msg
                                       :expression   ["display"]}]))
            result))
        (let [code-ref (cond-> (str system "#" code)
                        display (str " ('" display "')"))
              vs-ref (if version (str url "|" version) url)
              not-in-vs-msg (str "The provided code '" code-ref "' was not found in the value set '" vs-ref "'")]
          {"result"  false
           "code"    (keyword code)
           "system"  system
           "message" not-in-vs-msg
           "issues"  [{:severity     "error"
                       :type         "code-invalid"
                       :details-code "not-in-vs"
                       :text         not-in-vs-msg
                       :expression   ["code"]}]})))))

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
