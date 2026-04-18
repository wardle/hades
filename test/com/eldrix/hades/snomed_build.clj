(ns com.eldrix.hades.snomed-build
  "Install a SNOMED CT Hermes database from the MLDS International Edition."
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [com.eldrix.hermes.core :as hermes]
            [com.eldrix.hermes.download :as hermes-download]))

(def ^:private int-package-id "ihtsdo.mlds/167")

(defn list-packages!
  "List available MLDS SNOMED distributions.

  Usage:
    clj -X:install/list :username '\"user\"' :password '\"pw-file\"'"
  [{:keys [username password]}]
  (when-not (and username password)
    (println "Usage: clj -X:install/list :username '\"user\"' :password '\"pw-file\"'")
    (System/exit 1))
  (let [packages (hermes-download/mlds-packages {:username username :password password})]
    (println (format "\nAvailable MLDS packages (%d):\n" (count packages)))
    (println (format "  %-40s %-20s %s" "Package ID" "Member" "Name"))
    (println (str "  " (apply str (repeat 80 "-"))))
    (doseq [pkg (sort-by #(get-in % [:member :key]) packages)]
      (let [pkg-id (hermes-download/make-mlds-id pkg)
            member-key (get-in pkg [:member :key])
            name' (:name pkg)]
        (println (format "  %-40s %-20s %s" pkg-id member-key (or name' "")))))
    (println)))

(defn install!
  "Download SNOMED International Edition via MLDS and build a Hermes database.

  Required:
    :username      — MLDS username
    :password      — path to file containing MLDS password
    :db            — path for the Hermes database to create

  Optional:
    :package-id    — MLDS package ID (default: ihtsdo.mlds/167)
    :release-date  — ISO date e.g. \"2025-02-01\" (default: latest)

  Usage:
    clj -X:install :username '\"user\"' :password '\"pw\"' :db '\".hades/snomed.db\"'
    clj -X:install :username '\"user\"' :password '\"pw\"' :db '\".hades/snomed.db\"' :release-date '\"2025-02-01\"'"
  [{:keys [username password db package-id release-date]}]
  (when-not (and username password db)
    (println "Usage: clj -X:install :username '\"user\"' :password '\"pw\"' :db '\"path/to/snomed.db\"'")
    (System/exit 1))
  (let [db-file (io/file db)]
    (when (.exists db-file)
      (println (format "Database already exists: %s" db))
      (println "Delete it first if you want to rebuild.")
      (System/exit 1))
    (let [pkg-id (or package-id int-package-id)]
      (log/info "Downloading SNOMED International Edition" {:package pkg-id :release-date release-date})
      (let [rf2-dir (hermes-download/download-from-mlds
                      pkg-id (cond-> {:username username :password password}
                               release-date (assoc :release-date release-date)))]
        (hermes/import-snomed (str db-file) [(str rf2-dir)])
        (hermes/index (str db-file))
        (hermes/compact (str db-file))
        (println (format "\nInstalled: %s" (str db-file)))))))
