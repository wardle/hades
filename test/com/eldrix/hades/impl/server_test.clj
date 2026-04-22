(ns com.eldrix.hades.impl.server-test
  "Integration tests for the Hades FHIR server endpoints.
  Starts a real HTTP server with Hermes and makes actual HTTP requests.
  Tests are skipped when SNOMED index is not available."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.eldrix.hades.impl.registry :as registry]
            [com.eldrix.hades.impl.server :as server]
            [com.eldrix.hades.impl.snomed :as snomed]
            [com.eldrix.hermes.core :as hermes])
  (:import (java.net ServerSocket URI)
           (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)))

(def ^:private snomed-path
  (or (System/getenv "SNOMED_DB")
      (let [f (clojure.java.io/file "/Users/mark/Dev/hermes/snomed.db")]
        (when (.exists f) (str f)))))

(def ^:private test-state (atom nil))

(defn- free-port []
  (with-open [sock (ServerSocket. 0)]
    (.getLocalPort sock)))

(defn server-fixture [f]
  (if snomed-path
    (let [port (free-port)
          svc (hermes/open snomed-path)
          snomed-svc (snomed/->HermesService svc)
          srv (server/make-server {:port port})]
      (registry/register-codesystem "http://snomed.info/sct" snomed-svc)
      (registry/register-codesystem "http://snomed.info/sct|*" snomed-svc)
      (registry/register-codesystem "sct" snomed-svc)
      (registry/register-valueset "http://snomed.info/sct" snomed-svc)
      (registry/register-valueset "http://snomed.info/sct|*" snomed-svc)
      (registry/register-valueset "sct" snomed-svc)
      (registry/register-concept-map-provider snomed-svc)
      (server/start! srv)
      (reset! test-state {:port port :svc svc :server srv})
      (try (f)
           (finally
             (server/stop! srv)
             (.close svc)
             (reset! test-state nil))))
    (f)))

(use-fixtures :once server-fixture)

(defn- base-url []
  (str "http://localhost:" (:port @test-state) "/fhir"))

