(ns com.eldrix.hades.providers.snomed.snomed-uk-live-test
  "Live multi-module SNOMED routing test against a real UK Monolith DB."
  (:require [clojure.test :refer [deftest is testing]]
            [com.eldrix.hades.core :as hades]
            [com.eldrix.hades.fixtures :as fixtures]
            [com.eldrix.hades.composite :as composite]
            [com.eldrix.hades.providers.snomed.provider :as snomed]
            [com.eldrix.hermes.core :as hermes])
  (:import (java.time.format DateTimeFormatter)))

(defn- module-version-uri [{:keys [moduleId effectiveTime]}]
  (str snomed/snomed-system-uri "/" moduleId "/version/"
       (.format DateTimeFormatter/BASIC_ISO_DATE effectiveTime)))

(defn- get-param [body name]
  (some (fn [p] (when (= name (get p "name")) p))
        (get body "parameter")))

(deftest ^:live uk-monolith-routes-bare-and-module-versions
  (testing "real UK Monolith Hermes DB has multiple modules and one provider route"
    (let [db-path fixtures/snomed-uk-db-path]
      (with-open [hermes-svc (hermes/open db-path)]
        (let [releases (vec (hermes/release-information hermes-svc))]
          (is (>= (count releases) 2)
              (str "UK Monolith fixture must be multi-module; release-information="
                   (pr-str releases)))
          (let [svc-or-ex (try (hades/open [db-path])
                               (catch clojure.lang.ExceptionInfo e e))]
            (is (not (instance? clojure.lang.ExceptionInfo svc-or-ex))
                (str "opening UK Monolith must not fail with "
                     (some-> svc-or-ex ex-data :reason)))
            (when-not (instance? clojure.lang.ExceptionInfo svc-or-ex)
              (let [svc svc-or-ex]
                (try
                  (let [bare (composite/find-codesystem svc snomed/snomed-system-uri)]
                    (is (some? bare)
                        "bare SNOMED URL must resolve to the UK Monolith provider")
                    (doseq [ri releases
                            :let [versioned (str snomed/snomed-system-uri "|"
                                                 (module-version-uri ri))]]
                      (is (identical? bare (composite/find-codesystem svc versioned))
                          (str versioned " must resolve to the same provider as the bare URL"))))
                  (let [server (fixtures/start-server svc)]
                    (try
                      (let [{:keys [status body]} (fixtures/request!
                                                    (:url server)
                                                    {:path (str "/CodeSystem/$lookup"
                                                                "?system=http://snomed.info/sct"
                                                                "&code=73211009")})]
                        (is (= 200 status))
                        (is (some? (get (get-param body "display") "valueString"))))
                      (finally
                        (fixtures/stop-server server))))
                  (finally
                    (hades/close svc)))))))))))

(deftest ^:live separate-snomed-providers-require-default-but-keep-exact-versions
  (testing "International and UK Monolith as separate providers"
    (with-open [intl-hermes (hermes/open fixtures/snomed-db-path)
                uk-hermes (hermes/open fixtures/snomed-uk-db-path)]
      (let [intl-provider (snomed/->HermesService intl-hermes)
            uk-provider (snomed/->HermesService uk-hermes)
            intl-version (module-version-uri (first (hermes/release-information intl-hermes)))
            uk-version (module-version-uri (first (hermes/release-information uk-hermes)))
            ex (try (composite/from-providers [intl-provider uk-provider]) nil
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (= :ambiguous-default (:reason (ex-data ex)))
            "bare SNOMED URL is ambiguous across two independent providers")
        (let [svc (composite/from-providers [intl-provider uk-provider]
                                            {:defaults {snomed/snomed-system-uri intl-version}})]
          (try
            (is (identical? intl-provider
                            (composite/find-codesystem svc snomed/snomed-system-uri))
                "bare SNOMED URL follows the explicit default")
            (is (identical? intl-provider
                            (composite/find-codesystem
                              svc (str snomed/snomed-system-uri "|" intl-version)))
                "exact International version routes to the International provider")
            (is (identical? uk-provider
                            (composite/find-codesystem
                              svc (str snomed/snomed-system-uri "|" uk-version)))
                "exact UK composite version routes to the UK provider")
            (is (= intl-version
                   (:version (hades/lookup svc {:system snomed/snomed-system-uri
                                                :version intl-version
                                                :code "73211009"}))))
            (is (= uk-version
                   (:version (hades/lookup svc {:system snomed/snomed-system-uri
                                                :version uk-version
                                                :code "73211009"}))))
            (is (identical? intl-provider
                            (composite/find-codesystem
                              svc (str snomed/snomed-system-uri "|"
                                       snomed/snomed-system-uri
                                       "/999999999999/version/20990131")))
                "wildcard-only versions follow the explicit default provider")
            (finally
              (hades/close svc))))))))
