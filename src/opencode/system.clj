(ns opencode.system
  "Integrant system map for opencode.
   Defines the system configuration and provides start!/stop! helpers.
   Components are wired via Integrant refs for dependency injection."
  (:require
   [integrant.core :as ig]
   ;; Adapter namespaces — required so their Integrant init-key/halt-key! and
   ;; defmethod registrations execute at load time.
   [opencode.adapter.llm.anthropic]
   [opencode.adapter.persistence]
   [opencode.adapter.tool.bash]
   [opencode.adapter.tool.file-edit]
   [opencode.adapter.tool.file-read]
   [opencode.adapter.tool.file-write]
   [opencode.adapter.tool.glob]
   [opencode.adapter.tool.grep]
   [opencode.adapter.ui.repl]
   ;; Domain + logic namespaces with Integrant methods
   [opencode.config]
   [opencode.logic.event-bus]))

;; ---------------------------------------------------------------------------
;; System configuration
;; ---------------------------------------------------------------------------

(def default-system-config
  "Default Integrant system configuration map.
   Each key corresponds to a component initialized via ig/init-key."
  {:opencode/config        {}
   :opencode/event-bus      {}
   :opencode/llm-provider   {:config (ig/ref :opencode/config)}
   :opencode/ui             {}
   :opencode/session-store  {}})

(defn system-config
  "Returns the system configuration, optionally merging overrides."
  ([]
   default-system-config)
  ([overrides]
   (merge default-system-config overrides)))

;; ---------------------------------------------------------------------------
;; Lifecycle helpers
;; ---------------------------------------------------------------------------

(defn start!
  "Initializes and starts the Integrant system.
   Returns the running system map."
  ([]
   (start! (system-config)))
  ([config]
   (ig/init config)))

(defn stop!
  "Halts the given running Integrant system."
  [system]
  (ig/halt! system))

(comment
  ;; REPL exploration
  (def sys (start!))
  sys
  (stop! sys)
  ,)
