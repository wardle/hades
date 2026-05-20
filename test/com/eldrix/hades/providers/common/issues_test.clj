(ns com.eldrix.hades.providers.common.issues-test
  (:require [clojure.test :refer [deftest is testing]]
            [com.eldrix.hades.providers.common.issues :as issues]))

(def ^:private given "XCode1")
(def ^:private system "http://example.org/cs")
(def ^:private code "code1")
(def ^:private primary "Code1")
(def ^:private prefix
  (str "Wrong Display Name '" given "' for " system "#" code ". "))

(defn- mismatch [designations display-language cs-language]
  (issues/format-display-mismatch given system code primary
                                  designations display-language cs-language))

(deftest format-display-mismatch-trailing-language-test
  (testing "single choice with a known designation language: the trailing `(for the language(s) '<lang>')` reflects the *requested* language, not the designation's"
    ;; Regression: the inner destructure used to shadow the outer `lang`,
    ;; so when no displayLanguage was requested the trailing parenthetical
    ;; echoed the designation language ('en') instead of '--'.
    (is (= (str prefix "Valid display is '" primary "' (en) (for the language(s) '--')")
           (mismatch [{:value primary :language :en}] nil nil)))
    (is (= (str prefix "Valid display is '" primary "' (fr) (for the language(s) 'fr')")
           (mismatch [{:value primary :language :fr}] "fr" nil))))

  (testing "single choice without a known language falls through to the plain else branch"
    (is (= (str prefix "Valid display is '" primary "' (for the language(s) '--')")
           (mismatch nil nil nil))))

  (testing "multiple distinct choices: each rendered with its own language; trailing parenthetical is the request"
    (is (= (str prefix "Valid display is one of 3 choices: "
                "'Code1' (en) or 'Code 1' (en) or 'Code1.0' (de) "
                "(for the language(s) '--')")
           (mismatch [{:value "Code 1"   :language :en}
                      {:value "Code1.0"  :language :de}]
                     nil "en"))))

  (testing "displayLanguage requested but no choices in that language: 'no valid display' message names the request"
    (is (= (str prefix "There are no valid display names found for language(s) 'fr'. "
                "Default display is '" primary "'")
           (mismatch [{:value "Code 1"  :language :en}
                      {:value "Code1.0" :language :de}]
                     "fr" nil)))))
