(ns com.eldrix.hades.system-aliases-live-test
  "Live integration tests for system-alias resolution (OID/URN → canonical)
  against the combined FTRM container `data/fhir-tx.db`.

  Every resource addressed here is present in the loaded FHIR packages and
  resolves by its canonical URL (the `*-canonical` control cases). Each also
  ships an alias in its source JSON:

    • CodeSystem  `…/CodeSystem/v2-0203`  carries `.identifier`
      `urn:oid:2.16.840.1.113883.18.108`.
    • ValueSet    `…/ValueSet/1.2.91.13925.17760.26050446` (VSAC) carries
      `.identifier` `urn:oid:1.2.91.13925.17760.26050446` — one of 7,877
      VSAC ValueSets with an OID identifier.

  The alias cases assert that addressing a resource by its OID/URN alias
  resolves to the same provider as its canonical URL — via the providers'
  `NamingService` (OID/URN → `{:url :kind}`), which the composite consults
  on a dispatch miss. ConceptMap alias resolution is covered by the
  synthetic unit test (see the alias-cases tail comment)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.eldrix.hades.composite :as composite]
            [com.eldrix.hades.core :as hades]
            [com.eldrix.hades.fixtures :as fixtures]))

(def ^:dynamic *svc* nil)

(defn provider-fixture [f]
  (with-open [svc (hades/open (fixtures/paths [:fhir/tx]) {})]
    (binding [*svc* svc]
      (f))))

(use-fixtures :once provider-fixture)

;; ---------------------------------------------------------------------------
;; Predicates over operation results.
;; ---------------------------------------------------------------------------

(defn- resolved? [r] (some? r))
(defn- display? [display]
  (fn [r] (= display (:display r))))
(defn- expand-includes? [code]
  (fn [r] (boolean (some #(= code (:code %)) (:concepts r)))))
(defn- translate-to? [code]
  (fn [r] (and (true? (:result r))
               (some #(= code (:code %)) (:matches r)))))

(defn- run-op [op input]
  (case op
    :lookup    (hades/lookup *svc* input)
    :expand    (hades/expand *svc* input)
    :translate (hades/translate *svc* input)
    :find-cs   (composite/find-codesystem *svc* (:id input))
    :find-vs   (composite/find-valueset *svc* (:id input))))

(defn- check [{:keys [name op input expect]}]
  (testing name
    (let [r (run-op op input)]
      (doseq [[label pred] expect]
        (is (pred r) (str name " — " label))))))

;; ---------------------------------------------------------------------------
;; Controls: every resource resolves by its canonical URL. These confirm the
;; resource is loaded, so an alias failure is a routing gap, not a missing
;; resource.
;; ---------------------------------------------------------------------------

(def ^:private canonical-cases
  [{:name "CodeSystem $lookup by canonical URL"
    :op :lookup :input {:system "http://terminology.hl7.org/CodeSystem/v2-0203" :code "AM"}
    :expect [["display" (display? "American Express")]]}

   {:name "CodeSystem find-codesystem by canonical URL"
    :op :find-cs :input {:id "http://terminology.hl7.org/CodeSystem/v2-0203"}
    :expect [["resolves" resolved?]]}

   {:name "ValueSet $expand by canonical URL"
    :op :expand :input {:url "http://cts.nlm.nih.gov/fhir/ValueSet/1.2.91.13925.17760.26050446"}
    :expect [["includes member" (expand-includes? "69610")]]}

   {:name "ValueSet find-valueset by canonical URL"
    :op :find-vs :input {:id "http://cts.nlm.nih.gov/fhir/ValueSet/1.2.91.13925.17760.26050446"}
    :expect [["resolves" resolved?]]}

   {:name "ConceptMap $translate by canonical URL"
    :op :translate :input {:url "http://hl7.org/fhir/ConceptMap/101" :code "home"}
    :expect [["maps to target" (translate-to? "H")]]}])

;; ---------------------------------------------------------------------------
;; Aliases: the same resources addressed by their OID/URN alias. Desired
;; behaviour — identical resolution to the canonical URL.
;; ---------------------------------------------------------------------------

(def ^:private alias-cases
  [{:name "CodeSystem $lookup by bare OID"
    :op :lookup :input {:system "2.16.840.1.113883.18.108" :code "AM"}
    :expect [["display" (display? "American Express")]]}

   {:name "CodeSystem $lookup by urn:oid:"
    :op :lookup :input {:system "urn:oid:2.16.840.1.113883.18.108" :code "AM"}
    :expect [["display" (display? "American Express")]]}

   {:name "CodeSystem find-codesystem by bare OID"
    :op :find-cs :input {:id "2.16.840.1.113883.18.108"}
    :expect [["resolves" resolved?]]}

   {:name "CodeSystem find-codesystem by urn:oid:"
    :op :find-cs :input {:id "urn:oid:2.16.840.1.113883.18.108"}
    :expect [["resolves" resolved?]]}

   {:name "ValueSet $expand by urn:oid:"
    :op :expand :input {:url "urn:oid:1.2.91.13925.17760.26050446"}
    :expect [["includes member" (expand-includes? "69610")]]}

   {:name "ValueSet find-valueset by urn:oid:"
    :op :find-vs :input {:id "urn:oid:1.2.91.13925.17760.26050446"}
    :expect [["resolves" resolved?]]}])

;; ConceptMap alias resolution is exercised by the synthetic
;; `composite-alias-federation-test`, not here: R4-core's only ConceptMap
;; `.identifier` (`urn:uuid:53cd62ee-…`) is shared by both ConceptMap/101
;; and /103 — a published-data collision HL7 only fixed in R5 — so no
;; loaded ConceptMap has an unambiguous identifier to address.

(deftest ^:live canonical-controls
  (doseq [c canonical-cases] (check c)))

(deftest ^:live system-alias-resolution
  (doseq [c alias-cases] (check c)))
