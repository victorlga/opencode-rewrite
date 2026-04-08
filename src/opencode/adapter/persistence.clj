(ns opencode.adapter.persistence
  "Atom-backed session store for opencode (MVP).
   Stores sessions in an atom keyed by :session/id. Designed for easy migration
   to DataScript or SQLite later — session shapes are plain serializable EDN.
   Wired as Integrant component :opencode/session-store."
  (:require
   [integrant.core :as ig]))

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
  "Persists a session in the store, keyed by :session/id.
   Returns the session."
  [store session]
  (swap! (:sessions-atom store) assoc (:session/id session) session)
  session)

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
  "Removes a session from the store by ID. Returns nil."
  [store session-id]
  (swap! (:sessions-atom store) dissoc session-id)
  nil)

;; ---------------------------------------------------------------------------
;; Integrant component
;; ---------------------------------------------------------------------------

(defmethod ig/init-key :opencode/session-store [_ _opts]
  (create-store))

(comment
  ;; REPL exploration
  (require '[opencode.domain.session :as session])
  (def store (create-store))
  (def s (session/create-session "claude-sonnet-4-20250514"))
  (save-session! store s)
  (load-session store (:session/id s))
  (list-sessions store)
  (delete-session! store (:session/id s))
  (list-sessions store)
  ,)
