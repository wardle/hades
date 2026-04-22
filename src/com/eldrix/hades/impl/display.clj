(ns com.eldrix.hades.impl.display
  "Shared display + language helpers for FHIR terminology operations.
  Used by CodeSystem and ValueSet implementations for language-aware
  display validation and selection."
  (:require [clojure.string :as str])
  (:import (java.util Locale$LanguageRange)))

(defn parse-display-language
  "Parse a displayLanguage (RFC 4647 / Accept-Language) string into a seq of
  `{:lang :q}` maps sorted by quality descending. The wildcard `*` is dropped.
  Returns nil for nil/blank input and for inputs the JDK rejects as malformed."
  [s]
  (when (and s (not (str/blank? s)))
    (let [ranges (try (Locale$LanguageRange/parse ^String s) (catch Exception _ nil))]
      (->> ranges
           (keep (fn [^Locale$LanguageRange r]
                   (let [range (.getRange r)]
                     (when (not= range "*")
                       {:lang range :q (.getWeight r)}))))
           (sort-by :q #(compare %2 %1))))))

(defn language-matches?
  "Check if a designation language matches a requested language using prefix matching."
  [designation-lang requested-lang]
  (when (and designation-lang requested-lang)
    (let [d (str/lower-case (if (keyword? designation-lang) (name designation-lang) (str designation-lang)))
          r (str/lower-case requested-lang)]
      (or (= d r) (str/starts-with? d (str r "-"))))))

(defn display-matches?
  "Check whether a display string matches the concept, checking against the
  primary display and all designations. Case-insensitive.
  When display-langs is provided, only checks designations in those languages."
  ([concept display] (display-matches? concept display nil))
  ([concept display display-langs]
   (let [display-lower (str/lower-case display)
         primary (:display concept)]
     (or (and primary (= display-lower (str/lower-case primary)))
         (some (fn [d]
                 (when-let [v (:value d)]
                   (and (= display-lower (str/lower-case v))
                        (or (nil? display-langs)
                            (some #(language-matches? (:language d) (:lang %))
                                  display-langs)))))
               (:designations concept))))))

(defn find-display-for-language
  "Find the best display for a set of designations given language preferences."
  [designations display-languages]
  (some (fn [{:keys [lang]}]
          (some (fn [d]
                  (when (language-matches? (:language d) lang)
                    (:value d)))
                designations))
        display-languages))