(defn- http-get [path]
  (let [client (HttpClient/newHttpClient)
        request (-> (HttpRequest/newBuilder (URI. (str (base-url) path)))
                    (.header "Accept" "application/fhir+json")
                    (.GET)
                    (.build))
        response (.send client request (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response)
     :body (json/read-str (.body response))}))

(defn- http-get-raw [path]
  (let [client (HttpClient/newHttpClient)
        request (-> (HttpRequest/newBuilder (URI. (str (base-url) path)))
                    (.GET)
                    (.build))
        response (.send client request (HttpResponse$BodyHandlers/ofString))]
    {:status       (.statusCode response)
     :content-type (.orElse (.firstValue (.headers response) "content-type") "")
     :raw          (.body response)}))

(defn- http-post-json [path body-map]
  (let [client (HttpClient/newHttpClient)
        payload (json/write-str body-map)
        request (-> (HttpRequest/newBuilder (URI. (str (base-url) path)))
                    (.header "Accept" "application/fhir+json")
                    (.header "Content-Type" "application/fhir+json")
                    (.POST (java.net.http.HttpRequest$BodyPublishers/ofString payload))
                    (.build))
        response (.send client request (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response)
     :body (json/read-str (.body response))}))

(defn- get-param [body name]
  (some (fn [p] (when (= name (get p "name")) p))
        (get body "parameter")))

;; ---------------------------------------------------------------------------
;; Tests — skipped when no SNOMED index
;; ---------------------------------------------------------------------------

(deftest lookup-valid-code
  (when @test-state
    (testing "lookup returns display for valid SNOMED code"
      (let [{:keys [status body]} (http-get "/CodeSystem/$lookup?system=http://snomed.info/sct&code=73211009")]
        (is (= 200 status))
        (is (= "SNOMED CT" (get (get-param body "name") "valueString")))
        (is (some? (get (get-param body "display") "valueString")))))))

(deftest lookup-unknown-code
  (when @test-state
    (testing "lookup returns 404 for unknown code in known system"
      (let [{:keys [status body]} (http-get "/CodeSystem/$lookup?system=http://snomed.info/sct&code=999999999")]
        (is (= 404 status))
        (is (= "OperationOutcome" (get body "resourceType")))
        (let [text (get-in body ["issue" 0 "details" "text"])]
          (is (re-find #"(?i)unknown code" text))
          (is (not (re-find #"(?i)unknown code system" text))))))))

(deftest lookup-unknown-system
  (when @test-state
    (testing "lookup returns 404 for unknown code system"
      (let [{:keys [status body]} (http-get "/CodeSystem/$lookup?system=http://example.com/fake&code=123")]
        (is (= 404 status))
        (is (= "OperationOutcome" (get body "resourceType")))))))

(deftest validate-code-with-system-param
  (when @test-state
    (testing "CS $validate-code works with system= query param"
      (let [{:keys [status body]} (http-get "/CodeSystem/$validate-code?system=http://snomed.info/sct&code=73211009")]
        (is (= 200 status))
        (is (true? (get (get-param body "result") "valueBoolean")))
        (is (= "http://snomed.info/sct" (get (get-param body "system") "valueUri")))))))

(deftest validate-code-unknown-code
  (when @test-state
    (testing "CS $validate-code returns result=false for unknown code"
      (let [{:keys [status body]} (http-get "/CodeSystem/$validate-code?system=http://snomed.info/sct&code=999999999")]
        (is (= 200 status))
        (is (false? (get (get-param body "result") "valueBoolean")))
        (is (some? (get (get-param body "issues") "resource")))))))

(deftest validate-code-unknown-system
  (when @test-state
    (testing "CS $validate-code returns result=false for unknown system"
      (let [{:keys [status body]} (http-get "/CodeSystem/$validate-code?system=http://example.com/fake&code=123")]
        (is (= 200 status))
        (is (false? (get (get-param body "result") "valueBoolean")))))))

(deftest expand-inline-valueset-descendent-of
  (when @test-state
    (testing "POST $expand with inline valueSet (descendent-of filter) expands without a url"
      (let [payload {:resourceType "Parameters"
                     :parameter    [{:name "valueSet"
                                     :resource
                                     {:resourceType "ValueSet"
                                      :compose
                                      {:include
                                       [{:system "http://snomed.info/sct"
                                         :filter [{:property "concept"
                                                   :op       "descendent-of"
                                                   :value    "64572001"}]}]}}}
                                    {:name "count" :valueInteger 10}]}
            {:keys [status body]} (http-post-json "/ValueSet/$expand" payload)]
        (is (= 200 status))
        (is (= "ValueSet" (get body "resourceType")))
        (let [expansion (get body "expansion")]
          (is (<= (count (get expansion "contains" [])) 10)))))))

(deftest expand-inline-valueset-multifilter
  (when @test-state
    (testing "POST $expand with inline valueSet and multiple filters"
      (let [payload {:resourceType "Parameters"
                     :parameter    [{:name "valueSet"
                                     :resource
                                     {:resourceType "ValueSet"
                                      :compose
                                      {:include
                                       [{:system "http://snomed.info/sct"
                                         :filter [{:property "concept"
                                                   :op       "is-a"
                                                   :value    "195967001"}
                                                  {:property "363698007"
                                                   :op       "="
                                                   :value    "89187006"}]}]}}}
                                    {:name "count" :valueInteger 10}]}
            {:keys [status body]} (http-post-json "/ValueSet/$expand" payload)]
        (is (= 200 status))
        (is (= "ValueSet" (get body "resourceType")))))))

(deftest translate-snomed-replaced-by
  (when @test-state
    (testing "$translate resolves SNOMED REPLACED BY for a retired code"
      (let [{:keys [status body]} (http-get (str "/ConceptMap/$translate"
                                                 "?url=http%3A%2F%2Fsnomed.info%2Fsct%3Ffhir_cm%3D900000000000526001"
                                                 "&system=http%3A%2F%2Fsnomed.info%2Fsct&code=225983005"
                                                 "&target=http%3A%2F%2Fsnomed.info%2Fsct"))]
        (is (= 200 status))
        (is (= "Parameters" (get body "resourceType")))
        (is (true? (get (get-param body "result") "valueBoolean")))
        (let [match (get-param body "match")
              parts (->> (get match "part") (map (juxt #(get % "name") identity)) (into {}))]
          (is (= "equivalent" (get-in parts ["equivalence" "valueCode"])))
          (is (= "441207001" (get-in parts ["concept" "valueCoding" "code"])))
          (is (= "http://snomed.info/sct" (get-in parts ["concept" "valueCoding" "system"]))))))))

(deftest translate-snomed-active-code-unmapped
  (when @test-state
    (testing "$translate returns result=false for a code with no historical association"
      (let [{:keys [status body]} (http-get (str "/ConceptMap/$translate"
                                                 "?url=http%3A%2F%2Fsnomed.info%2Fsct%3Ffhir_cm%3D900000000000526001"
                                                 "&system=http%3A%2F%2Fsnomed.info%2Fsct&code=73211009"
                                                 "&target=http%3A%2F%2Fsnomed.info%2Fsct"))]
        (is (= 200 status))
        (is (false? (get (get-param body "result") "valueBoolean")))
        (is (nil? (get-param body "match")))))))

(defn- conceptmap-registered-for? [source target]
  (some (fn [d] (and (= source (:system d)) (= target (:target d))))
        (registry/conceptmap-descriptions)))

(deftest translate-pair-lookup-dispatches-to-provider
  (when @test-state
    (let [sct "http://snomed.info/sct"
          icd-o "http://hl7.org/fhir/sid/icd-o"]
      (when (conceptmap-registered-for? sct icd-o)
        (testing "(system,target) pair resolves to the right provider without a url"
          (let [{:keys [status]} (http-get (str "/ConceptMap/$translate"
                                                "?system=" sct "&code=000&target=" icd-o))]
            (is (= 200 status)
                "Registry should route pair requests to the SNOMED provider"))))
      (when (conceptmap-registered-for? icd-o sct)
        (testing "Reverse pair (external → SCT) resolves to the same provider"
          (let [{:keys [status]} (http-get (str "/ConceptMap/$translate"
                                                "?system=" icd-o "&code=FAKE&target=" sct))]
            (is (= 200 status))))))))

(deftest cm-describe-lists-installed-refsets
  (when @test-state
    (testing "cm-describe emits SCT→SCT historical maps + pairs for any installed external maps"
      (let [descs (registry/conceptmap-descriptions)
            snomed "http://snomed.info/sct"]
        (is (seq (filter #(= [snomed snomed] [(:system %) (:target %)]) descs))
            "at least one SCT→SCT historical association should be registered")
        (is (every? :url descs)
            "every description carries a canonical url for direct $translate dispatch")))))

(deftest translate-unknown-conceptmap-url
  (when @test-state
    (testing "$translate with unknown url returns 404 OperationOutcome"
      (let [{:keys [status body]} (http-get "/ConceptMap/$translate?url=http://example.com/fake&system=http://snomed.info/sct&code=225983005")]
        (is (= 404 status))
        (is (= "OperationOutcome" (get body "resourceType")))
        (is (= "not-found" (get-in body ["issue" 0 "code"])))))))

(deftest unrouted-path-returns-fhir-404
  (when @test-state
    (testing "GET on an unrouted path returns a FHIR OperationOutcome 404"
      (let [{:keys [status content-type raw]} (http-get-raw "/ValueSet?url=http://example.com")]
        (is (= 404 status))
        (is (re-find #"application/fhir\+json" content-type))
        (let [body (json/read-str raw)]
          (is (= "OperationOutcome" (get body "resourceType")))
          (is (= "not-found" (get-in body ["issue" 0 "code"]))))))))
