(ns opencode.adapter.llm.model-registry
  "Model metadata registry for LLM providers.
   Stores model info as plain data (not fetched from network for MVP).
   Provides lookup functions for model metadata by ID or provider."
  (:require
   [cognitect.anomalies :as anom]
   [malli.core :as m]
   [malli.error :as me]))

;; ---------------------------------------------------------------------------
;; Malli schemas
;; ---------------------------------------------------------------------------

(def ModelInfo
  "Schema for model metadata."
  [:map
   [:model/id :string]
   [:model/provider :keyword]
   [:model/context-window :int]
   [:model/max-output :int]
   [:model/cost [:map
                 [:input :double]
                 [:output :double]]]
   [:model/capabilities {:optional true}
    [:map
     [:tool-calls :boolean]
     [:streaming :boolean]]]])

;; ---------------------------------------------------------------------------
;; Model registry — static data for MVP
;; ---------------------------------------------------------------------------

(def ^:private models
  "Static model metadata registry keyed by model ID string.
   Translated from the TypeScript models.dev data."
  {"claude-sonnet-4-20250514"
   {:model/id             "claude-sonnet-4-20250514"
    :model/provider       :anthropic
    :model/context-window 200000
    :model/max-output     16384
    :model/cost           {:input 3.0 :output 15.0}
    :model/capabilities   {:tool-calls true :streaming true}}

   "claude-haiku-3-5-20241022"
   {:model/id             "claude-haiku-3-5-20241022"
    :model/provider       :anthropic
    :model/context-window 200000
    :model/max-output     8192
    :model/cost           {:input 0.80 :output 4.0}
    :model/capabilities   {:tool-calls true :streaming true}}})

;; ---------------------------------------------------------------------------
;; Lookup functions
;; ---------------------------------------------------------------------------

(defn get-model
  "Returns the model metadata map for the given model-id string.
   Returns a :cognitect.anomalies/not-found anomaly if the model is unknown."
  [model-id]
  (or (get models model-id)
      {::anom/category ::anom/not-found
       ::anom/message  (str "Unknown model: " model-id)}))

(defn models-for-provider
  "Returns a vector of model metadata maps for the given provider keyword."
  [provider-kw]
  (into []
        (filter #(= provider-kw (:model/provider %)))
        (vals models)))

(defn all-models
  "Returns a vector of all model metadata maps in the registry."
  []
  (vec (vals models)))

(defn valid-model?
  "Returns true if the given model map validates against the ModelInfo schema."
  [model]
  (m/validate ModelInfo model))

(defn validate-model
  "Validates a model map against the ModelInfo schema.
   Returns the model if valid, or an anomaly map if not."
  [model]
  (if (m/validate ModelInfo model)
    model
    {::anom/category ::anom/incorrect
     ::anom/message  "Invalid model metadata"
     :errors         (me/humanize (m/explain ModelInfo model))}))

(comment
  ;; REPL exploration
  (get-model "claude-sonnet-4-20250514")
  (get-model "nonexistent-model")
  (models-for-provider :anthropic)
  (all-models)
  (every? valid-model? (all-models))
  ,)
