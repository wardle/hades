(ns com.eldrix.hades.providers.common.display-test
  "Unit tests for the FHIR designation-use semantics of
  `find-display-for-language`. The picker only honours designations
  whose `use` is FHIR-standard (`designation-usage` `display`/`synonym`)
  or absent; everything else falls through so callers use the
  concept's primary `:display`. These tests are intentionally
  provider-free so a regression surfaces with a clear stack."
  (:require [clojure.test :refer [deftest is testing]]
            [com.eldrix.hades.providers.common.display :as display]))

(def ^:private std-usage "http://terminology.hl7.org/CodeSystem/designation-usage")

(defn- d
  "Build one designation. `lang` may be a string or keyword."
  [{:keys [lang value use]}]
  (cond-> {:value value}
    lang (assoc :language lang)
    use  (assoc :use use)))

(defn- ranges
  "Parse a Accept-Language style string into the priority-ordered
  `{:lang :q}` seq the picker consumes."
  [s]
  (display/parse-display-language s))

(deftest parse-display-language-strict-throws
  (testing "malformed input throws an invalid-display ex-info"
    (let [e (is (thrown? clojure.lang.ExceptionInfo
                         (display/parse-display-language* "-")))]
      (is (= {:type :processing :details-code "invalid-display"} (ex-data e)))
      (is (= "Invalid displayLanguage: '-'" (ex-message e)))))
  (testing "blank/nil yields nil without throwing"
    (is (nil? (display/parse-display-language* nil)))
    (is (nil? (display/parse-display-language* ""))))
  (testing "well-formed input parses; the wildcard is valid and yields no languages"
    (is (= [{:lang "en" :q 1.0}] (display/parse-display-language* "en")))
    (is (= () (display/parse-display-language* "*")))))

(deftest parse-display-language-lenient-swallows-malformed
  (testing "the lenient parser swallows malformed input for the Accept-Language header path"
    (is (= () (display/parse-display-language "-")))))

(deftest no-use-designation-is-eligible
  (testing "writer didn't classify ⇒ treated as a display"
    (is (= "Hallo"
           (display/find-display-for-language
             [(d {:lang "de-DE" :value "Hallo"})]
             (ranges "de-DE"))))))

(deftest fhir-standard-display-eligible
  (testing "designation-usage#display is display-eligible"
    (is (= "Hallo"
           (display/find-display-for-language
             [(d {:lang "de-DE" :value "Hallo"
                  :use {:system std-usage :code "display"}})]
             (ranges "de-DE")))))
  (testing "designation-usage#synonym is also eligible"
    (is (= "Hallo, Welt"
           (display/find-display-for-language
             [(d {:lang "de-DE" :value "Hallo, Welt"
                  :use {:system std-usage :code "synonym"}})]
             (ranges "de-DE"))))))

(deftest terminology-specific-use-not-eligible
  (testing "LOINC use is ignored — picker returns nil so caller falls back to primary"
    (is (nil? (display/find-display-for-language
                [(d {:lang "de-DE" :value "Hämatologie und Zellzählung"
                     :use {:system "http://loinc.org" :code "CLASS"}})
                 (d {:lang "de-DE" :value "Hämoglobin"
                     :use {:system "http://loinc.org" :code "COMPONENT"}})]
                (ranges "de-DE")))))
  (testing "SNOMED description-type ids are ignored"
    (is (nil? (display/find-display-for-language
                [(d {:lang "de-DE" :value "Diabetes mellitus (Erkrankung)"
                     :use {:system "http://snomed.info/sct"
                           :code "900000000000003001"}})]
                (ranges "de-DE")))))
  (testing "unrecognised designation-usage code is ignored"
    ;; e.g. a hypothetical `#deprecated` slips in
    (is (nil? (display/find-display-for-language
                [(d {:lang "de-DE" :value "Veraltet"
                     :use {:system std-usage :code "deprecated"}})]
                (ranges "de-DE"))))))

(deftest eligible-shadows-ineligible
  (testing "an eligible designation wins regardless of position in the seq"
    (let [designations [(d {:lang "de-DE" :value "AXIS"
                            :use {:system "http://loinc.org" :code "CLASS"}})
                        (d {:lang "de-DE" :value "Hämoglobin in Blut"
                            :use {:system std-usage :code "display"}})
                        (d {:lang "de-DE" :value "MORE AXIS"
                            :use {:system "http://loinc.org" :code "COMPONENT"}})]]
      (is (= "Hämoglobin in Blut"
             (display/find-display-for-language designations (ranges "de-DE")))))))

(deftest language-priority-honoured
  (testing "requested-language order wins over designation-list order"
    (let [designations [(d {:lang "en-US" :value "Hello"
                            :use {:system std-usage :code "display"}})
                        (d {:lang "de-DE" :value "Hallo"
                            :use {:system std-usage :code "display"}})]]
      (is (= "Hallo" (display/find-display-for-language designations (ranges "de-DE,en-US"))))
      (is (= "Hello" (display/find-display-for-language designations (ranges "en-US,de-DE")))))))

(deftest language-prefix-match
  (testing "requested `de` matches designation `de-DE`"
    (is (= "Hallo"
           (display/find-display-for-language
             [(d {:lang "de-DE" :value "Hallo"
                  :use {:system std-usage :code "display"}})]
             (ranges "de"))))))

(deftest language-keyword-and-string
  (testing "keyword :de-DE on a designation matches string lang 'de-DE'"
    (is (= "Hallo"
           (display/find-display-for-language
             [(d {:lang :de-DE :value "Hallo"
                  :use {:system std-usage :code "display"}})]
             (ranges "de-DE"))))))

(deftest first-eligible-language-match-wins
  (testing "among eligible language-matched designations, source order decides"
    (let [designations [(d {:lang "de-DE" :value "Erste"
                            :use {:system std-usage :code "display"}})
                        (d {:lang "de-DE" :value "Zweite"
                            :use {:system std-usage :code "synonym"}})]]
      (is (= "Erste"
             (display/find-display-for-language designations (ranges "de-DE")))))))
