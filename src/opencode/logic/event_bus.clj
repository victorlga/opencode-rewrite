(ns opencode.logic.event-bus
  "core.async pub/sub event bus for opencode.
   Provides a simple publish/subscribe mechanism using core.async channels.
   Events are maps with :event/type, :event/data, and :event/timestamp."
  (:require
   [clojure.core.async :as async]
   [integrant.core :as ig])
  (:import
   (java.time Instant)))

;; ---------------------------------------------------------------------------
;; Event types (keywords — no need to pre-register for MVP)
;; ---------------------------------------------------------------------------
;;
;; :llm/stream-delta, :llm/stream-done, :llm/error,
;; :tool/executing, :tool/completed, :tool/error,
;; :session/updated, :permission/requested, :permission/replied

;; ---------------------------------------------------------------------------
;; Bus lifecycle
;; ---------------------------------------------------------------------------

(defn create-bus
  "Creates a new event bus backed by a core.async sliding-buffer channel.
   Returns a map with :bus/channel and :bus/publication."
  []
  (let [ch  (async/chan (async/sliding-buffer 1024))
        pub (async/pub ch :event/type)]
    {:bus/channel     ch
     :bus/publication pub}))

(defn close-bus!
  "Closes the main bus channel, which causes all subscriber channels to close."
  [bus]
  (async/close! (:bus/channel bus)))

;; ---------------------------------------------------------------------------
;; Publish / Subscribe
;; ---------------------------------------------------------------------------

(defn publish!
  "Puts an event map on the bus channel (non-blocking).
   Returns true if the put succeeded, false if the channel was closed."
  [bus event-type data]
  (async/put! (:bus/channel bus)
              {:event/type      event-type
               :event/data      data
               :event/timestamp (Instant/now)}))

(defn subscribe!
  "Creates a subscriber channel that receives events of the given type.
   buffer-size controls the sliding-buffer size for the subscriber channel.
   Returns the subscriber channel."
  [bus event-type buffer-size]
  (let [ch (async/chan (async/sliding-buffer buffer-size))]
    (async/sub (:bus/publication bus) event-type ch)
    ch))

(defn unsubscribe!
  "Removes a subscriber channel from the publication for the given event type."
  [bus event-type ch]
  (async/unsub (:bus/publication bus) event-type ch))

;; ---------------------------------------------------------------------------
;; Integrant component
;; ---------------------------------------------------------------------------

(defmethod ig/init-key :opencode/event-bus [_ _opts]
  (create-bus))

(defmethod ig/halt-key! :opencode/event-bus [_ bus]
  (close-bus! bus))

(comment
  ;; REPL exploration
  (def bus (create-bus))
  (def sub-ch (subscribe! bus :session/updated 16))

  (publish! bus :session/updated {:session/id "abc"})

  (async/<!! sub-ch) ;; => event map

  (unsubscribe! bus :session/updated sub-ch)
  (close-bus! bus)
  ,)
