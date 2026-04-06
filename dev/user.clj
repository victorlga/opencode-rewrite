(ns user
  "REPL development helpers.
   Loaded automatically when starting with the :dev alias."
  (:require
   [integrant.core :as ig]
   [opencode.system :as system]
   [opencode.config :as config]))

(defonce ^:private system-instance (atom nil))

(defn start!
  "Starts the Integrant system and stores it in the atom."
  []
  (reset! system-instance (system/start!))
  :started)

(defn stop!
  "Stops the running Integrant system."
  []
  (when-let [sys @system-instance]
    (system/stop! sys)
    (reset! system-instance nil))
  :stopped)

(defn restart!
  "Stops and restarts the system."
  []
  (stop!)
  (start!))

(defn system
  "Returns the current running system map."
  []
  @system-instance)

(comment
  ;; REPL workflow
  (start!)
  (system)
  (stop!)
  (restart!)

  ;; Inspect config directly
  (config/load-config)
  ,)
