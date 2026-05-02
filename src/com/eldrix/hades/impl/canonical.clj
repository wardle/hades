(ns com.eldrix.hades.impl.canonical
  "Pure utilities for FHIR canonical URLs and version handling.

  - parse `url|version` canonicals
  - version pattern matching (`x` wildcards)
  - numeric version compare
  - query-string stripping"
  (:require [clojure.string :as str]
            [lambdaisland.uri :as uri]))

(def ^:const wildcard-version
  "Sentinel `version` value declaring that a provider serves *every*
  version of its CodeSystem URL. Reserved for backends that handle
  arbitrary editions natively (e.g. SNOMED via Hermes resolves any
  `http://snomed.info/sct/<edition>/version/<date>` URI). Providers
  that only know specific versions must enumerate them; never use `*`
  as a fallback for \"version unknown\"."
  "*")

(defn parse-versioned-uri
  "Split a FHIR canonical reference into [url version].
  'http://example.com/cs|1.0' => ['http://example.com/cs' '1.0']
  'http://example.com/cs'     => ['http://example.com/cs' nil]"
  [s]
  (when s
    (let [idx (.lastIndexOf ^String s "|")]
      (if (pos? idx)
        [(.substring ^String s 0 idx) (.substring ^String s (inc idx))]
        [s nil]))))

(defn versioned-uri
  "Construct a versioned canonical: url|version. Returns url if version is nil."
  [url version]
  (if version (str url "|" version) url))

(defn parse-version-param
  "Parse a seq of 'url|version' canonical strings into {url version} map."
  [params]
  (into {} (keep (fn [s] (let [[url ver] (parse-versioned-uri s)]
                            (when ver [url ver]))))
        params))

(defn version-matches?
  "Check if concrete version matches a pattern. 'x' segments are wildcards.
   '1.0.x' matches '1.0.0', '1.x.x' matches '1.2.0'.
   A shorter pattern is padded with 'x': '1.x' matches '1.0.0'."
  [pattern concrete]
  (when (and pattern concrete)
    (if (= pattern concrete)
      true
      (let [p-parts (str/split pattern #"\.")
            c-parts (str/split concrete #"\.")
            p-padded (if (< (count p-parts) (count c-parts))
                       (into (vec p-parts) (repeat (- (count c-parts) (count p-parts)) "x"))
                       p-parts)]
        (and (= (count p-padded) (count c-parts))
             (every? true? (map (fn [p c] (or (= "x" p) (= p c)))
                                p-padded c-parts)))))))

(defn parse-numeric-segments
  "Split a dot-delimited version string into a vector of integers.
  Non-numeric segments compare as 0."
  [s]
  (mapv #(or (parse-long %) 0) (str/split s #"\.")))

(defn pad-to [v n]
  (into v (repeat (- n (count v)) 0)))

(defn semver-compare
  "Compare two version strings numerically by segment.
  '1.10.0' > '1.9.0' (unlike lexicographic compare).

  Nil is treated as 'no declared version' and orders below any concrete
  version, so a concrete version always wins 'latest semver' against
  a versionless entry. Two nils are equal."
  [a b]
  (cond
    (and (nil? a) (nil? b)) 0
    (nil? a) -1
    (nil? b) 1
    :else
    (let [pa (parse-numeric-segments a)
          pb (parse-numeric-segments b)
          n  (max (count pa) (count pb))]
      (compare (pad-to pa n) (pad-to pb n)))))

(defn uri-without-query
  "Return the URI with the query component stripped."
  [s]
  (str (assoc (uri/uri s) :query nil)))
