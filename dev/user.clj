(ns user
  "REPL development entry point."
  (:require [clojure.java.io :as io]
            [aero.core :as aero]
            [integrant.core :as ig]))

(defonce system nil)

(defn start!
  "Start the system for REPL development."
  []
  (alter-var-root #'system
    (fn [sys]
      (when sys (ig/halt! sys))
      (-> "config.edn"
          (io/resource)
          (aero/read-config)
          (ig/prep)
          (ig/init)))))

(defn stop!
  "Stop the running system."
  []
  (alter-var-root #'system
    (fn [sys]
      (when sys (ig/halt! sys))
      nil)))

(defn restart!
  "Restart the system."
  []
  (stop!)
  (start!))

(comment
  ;; REPL workflow:
  (start!)
  (stop!)
  (restart!)
  system
  )
