(ns opencode.domain.message
  "Message schemas and constructor functions for the opencode domain.
   Translates the TypeScript MessageV2 discriminated unions into Malli schemas
   with namespaced keywords. Constructors validate output via Malli."
  (:require
   [malli.core :as m]
   [malli.error :as me]))

;; ---------------------------------------------------------------------------
;; Malli schemas — translated from TypeScript message-v2.ts
;; ---------------------------------------------------------------------------

(def Role
  "Enum of valid message roles."
  [:enum :system :user :assistant :tool])

(def ToolCall
  "Schema for a single tool call requested by the assistant."
  [:map
   [:tool-call/id :string]
   [:tool-call/name :string]
   [:tool-call/arguments [:map-of :keyword :any]]])

(def UserMessage
  "Schema for a user-authored message."
  [:map
   [:message/role [:= :user]]
   [:message/content :string]])

(def AssistantMessage
  "Schema for an assistant response message."
  [:map
   [:message/role [:= :assistant]]
   [:message/content [:maybe :string]]
   [:message/tool-calls {:optional true} [:vector ToolCall]]
   [:message/finish-reason [:enum :stop :tool-calls :length :error]]])

(def ToolResultMessage
  "Schema for the result returned after executing a tool."
  [:map
   [:message/role [:= :tool]]
   [:message/tool-call-id :string]
   [:message/content :string]])

(def SystemMessage
  "Schema for a system prompt message."
  [:map
   [:message/role [:= :system]]
   [:message/content :string]])

(def Message
  "Union schema covering all message types."
  [:or SystemMessage UserMessage AssistantMessage ToolResultMessage])

;; ---------------------------------------------------------------------------
;; Validation helper
;; ---------------------------------------------------------------------------

(defn- validate!
  "Validates data against schema. Returns data if valid, throws ex-info if not."
  [schema data]
  (if (m/validate schema data)
    data
    (throw (ex-info "Invalid message data"
                    {:errors (me/humanize (m/explain schema data))
                     :data   data}))))

;; ---------------------------------------------------------------------------
;; Constructor functions
;; ---------------------------------------------------------------------------

(defn system-message
  "Creates a validated system message."
  [content]
  (validate! SystemMessage
             {:message/role    :system
              :message/content content}))

(defn user-message
  "Creates a validated user message."
  [content]
  (validate! UserMessage
             {:message/role    :user
              :message/content content}))

(defn assistant-message
  "Creates a validated assistant message.
   tool-calls may be nil or a vector of ToolCall maps."
  [content tool-calls finish-reason]
  (validate! AssistantMessage
             (cond-> {:message/role          :assistant
                      :message/content       content
                      :message/finish-reason finish-reason}
               (seq tool-calls) (assoc :message/tool-calls (vec tool-calls)))))

(defn tool-result
  "Creates a validated tool result message."
  [call-id content]
  (validate! ToolResultMessage
             {:message/role        :tool
              :message/tool-call-id call-id
              :message/content     content}))

(comment
  ;; REPL exploration
  (user-message "Hello, world!")
  (system-message "You are a helpful assistant.")
  (assistant-message "Sure, I can help." nil :stop)
  (assistant-message nil
                     [{:tool-call/id "tc_1"
                       :tool-call/name "bash"
                       :tool-call/arguments {:command "ls"}}]
                     :tool-calls)
  (tool-result "tc_1" "file1.txt\nfile2.txt")
  ,)
