(ns com.eldrix.hades.core-test
  "Smoke tests for the public Clojure API. Verifies that `open` builds
  a service from raw provider impls (URLs discovered via *-metadata)
  and that the operation convenience fns route correctly."
  (:require [clojure.test :refer [deftest is testing]]
            [com.eldrix.hades.core :as hades]
            [com.eldrix.hades.impl.load :as load-fhir]))

(def ^:private cs-fhir
  {"resourceType" "CodeSystem"
   "url"          "http://example.org/cs/colours"
   "version"      "1.0"
   "status"       "active"
   "content"      "complete"
   "concept"      [{"code" "red"   "display" "Red"}
                   {"code" "green" "display" "Green"}
                   {"code" "blue"  "display" "Blue"}]})

(def ^:private vs-fhir
  {"resourceType" "ValueSet"
   "url"          "http://example.org/vs/colours"
   "version"      "1.0"
   "status"       "active"
   "compose"      {"include" [{"system" "http://example.org/cs/colours"}]}})

(defn- mk-svc []
  (hades/open [(load-fhir/from-fhir cs-fhir)
               (load-fhir/from-fhir vs-fhir)]))

(deftest open-and-lookup
  (let [svc (mk-svc)]
    (testing "lookup returns details for a known code"
      (let [r (hades/lookup svc {:system "http://example.org/cs/colours" :code "red"})]
        (is (= "Red" (:display r)))
        (is (= "http://example.org/cs/colours" (:system r)))))
    (testing "lookup returns nil for an unknown code"
      (is (nil? (hades/lookup svc {:system "http://example.org/cs/colours" :code "nope"}))))
    (testing "lookup returns nil for an unknown system"
      (is (nil? (hades/lookup svc {:system "http://no/such" :code "x"}))))))

(deftest validate-code-cs
  (let [svc (mk-svc)]
    (testing "CS validate-code (no :url)"
      (let [r (hades/validate-code svc {:system "http://example.org/cs/colours"
                                        :code   "red"})]
        (is (true? (:result r)))
        (is (= "Red" (:display r)))))
    (testing "CS validate-code reports unknown system"
      (let [r (hades/validate-code svc {:system "http://no/such" :code "x"})]
        (is (false? (:result r)))
        (is (= "http://no/such" (:x-unknown-system r)))))))

;; VS validate-code via compose-driven ValueSet is intentionally not
;; exercised here yet — the leaf provider impls (in_memory, compose)
;; are migrated to take `svc` instead of `ctx` in a follow-up commit;
;; until then the spec-instrumented compose/expand-compose rejects a
;; TerminologyService passed where it expects a registry ctx map.

(deftest expand-and-with-overlays
  (let [svc (mk-svc)]
    (testing "expand returns concepts from the implicit ValueSet"
      (let [r (hades/expand svc {:url "http://example.org/cs/colours"})]
        (is (= 3 (count (:concepts r))))
        (is (every? #{"red" "green" "blue"} (map :code (:concepts r))))))
    (testing "with-overlays layers an extra provider for one call"
      (let [extra-fhir (assoc cs-fhir
                              "url"     "http://example.org/cs/extras"
                              "concept" [{"code" "x" "display" "Extra"}])
            extra (load-fhir/from-fhir extra-fhir)
            svc'  (hades/with-overlays svc [extra])]
        (is (some? (hades/lookup svc' {:system "http://example.org/cs/extras" :code "x"})))
        (testing "base service unaffected"
          (is (nil? (hades/lookup svc {:system "http://example.org/cs/extras" :code "x"}))))))))

(deftest metadata-empty-on-open
  (let [svc (mk-svc)]
    (is (= {} (hades/metadata svc)))))
