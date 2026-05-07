(ns com.eldrix.hades.impl.server-test
  "Integration tests for the Hades FHIR server endpoints. Boots a real
  HTTP server via `fixtures/start-server` against the pinned SNOMED CT
  DB and asserts response shapes from a data-driven case table.

  Tagged `^:live` — requires the pinned SNOMED CT International release;
  exclude with `clj -M:test -e :live`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.eldrix.hades.core :as hades]
            [com.eldrix.hades.fixtures :as fixtures]
            [com.eldrix.hades.impl.protocols :as protos]))

(def ^:dynamic *svc* nil)
(def ^:dynamic *server* nil)

(defn server-fixture [f]
  (let [svc (hades/open [(fixtures/assert-snomed-db!)])
        srv (fixtures/start-server svc)]
    (binding [*svc* svc *server* srv]
      (try (f)
           (finally
             (fixtures/stop-server srv)
             (hades/close svc))))))

(use-fixtures :once server-fixture)

(defn- request! [req]
  (fixtures/request! (:url *server*) req))

(defn- get-param [body name]
  (some (fn [p] (when (= name (get p "name")) p))
        (get body "parameter")))

;; ---------------------------------------------------------------------------
;; Helpers used by the case table to assert response shapes.
;; Each predicate takes the parsed JSON body and returns truthy on match.
;; ---------------------------------------------------------------------------

(defn- name-starts-with-version-uri? [body]
  (str/starts-with? (get (get-param body "name") "valueString")
                    "http://snomed.info/sct|http://snomed.info/sct/"))

(defn- has-display? [body]
  (some? (get (get-param body "display") "valueString")))

(defn- result-true? [body]
  (true? (get (get-param body "result") "valueBoolean")))

(defn- result-false? [body]
  (false? (get (get-param body "result") "valueBoolean")))

(defn- has-issues? [body]
  (some? (get (get-param body "issues") "resource")))

(defn- system-uri-equals? [expected]
  (fn [body] (= expected (get (get-param body "system") "valueUri"))))

(defn- operation-outcome? [body]
  (= "OperationOutcome" (get body "resourceType")))

(defn- issue-text-matches? [pattern]
  (fn [body] (re-find pattern (get-in body ["issue" 0 "details" "text"] ""))))

(defn- issue-text-not-matches? [pattern]
  (fn [body] (not (re-find pattern (get-in body ["issue" 0 "details" "text"] "")))))

(defn- issue-code-equals? [expected]
  (fn [body] (= expected (get-in body ["issue" 0 "code"]))))

(defn- display-matches? [pattern]
  (fn [body] (re-find pattern (get (get-param body "display") "valueString"))))

(defn- display-includes? [substring]
  (fn [body] (str/includes? (get (get-param body "display") "valueString") substring)))

(defn- valueset-resource? [body]
  (= "ValueSet" (get body "resourceType")))

(defn- expansion-bounded-by? [n]
  (fn [body] (<= (count (get-in body ["expansion" "contains"] [])) n)))

