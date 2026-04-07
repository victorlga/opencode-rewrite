(ns opencode.domain.session
  "Functions for session data manipulation.
   Sessions are plain maps with namespaced keywords.
   Note: create-session uses UUID/randomUUID and Instant/now for convenience;
   all other functions are pure transforms."
  (:require
   [cognitect.anomalies :as anom]
   [malli.core :as m]
   [malli.error :as me]
   [opencode.domain.message :as message])
  (:import
   (java.time Instant)
   (java.util UUID)))

;; ---------------------------------------------------------------------------
;; Malli schemas
;; ---------------------------------------------------------------------------

(def Session
  "Schema for a session map."
  [:map
   [:session/id :uuid]
   [:session/title :string]
   [:session/messages [:vector message/Message]]
   [:session/created-at inst?]
   [:session/model :string]
   [:session/tokens [:map
                     [:input :int]
                     [:output :int]]]])

;; ---------------------------------------------------------------------------
;; Constructor
;; ---------------------------------------------------------------------------

(defn create-session
  "Creates a new session with a random UUID, empty messages, zero tokens,
   title \"New Session\", and created-at set to now.
   Returns the session map, or an anomaly map if validation fails."
  [model]
  (let [session {:session/id         (UUID/randomUUID)
                 :session/title      "New Session"
                 :session/messages   []
                 :session/created-at (Instant/now)
                 :session/model      model
                 :session/tokens     {:input 0 :output 0}}]
    (if (m/validate Session session)
      session
      {::anom/category ::anom/incorrect
       ::anom/message  "Invalid session data"
       :errors         (me/humanize (m/explain Session session))})))

;; ---------------------------------------------------------------------------
;; Pure accessors and transforms
;; ---------------------------------------------------------------------------

(defn append-message
  "Returns session with message conj'd to :session/messages.
   Returns an anomaly map if msg does not validate against Message schema."
  [session msg]
  (if (m/validate message/Message msg)
    (update session :session/messages conj msg)
    {::anom/category ::anom/incorrect
     ::anom/message  "Invalid message data"
     :errors         (me/humanize (m/explain message/Message msg))}))

(defn get-messages
  "Returns the :session/messages vector."
  [session]
  (:session/messages session))

(defn session-token-count
  "Returns the :session/tokens map."
  [session]
  (:session/tokens session))

(defn update-tokens
  "Adds input-delta and output-delta to the current token counts."
  [session input-delta output-delta]
  (-> session
      (update-in [:session/tokens :input] + input-delta)
      (update-in [:session/tokens :output] + output-delta)))

(comment
  ;; REPL exploration
  (def s (create-session "claude-sonnet-4-20250514"))
  s
  (-> s
      (append-message (message/user-message "Hello"))
      (append-message (message/assistant-message "Hi there!" nil :stop))
      get-messages)
  (-> s
      (update-tokens 100 50)
      session-token-count)
  ,)
