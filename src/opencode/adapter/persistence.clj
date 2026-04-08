(ns opencode.adapter.persistence
  "Atom-backed session store for opencode (MVP).
   Stores sessions in an atom keyed by :session/id. Designed for easy migration
   to DataScript or SQLite later — session shapes are plain serializable EDN.
   Wired as Integrant component :opencode/session-store."
  (:require
   [cognitect.anomalies :as anom]
   [integrant.core :as ig]
   [malli.core :as m]
   [malli.error :as me]
   [opencode.domain.session :as session]))

;; ---------------------------------------------------------------------------
;; SessionStore record
;; ---------------------------------------------------------------------------

(defrecord SessionStore [sessions-atom])

(defn create-store
  "Creates a new in-memory session store backed by an atom."
  []
  (->SessionStore (atom {})))

;; ---------------------------------------------------------------------------
;; Store operations
;; ---------------------------------------------------------------------------

(defn save-session!
  "Validates session against the Session schema, then persists it in the store.
   Returns the session on success, or an anomaly map if validation fails."
  [store session]
  (if (m/validate session/Session session)
    (do
      (swap! (:sessions-atom store) assoc (:session/id session) session)
      session)
    {::anom/category ::anom/incorrect
     ::anom/message  "Invalid session data"
     :errors         (me/humanize (m/explain session/Session session))}))

(defn load-session
  "Loads a session by ID. Returns the session map, or nil if not found."
  [store session-id]
  (get @(:sessions-atom store) session-id))

(defn list-sessions
  "Returns all sessions sorted by :session/created-at descending (newest first)."
  [store]
  (->> (vals @(:sessions-atom store))
       (sort-by :session/created-at #(compare %2 %1))
       vec))

(defn delete-session!
  "Removes a session from the store by ID. Returns the session-id that was deleted."
  [store session-id]
  (swap! (:sessions-atom store) dissoc session-id)
  session-id)

;; ---------------------------------------------------------------------------
;; Integrant component
;; ---------------------------------------------------------------------------

(defmethod ig/init-key :opencode/session-store [_ _opts]
  (create-store))

(comment
  ;; REPL exploration
  (def store (create-store))
  (def s (session/create-session "claude-sonnet-4-20250514"))
  (save-session! store s)
  (load-session store (:session/id s))
  (list-sessions store)
  (delete-session! store (:session/id s))
  (list-sessions store)
  ,)