(defn- match-equivalence-equivalent? [body]
  (let [match (get-param body "match")
        parts (->> (get match "part") (map (juxt #(get % "name") identity)) (into {}))]
    (= "equivalent" (get-in parts ["equivalence" "valueCode"]))))

(defn- match-target-code-equals? [code]
  (fn [body]
    (let [match (get-param body "match")
          parts (->> (get match "part") (map (juxt #(get % "name") identity)) (into {}))]
      (= code (get-in parts ["concept" "valueCoding" "code"])))))

(defn- no-match? [body]
  (nil? (get-param body "match")))

(defn- check
  "Run one case from the table. Asserts status, optional content-type
  regex, and every body predicate (each `assertions` entry is
  `[label pred]`)."
  [{:keys [name request expect]}]
  (testing name
    (let [{:keys [status content-type body]} (request! request)]
      (is (= (:status expect) status)
          (str name " — expected status " (:status expect) ", got " status))
      (when-let [ct (:content-type expect)]
        (is (re-find ct content-type)
            (str name " — content-type " content-type)))
      (doseq [[label pred] (:assertions expect)]
        (is (pred body) (str name " — " label))))))

;; ---------------------------------------------------------------------------
;; Case table
;; ---------------------------------------------------------------------------

(def ^:private cases
  [{:name "lookup valid SNOMED code returns 200 + display"
    :request {:path "/CodeSystem/$lookup?system=http://snomed.info/sct&code=73211009"}
    :expect {:status 200
             :assertions [["name in system|version-uri form" name-starts-with-version-uri?]
                          ["display present" has-display?]]}}

   {:name "lookup unknown code returns 404 OperationOutcome"
    :request {:path "/CodeSystem/$lookup?system=http://snomed.info/sct&code=999999999"}
    :expect {:status 404
             :assertions [["body is OperationOutcome" operation-outcome?]
                          ["text mentions unknown code" (issue-text-matches? #"(?i)unknown code")]
                          ["text does not say unknown code system" (issue-text-not-matches? #"(?i)unknown code system")]]}}

   {:name "lookup unknown code system returns 404 OperationOutcome"
    :request {:path "/CodeSystem/$lookup?system=http://example.com/fake&code=123"}
    :expect {:status 404
             :assertions [["body is OperationOutcome" operation-outcome?]]}}

   {:name "$validate-code with system query param succeeds"
    :request {:path "/CodeSystem/$validate-code?system=http://snomed.info/sct&code=73211009"}
    :expect {:status 200
             :assertions [["result=true" result-true?]
                          ["echoes system" (system-uri-equals? "http://snomed.info/sct")]]}}

   {:name "$validate-code unknown code returns result=false + issues"
    :request {:path "/CodeSystem/$validate-code?system=http://snomed.info/sct&code=999999999"}
    :expect {:status 200
             :assertions [["result=false" result-false?]
                          ["issues populated" has-issues?]]}}

   {:name "$validate-code unknown system returns result=false"
    :request {:path "/CodeSystem/$validate-code?system=http://example.com/fake&code=123"}
    :expect {:status 200
             :assertions [["result=false" result-false?]]}}

   ;; SCG expressions (post-coordinated SNOMED) — anything non-numeric routes
   ;; through the SCG parser rather than the simple-code path.
   {:name "$lookup of a term-annotated SCG expression"
    :request {:path (str "/CodeSystem/$lookup?system=http://snomed.info/sct"
                         "&code=73211009%20%7CDiabetes%7C")}
    :expect {:status 200
             :assertions [["display contains 'diabetes'" (display-matches? #"(?i)diabetes")]]}}

   {:name "$lookup of a `+` compound SCG expression"
    :request {:path (str "/CodeSystem/$lookup?system=http://snomed.info/sct"
                         "&code=73211009%20%2B%2024700007")}
    :expect {:status 200
             :assertions [["display present" has-display?]]}}

   {:name "$lookup of a `:` refinement SCG expression"
    :request {:path (str "/CodeSystem/$lookup?system=http://snomed.info/sct"
                         "&code=24700007%3A363698007%3D56459004")}
    :expect {:status 200
             :assertions [["display present" has-display?]
                          ["display includes refinement attribute" (display-includes? "363698007")]]}}

   {:name "$validate-code on a well-formed SCG expression returns true"
    :request {:path (str "/CodeSystem/$validate-code?system=http://snomed.info/sct"
                         "&code=24700007%3A363698007%3D56459004")}
    :expect {:status 200
             :assertions [["result=true" result-true?]]}}

   {:name "$validate-code on SCG with unknown focus returns false"
    :request {:path (str "/CodeSystem/$validate-code?system=http://snomed.info/sct"
                         "&code=999999999999%3A363698007%3D56459004")}
    :expect {:status 200
             :assertions [["result=false" result-false?]]}}

   {:name "POST $expand with inline valueSet (descendent-of)"
    :request {:method :post
              :path "/ValueSet/$expand"
              :body {:resourceType "Parameters"
                     :parameter [{:name "valueSet"
                                  :resource {:resourceType "ValueSet"
                                             :compose {:include [{:system "http://snomed.info/sct"
                                                                  :filter [{:property "concept"
                                                                            :op "descendent-of"
                                                                            :value "64572001"}]}]}}}
                                 {:name "count" :valueInteger 10}]}}
    :expect {:status 200
             :assertions [["body is ValueSet" valueset-resource?]
                          ["expansion ≤ 10" (expansion-bounded-by? 10)]]}}

   {:name "POST $expand with inline valueSet and multiple filters"
    :request {:method :post
              :path "/ValueSet/$expand"
              :body {:resourceType "Parameters"
                     :parameter [{:name "valueSet"
                                  :resource {:resourceType "ValueSet"
                                             :compose {:include [{:system "http://snomed.info/sct"
                                                                  :filter [{:property "concept"
                                                                            :op "is-a"
                                                                            :value "195967001"}
                                                                           {:property "363698007"
                                                                            :op "="
                                                                            :value "89187006"}]}]}}}
                                 {:name "count" :valueInteger 10}]}}
    :expect {:status 200
             :assertions [["body is ValueSet" valueset-resource?]]}}

   {:name "$translate resolves SNOMED REPLACED BY for a retired code"
    :request {:path (str "/ConceptMap/$translate"
                         "?url=http%3A%2F%2Fsnomed.info%2Fsct%3Ffhir_cm%3D900000000000526001"
                         "&system=http%3A%2F%2Fsnomed.info%2Fsct&code=225983005"
                         "&target=http%3A%2F%2Fsnomed.info%2Fsct")}
    :expect {:status 200
             :assertions [["result=true" result-true?]
                          ["match is equivalent" match-equivalence-equivalent?]
                          ["target code 441207001" (match-target-code-equals? "441207001")]]}}

   {:name "$translate active code with no historical association"
    :request {:path (str "/ConceptMap/$translate"
                         "?url=http%3A%2F%2Fsnomed.info%2Fsct%3Ffhir_cm%3D900000000000526001"
                         "&system=http%3A%2F%2Fsnomed.info%2Fsct&code=73211009"
                         "&target=http%3A%2F%2Fsnomed.info%2Fsct")}
    :expect {:status 200
             :assertions [["result=false" result-false?]
                          ["no match part" no-match?]]}}

   {:name "$translate with unknown ConceptMap url returns 404 OperationOutcome"
    :request {:path "/ConceptMap/$translate?url=http://example.com/fake&system=http://snomed.info/sct&code=225983005"}
    :expect {:status 404
             :assertions [["body is OperationOutcome" operation-outcome?]
                          ["issue.code = not-found" (issue-code-equals? "not-found")]]}}

   {:name "unrouted path returns FHIR-shaped 404"
    :request {:path "/Observation?url=http://example.com"
              :headers {}}
    :expect {:status 404
             :content-type #"application/fhir\+json"
             :assertions [["body is OperationOutcome" operation-outcome?]
                          ["issue.code = not-found" (issue-code-equals? "not-found")]]}}])

(deftest ^:live operation-cases
  (doseq [c cases] (check c)))

;; ---------------------------------------------------------------------------
;; ConceptMap dispatch tests — these branch on whether external concept-map
;; refsets happen to be installed in the pinned SNOMED DB, so they live
;; outside the table.
;; ---------------------------------------------------------------------------

(defn- conceptmap-descriptions []
  (protos/cm-metadata *svc*))

(defn- conceptmap-registered-for? [source target]
  (some (fn [d] (and (= source (:system d)) (= target (:target d))))
        (conceptmap-descriptions)))

(deftest ^:live translate-pair-lookup-dispatches-to-provider
  (let [sct "http://snomed.info/sct"
        icd-o "http://hl7.org/fhir/sid/icd-o"]
    (when (conceptmap-registered-for? sct icd-o)
      (testing "(system,target) pair resolves to the right provider without a url"
        (is (= 200 (:status (request! {:path (str "/ConceptMap/$translate?system=" sct
                                                  "&code=000&target=" icd-o)}))))))
    (when (conceptmap-registered-for? icd-o sct)
      (testing "Reverse pair (external → SCT) resolves to the same provider"
        (is (= 200 (:status (request! {:path (str "/ConceptMap/$translate?system=" icd-o
                                                  "&code=FAKE&target=" sct)}))))))))

(deftest ^:live cm-metadata-lists-installed-refsets
  (testing "cm-metadata emits SCT→SCT historical maps + pairs for any installed external maps"
    (let [descs  (conceptmap-descriptions)
          snomed "http://snomed.info/sct"]
      (is (seq (filter #(= [snomed snomed] [(:system %) (:target %)]) descs))
          "at least one SCT→SCT historical association should be registered")
      (is (every? :url descs)
          "every description carries a canonical url for direct $translate dispatch"))))
