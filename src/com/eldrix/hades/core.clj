(ns com.eldrix.hades.core
  (:gen-class)
  (:require [com.eldrix.hades.registry :as registry]
            [com.eldrix.hades.server :as server]
            [com.eldrix.hades.snomed :as snomed]
            [com.eldrix.hermes.core :as hermes]))


(defn -main [& args]
  (if-not (= 2 (count args))
    (do (println "Usage: clj -M:run <snomed-index-path> <port>")
        (println "   or: java -jar hades-server.jar <snomed-index-path> <port>")
        (System/exit 1))
    (let [[index-path port-str] args
          port (Integer/parseInt port-str)
          svc (hermes/open index-path)
          snomed (snomed/->HermesService svc)
          server (server/make-server svc {:port port})]
      (registry/register-codesystem "http://snomed.info/sct" snomed)
      (registry/register-codesystem "sct" snomed)
      (registry/register-valueset "http://snomed.info/sct" snomed)
      (registry/register-valueset "sct" snomed)
      (.start server))))

(comment

  (def svc (hermes/open "/Users/mark/Dev/hermes/snomed.db"))

  (def server (server/make-server svc {:port 8080}))
  (.start server)
  (.stop server)

  (do
    (.stop server)
    (def server (server/make-server svc {:port 8080}))
    (.start server))

  (hermes/search svc {:s "mnd"})
  (hermes/concept svc 24700007)

  (hermes/preferred-synonym svc 233753001 "en")
  (hermes/release-information svc)
  (keys (hermes/extended-concept svc 138875005))
  (get-in (hermes/extended-concept svc 24700007) [:parent-relationships com.eldrix.hermes.snomed/IsA]))

