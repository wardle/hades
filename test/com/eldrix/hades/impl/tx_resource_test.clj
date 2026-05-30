(ns com.eldrix.hades.impl.tx-resource-test
  "HTTP-level tests for the `tx-resource` overlay interceptor (`derive-svc`
  in `impl/http.clj`). Boots a real Pedestal server fronting a tiny
  in-memory base service (no SNOMED — the test is fast and offline),
  then POSTs Parameters carrying transient CodeSystem / ValueSet
  resources and asserts that the overlay participates in dispatch for
  the request only.

  Mirrors the conformance suite's `tx-resource` use (e.g. the ISO-3166
  supplement test in `bugs/validate-3166-c-request.json`), but uses
  synthetic systems so the assertions stay tight and the fixture is
  free of pinned-data prerequisites."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.eldrix.hades.fixtures :as fixtures]
            [com.eldrix.hades.composite :as composite]
            [com.eldrix.hades.impl.load :as load-fhir]
            [com.eldrix.hades.providers.common.fhir-loader :as loaders]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(def ^:private base-cs
  {"resourceType"  "CodeSystem"
   "url"           "http://example.com/base/cs" "version" "1.0" "status" "active"
   "content"       "complete" "caseSensitive" true
   "concept"       [{"code" "X" "display" "Existing"}]})

(def ^:private overlay-cs
  {"resourceType"  "CodeSystem"
   "url"           "http://example.com/tx/cs" "version" "1.0" "status" "active"
   "content"       "complete" "caseSensitive" true
   "concept"       [{"code" "T" "display" "Transient"}]})

(def ^:dynamic *server* nil)

(defn- in-memory-svc [resource-maps]
  (let [data (mapcat #(loaders/resource->fhir-data % "<test>") resource-maps)]
    (composite/from-providers (:providers (load-fhir/build-from-fhir-data data)))))

(defn server-fixture [f]
  (let [svc (in-memory-svc [base-cs])
        srv (fixtures/start-server svc)]
    (binding [*server* srv]
      (try (f)
           (finally
             (fixtures/stop-server srv)
             (.close ^java.io.Closeable svc))))))

(use-fixtures :once server-fixture)

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- get-param [body name]
  (some (fn [p] (when (= name (get p "name")) p))
        (get body "parameter")))

(defn- request! [req]
  (fixtures/request! (:url *server*) req))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest tx-resource-overlay-cs-lookup-test
  (testing "POST $lookup against an overlay-only CodeSystem succeeds via the request-scoped overlay"
    (let [resp (request!
                 {:method :post
                  :path   "/CodeSystem/$lookup"
                  :body   {:resourceType "Parameters"
                           :parameter
                           [{:name "system"      :valueUri "http://example.com/tx/cs"}
                            {:name "code"        :valueCode "T"}
                            {:name "tx-resource" :resource overlay-cs}]}})]
      (is (= 200 (:status resp)))
      (is (= "Transient"
             (get (get-param (:body resp) "display") "valueString"))))))

(deftest tx-resource-does-not-leak-after-request-test
  (testing "Without the tx-resource the same lookup fails — overlays are scoped to the request"
    (let [resp (request!
                 {:method :get
                  :path   "/CodeSystem/$lookup?system=http://example.com/tx/cs&code=T"})]
      (is (>= (:status resp) 400)
          "post-request, the overlay system is unknown to the base service"))))

(deftest tx-resource-base-system-still-reachable-test
  (testing "The base CodeSystem is still served alongside the overlay"
    (let [resp (request!
                 {:method :post
                  :path   "/CodeSystem/$lookup"
                  :body   {:resourceType "Parameters"
                           :parameter
                           [{:name "system"      :valueUri "http://example.com/base/cs"}
                            {:name "code"        :valueCode "X"}
                            {:name "tx-resource" :resource overlay-cs}]}})]
      (is (= 200 (:status resp)))
      (is (= "Existing"
             (get (get-param (:body resp) "display") "valueString"))))))
