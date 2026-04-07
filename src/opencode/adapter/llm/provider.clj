(ns opencode.adapter.llm.provider
  "LLMProvider protocol definition and completion options schema.
   Defines the interface that all LLM provider adapters must implement.
   No implementation here — just the protocol and its Malli schemas."
  (:require
   [malli.core :as m]))

;; ---------------------------------------------------------------------------
;; Completion options schema
;; ---------------------------------------------------------------------------

(def CompletionOpts
  "Schema for completion request options passed to complete/stream."
  [:map
   [:system {:optional true} :string]
   [:tools {:optional true} [:vector :map]]
   [:max-tokens {:optional true} :int]
   [:temperature {:optional true} :double]])

;; ---------------------------------------------------------------------------
;; LLMProvider protocol
;; ---------------------------------------------------------------------------

(defprotocol LLMProvider
  "Protocol for LLM provider adapters.
   Each provider (Anthropic, OpenAI, etc.) implements this protocol
   to provide completions, streaming, and model listing."
  (complete [this messages opts]
    "Synchronous completion. Takes a vector of message maps and a CompletionOpts map.
     Returns an assistant message map on success, or a cognitect anomaly map on failure.")
  (stream [this messages opts]
    "Streaming completion. Takes a vector of message maps and a CompletionOpts map.
     Returns a core.async channel of stream events. The channel closes when the
     stream ends. Error events are delivered as anomaly maps on the channel.")
  (list-models [this]
    "Returns a vector of model metadata maps supported by this provider."))

(comment
  ;; REPL exploration
  (m/validate CompletionOpts {})
  (m/validate CompletionOpts {:system "You are helpful"})
  (m/validate CompletionOpts {:max-tokens 4096 :temperature 0.7})
  (m/validate CompletionOpts {:tools [{:name "bash" :schema {}}]})
  ,)
