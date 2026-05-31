(ns com.eldrix.hades.composite-alias-federation-test
  "OID/URN aliases resolve url-scoped across separate providers via
  `NamingService`: an identifier names a resource's canonical URL, not a
  version, so it resolves to the URL and then to a version by the normal
  rules — across any number of providers/containers.

  Synthetic, fixture-free: the gap these guard is *federation* across
  providers, which a single loaded container can't exhibit. Also home to
  the ConceptMap-alias case, for which no loaded package has an
  unambiguous identifier (R4-core's only ConceptMap `.identifier` is
  shared by ConceptMap/101 and /103)."
  (:require [clojure.test :refer [deftest is testing]]
            [com.eldrix.hades.composite :as composite]
            [com.eldrix.hades.core :as hades]
            [com.eldrix.hades.impl.load :as load]
            [com.eldrix.hades.providers.common.fhir-loader :as fhir-loader]))

(defn- providers-for
  "Build the in-memory providers for one FHIR resource map (string-keyed)
  through the real loader → index path, so `.identifier` flows into a
  `MemoryNamingSystem` exactly as in production."
  [resource]
  (-> (fhir-loader/resource->fhir-data resource :tx-resource)
      vec
      load/build-from-fhir-data
      :providers))

(defn- code-system [url version oid display]
  {"resourceType" "CodeSystem" "url" url "version" version
   "status" "active" "content" "complete"
   "identifier" [{"system" "urn:ietf:rfc:3986" "value" (str "urn:oid:" oid)}]
   "concept" [{"code" "x" "display" display}]})

(def ^:private cs-url "http://ex/cs")

(deftest cross-provider-oid-is-url-scoped
  (testing "an OID on one version resolves to the latest version, not its
            declaring version, when versions are split across providers"
    (let [svc (composite/from-providers
                (concat (providers-for (code-system cs-url "1.0.0" "2.16.1" "X-v1"))
                        (providers-for (code-system cs-url "4.0.0" "2.16.4" "X-v4"))))
          display-of #(:display (hades/lookup svc {:system % :code "x"}))]
      (is (= "X-v4" (display-of cs-url)) "control: canonical URL → latest")
      (is (= "X-v4" (display-of "urn:oid:2.16.1")) "v1's OID must resolve url-scoped to latest")
      (is (= "X-v4" (display-of "urn:oid:2.16.4"))))))

(deftest cross-provider-oid-matches-canonical-dispatch
  (testing "two providers serving the same (url, version) with different
            OIDs: every alias resolves to the same provider as the URL"
    (let [svc (composite/from-providers
                (concat (providers-for (code-system cs-url "1.0.0" "2.16.1" "from-A"))
                        (providers-for (code-system cs-url "1.0.0" "2.16.2" "from-B"))))
          display-of #(:display (hades/lookup svc {:system % :code "x"}))
          canonical (display-of cs-url)]
      (is (= canonical (display-of "urn:oid:2.16.1")))
      (is (= canonical (display-of "urn:oid:2.16.2"))))))

(defn- concept-map [url oid]
  {"resourceType" "ConceptMap" "url" url "status" "active"
   "identifier" {"system" "urn:ietf:rfc:3986" "value" (str "urn:oid:" oid)}
   "group" [{"source" "http://ex/src" "target" "http://ex/tgt"
             "element" [{"code" "home"
                         "target" [{"code" "H" "equivalence" "equivalent"}]}]}]})

(deftest conceptmap-alias-translate
  (testing "$translate by a ConceptMap's OID alias resolves to the map"
    (let [svc (composite/from-providers (providers-for (concept-map "http://ex/cm" "2.16.9")))
          translate (fn [url] (hades/translate svc {:url url :code "home"}))
          by-canonical (translate "http://ex/cm")
          by-alias     (translate "urn:oid:2.16.9")]
      (is (true? (:result by-canonical)) "control: canonical URL translates")
      (is (= (:result by-canonical) (:result by-alias)) "alias must match canonical result")
      (is (some #(= "H" (:code %)) (:matches by-alias)) "alias translate maps home → H"))))
