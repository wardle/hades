(ns com.eldrix.hades.impl.http-test
  "Unit tests for the Pedestal HTTP layer's error mapping. No live
  fixtures: exceptions are constructed in-process and fed to the
  `catch-all-error` interceptor's `:error` fn directly."
  (:require [clojure.test :refer [deftest is testing]]
            [com.eldrix.hades.impl.http :as http])
  (:import (java.sql SQLTransientConnectionException)))

(defn- run-error
  "Invoke the catch-all-error interceptor's :error fn on `ex` and
  return the `:response` map it sets on the context."
  [ex]
  (-> ((:error http/catch-all-error) {} ex) :response))

;; Pedestal wraps thrown exceptions in an ex-info whose cause is the
;; original throwable and whose ex-data merges in the original's ex-data
;; (see io.pedestal.interceptor.chain/throwable->ex-info).
(defn- pedestal-wrapped [^Throwable cause]
  (ex-info (str (type cause) " in Interceptor :test - " (.getMessage cause))
           (merge {:exception cause}
                  (when (instance? clojure.lang.ExceptionInfo cause)
                    (ex-data cause)))
           cause))

(deftest catch-all-error-saturation
  (testing "SQLTransientConnectionException → 503 + Retry-After + throttled OperationOutcome"
    (let [ex      (SQLTransientConnectionException.
                    "fhir-tx-12345 - Connection is not available, request timed out after 2000ms")
          {:keys [status headers body]} (run-error (pedestal-wrapped ex))]
      (is (= 503 status))
      (is (= "1" (get headers "Retry-After")))
      (is (= "OperationOutcome" (get body "resourceType")))
      (is (= "error"     (get-in body ["issue" 0 "severity"])))
      (is (= "throttled" (get-in body ["issue" 0 "code"])))
      (is (string? (get-in body ["issue" 0 "details" "text"])))))

  (testing "unwrapped SQLTransientConnectionException is also recognised"
    (let [ex (SQLTransientConnectionException. "fhir-tx-1 - Connection is not available")
          {:keys [status]} (run-error ex)]
      (is (= 503 status)))))

(deftest catch-all-error-non-saturation
  (testing "IllegalArgumentException takes the existing 500 path"
    (let [ex (pedestal-wrapped (IllegalArgumentException. "bad input"))
          {:keys [status headers body]} (run-error ex)]
      (is (= 500 status))
      (is (nil? (get headers "Retry-After")))
      (is (= "OperationOutcome" (get body "resourceType")))
      (is (not= "throttled" (get-in body ["issue" 0 "code"])))))

  (testing "ex-info with :type takes the typed-status path"
    (let [ex (pedestal-wrapped (ex-info "missing" {:type :not-found}))
          {:keys [status body]} (run-error ex)]
      (is (= 404 status))
      (is (= "not-found" (get-in body ["issue" 0 "code"]))))))
