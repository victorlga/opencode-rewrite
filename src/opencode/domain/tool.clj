(ns opencode.domain.tool
  "Tool framework: schema, registry, dispatch multimethod, and API conversion.
   Tool definitions are plain maps with Malli schemas for parameters.
   Tool execution is dispatched via multimethod on tool name string.
   Tool parameter schemas are converted to JSON Schema for the Anthropic API
   using malli.json-schema/transform — single source of truth."
  (:require
   [cognitect.anomalies :as anom]
   [malli.core :as m]
   [malli.error :as me]
   [malli.json-schema :as mjs]))

;; ---------------------------------------------------------------------------
;; Malli schemas
;; ---------------------------------------------------------------------------

(def ToolDef
  "Schema for a tool definition map."
  [:map
   [:tool/name :string]
   [:tool/description :string]
   [:tool/parameters :any]
   [:tool/dangerous? :boolean]])

(def ToolContext
  "Schema for the context map passed to execute-tool!."
  [:map
   [:ctx/session-id :uuid]
   [:ctx/project-dir :string]
   [:ctx/dangerous-mode? :boolean]])

;; ---------------------------------------------------------------------------
;; Registry (atom holding tool-name -> tool-def)
;; ---------------------------------------------------------------------------

(defonce ^:private registry
  (atom {}))

(defn register-tool!
  "Validates and registers a tool definition in the global registry.
   Returns the tool def on success, or an anomaly map if validation fails."
  [tool-def]
  (if (m/validate ToolDef tool-def)
    (do
      (swap! registry assoc (:tool/name tool-def) tool-def)
      tool-def)
    {::anom/category ::anom/incorrect
     ::anom/message  "Invalid tool definition"
     :errors         (me/humanize (m/explain ToolDef tool-def))}))

(defn get-tool
  "Looks up a tool by name in the registry. Returns the tool def or nil."
  [tool-name]
  (get @registry tool-name))

(defn all-tools
  "Returns a vector of all registered tool definitions."
  []
  (vec (vals @registry)))

(defn reset-registry!
  "Clears the tool registry. Intended for test isolation."
  []
  (reset! registry {}))

;; ---------------------------------------------------------------------------
;; API format conversion
;; ---------------------------------------------------------------------------

(defn tools-for-api
  "Converts tool definitions to the Anthropic API tool format.
   Uses malli.json-schema/transform to generate JSON Schema from Malli schemas.
   Returns a vector of maps with :name, :description, and :input_schema."
  [tools]
  (mapv (fn [tool-def]
          {:name         (:tool/name tool-def)
           :description  (:tool/description tool-def)
           :input_schema (mjs/transform (:tool/parameters tool-def))})
        tools))

;; ---------------------------------------------------------------------------
;; Execution multimethod
;; ---------------------------------------------------------------------------

(defmulti execute-tool!
  "Executes a tool by name with the given params and context.
   Dispatches on tool-name (a string).
   Returns a map with :output on success, or an anomaly map on failure."
  (fn [tool-name _params _context] tool-name))

(defmethod execute-tool! :default
  [tool-name _params _context]
  {::anom/category ::anom/unsupported
   ::anom/message  (str "Unknown tool: " tool-name)})

(comment
  ;; REPL exploration
  (register-tool! {:tool/name        "example"
                   :tool/description "An example tool"
                   :tool/parameters  [:map [:input :string]]
                   :tool/dangerous?  false})
  (get-tool "example")
  (all-tools)
  (tools-for-api (all-tools))
  (execute-tool! "nonexistent" {} {})
  (reset-registry!)
  ,)
