(ns opencode.config
  "Configuration loading and validation for opencode.
   Loads EDN config via Aero, validates with Malli, and provides
   an Integrant component for the system lifecycle."
  (:require
   [aero.core :as aero]
   [clojure.java.io :as io]
   [integrant.core :as ig]
   [malli.core :as m]
   [malli.error :as me]))

;; ---------------------------------------------------------------------------
;; Malli schemas — translated from the TypeScript Zod schemas in config.ts
;; ---------------------------------------------------------------------------

(def LLMConfig
  "Schema for LLM provider configuration."
  [:map
   [:provider :string]
   [:api-key {:optional true} [:maybe :string]]
   [:model :string]])

(def ToolsConfig
  "Schema for tool permission configuration."
  [:map
   [:allowed [:set :keyword]]])

(def UIConfig
  "Schema for UI configuration."
  [:map
   [:type [:enum :repl :tui]]])

(def ProjectConfig
  "Schema for project directory configuration."
  [:map
   [:directory :string]])

(def AppConfig
  "Schema for the top-level opencode configuration map."
  [:map
   [:llm LLMConfig]
   [:tools ToolsConfig]
   [:ui UIConfig]
   [:project ProjectConfig]])

(def Config
  "Schema for the full configuration file shape."
  [:map
   [:opencode AppConfig]])

;; ---------------------------------------------------------------------------
;; Config loading
;; ---------------------------------------------------------------------------

(defn load-config
  "Loads configuration from the given resource path using Aero.
   Returns the parsed EDN map with environment variable interpolation."
  ([]
   (load-config (io/resource "config.edn")))
  ([source]
   (aero/read-config source)))

(defn validate-config
  "Validates the given config map against the Config schema.
   Returns the config if valid, throws ex-info with explanation on failure."
  [config]
  (if (m/validate Config config)
    config
    (throw (ex-info "Invalid configuration"
                    {:errors (me/humanize (m/explain Config config))
                     :config config}))))

(defn load-and-validate!
  "Loads config from the default resource and validates it.
   Returns the validated config map or throws on invalid config."
  ([]
   (load-and-validate! (io/resource "config.edn")))
  ([source]
   (-> (load-config source)
       validate-config)))

;; ---------------------------------------------------------------------------
;; Integrant component
;; ---------------------------------------------------------------------------

(defmethod ig/init-key :opencode/config [_ opts]
  (let [source (or (:source opts) (io/resource "config.edn"))
        config (load-and-validate! source)]
    (:opencode config)))

(comment
  ;; REPL exploration
  (load-config)
  (validate-config (load-config))
  (load-and-validate!)

  ;; Test with bad config
  (m/explain Config {:opencode {:llm {:provider 123}}})
  (me/humanize (m/explain Config {:opencode {:llm {:provider 123}}}))
  ,)
