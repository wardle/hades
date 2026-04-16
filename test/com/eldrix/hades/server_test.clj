(ns com.eldrix.hades.server-test
  "Integration tests for the Hades FHIR server endpoints.
  Starts a real HTTP server with Hermes and makes actual HTTP requests.
  Tests are skipped when SNOMED index is not available."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.eldrix.hades.registry :as registry]
            [com.eldrix.hades.server :as server]
            [com.eldrix.hades.snomed :as snomed]
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
          srv (server/make-server svc {:port port})]
      (registry/register-codesystem "http://snomed.info/sct" snomed-svc)
      (registry/register-codesystem "http://snomed.info/sct|*" snomed-svc)
      (registry/register-codesystem "sct" snomed-svc)
      (registry/register-valueset "http://snomed.info/sct" snomed-svc)
      (registry/register-valueset "http://snomed.info/sct|*" snomed-svc)
      (registry/register-valueset "sct" snomed-svc)
      (.start srv)
      (reset! test-state {:port port :svc svc :server srv})
      (try (f)
           (finally
             (.stop srv)
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
        ;; should say "Unknown code" not "Unknown code system"
        (let [diag (get-in body ["issue" 0 "diagnostics"])]
          (is (re-find #"(?i)unknown code" diag))
          (is (not (re-find #"(?i)unknown code system" diag))))))))

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
