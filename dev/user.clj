(ns user
  "REPL development helpers.
   Loaded automatically when starting with the :dev alias."
  (:require
   [opencode.config :as config]
   [opencode.system :as system]))

(defonce ^:private system-instance (atom nil))

(defn start!
  "Starts the Integrant system and stores it in the atom.
   Halts any previously running system first to avoid resource leaks."
  []
  (locking system-instance
    (when-let [old @system-instance]
      (system/stop! old))
    (reset! system-instance (system/start!)))
  :started)

(defn stop!
  "Stops the running Integrant system."
  []
  (locking system-instance
    (when-let [sys @system-instance]
      (system/stop! sys)
      (reset! system-instance nil)))
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
