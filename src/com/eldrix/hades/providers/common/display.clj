(ns com.eldrix.hades.providers.common.display
  "Shared display + language helpers for FHIR terminology operations.
  Used by CodeSystem and ValueSet implementations for language-aware
  display validation and selection."
  (:require [clojure.string :as str])
  (:import (java.util Locale$LanguageRange)))

(set! *warn-on-reflection* true)

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

(def ^:private fhir-designation-usage
  "FHIR's canonical CodeSystem of designation `use` semantics —
  `http://terminology.hl7.org/CodeSystem/designation-usage`."
  "http://terminology.hl7.org/CodeSystem/designation-usage")

(defn- display-eligible?
  "A designation participates in display-language selection when its
  `use` carries FHIR-standard display semantics — either no `use` at
  all (the writer did not classify) or a `use` drawn from
  `http://terminology.hl7.org/CodeSystem/designation-usage`
  (`display` or `synonym`). Terminology-specific use codes (LOINC
  axes, SNOMED description-type ids, …) are ignored here; the
  ingest layer that emitted them is responsible for surfacing a
  FHIR-standard `use` if it wants the designation to be picked."
  [{:keys [use]}]
  (or (nil? use)
      (and (= (:system use) fhir-designation-usage)
           (contains? #{"display" "synonym"} (:code use)))))

(defn find-display-for-language
  "Pick a display for `designations` given a seq of priority-ordered
  `display-languages` (`{:lang :q}` maps from `parse-display-language`).
  Only display-eligible designations participate — see `display-eligible?`.
  Walks the requested languages outer-first and returns the first
  language-matched eligible designation's `:value`; returns nil when
  no eligible designation matches any requested language, so callers
  fall back to the concept's primary `:display`."
  [designations display-languages]
  (some (fn [{:keys [lang]}]
          (some (fn [d]
                  (when (and (display-eligible? d)
                             (language-matches? (:language d) lang))
                    (:value d)))
                designations))
        display-languages))
