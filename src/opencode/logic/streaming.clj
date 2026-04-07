(ns opencode.logic.streaming
  "SSE (Server-Sent Events) parsing utilities for the Anthropic Messages API.
   Parses the Anthropic streaming format: lines of 'event: <type>' followed by
   'data: <json>' then a blank line. Provides both blocking and channel-based APIs.

   IMPORTANT: read-sse-events is blocking — call it from async/thread, never go blocks."
  (:require
   [clojure.core.async :as async]
   [clojure.string :as str]
   [cognitect.anomalies :as anom]
   [jsonista.core :as json]))

;; ---------------------------------------------------------------------------
;; SSE event parsing
;; ---------------------------------------------------------------------------

(def ^:private object-mapper
  "Shared jsonista ObjectMapper configured for keyword keys."
  (json/object-mapper {:decode-key-fn keyword}))

(defn parse-sse-event
  "Parses a single SSE event given the event type string and JSON data string.
   Returns a map {:sse/type <keyword> :sse/data <parsed-json>}, or nil for ping events."
  [event-type data-str]
  (when-not (= event-type "ping")
    {:sse/type (keyword (str/replace event-type "_" "-"))
     :sse/data (json/read-value data-str object-mapper)}))

;; ---------------------------------------------------------------------------
;; Blocking SSE reader
;; ---------------------------------------------------------------------------

(defn read-sse-events
  "Reads SSE events from a java.io.BufferedReader. Returns a lazy seq of parsed
   SSE event maps. Reads line by line, accumulates event-type + data lines,
   emits an event on blank line. Stops when reader returns nil (stream closed).

   IMPORTANT: This function blocks on I/O — call from async/thread, never go blocks."
  [^java.io.BufferedReader reader]
  (letfn [(read-events [event-type data-lines]
            (lazy-seq
             (let [line (.readLine reader)]
               (cond
                 ;; Stream closed
                 (nil? line)
                 (when (and event-type (seq data-lines))
                   (when-let [evt (parse-sse-event event-type (str/join "\n" data-lines))]
                     (list evt)))

                 ;; Blank line = emit event
                 (str/blank? line)
                 (if (and event-type (seq data-lines))
                   (let [evt (parse-sse-event event-type (str/join "\n" data-lines))]
                     (if evt
                       (cons evt (read-events nil []))
                       (read-events nil [])))
                   (read-events event-type data-lines))

                 ;; event: line
                 (str/starts-with? line "event: ")
                 (read-events (subs line 7) data-lines)

                 ;; data: line
                 (str/starts-with? line "data: ")
                 (read-events event-type (conj data-lines (subs line 6)))

                 ;; Ignore other lines (comments, etc.)
                 :else
                 (read-events event-type data-lines)))))]
    (read-events nil [])))

;; ---------------------------------------------------------------------------
;; Channel-based SSE reader
;; ---------------------------------------------------------------------------

(defn sse-events->channel!
  "Wraps read-sse-events in an async/thread, puts each parsed event onto a
   core.async channel, and closes the channel when the reader ends.
   Returns the channel immediately.

   Uses loop/recur to check >!! return — stops reading when the channel is
   closed (e.g., consumer cancellation), avoiding resource leaks on network
   streams. Catches exceptions and delivers an anomaly map on the channel
   before closing, so consumers can distinguish errors from normal completion.

   buf-size controls the bounded buffer size for the output channel (default 64).
   Uses a bounded buffer (not sliding) to apply backpressure rather than
   silently dropping LLM response events per AGENTS.md buffer conventions."
  ([reader]
   (sse-events->channel! reader 64))
  ([reader buf-size]
   (let [ch (async/chan (async/buffer buf-size))]
     (async/thread
       (try
         (loop [events (seq (read-sse-events reader))]
           (when events
             (when (async/>!! ch (first events))
               (recur (next events)))))
         (catch Exception e
           (let [anomaly {::anom/category ::anom/fault
                          ::anom/message  (ex-message e)}]
             (async/>!! ch anomaly)))
         (finally
           (async/close! ch))))
     ch)))

(comment
  ;; REPL exploration
  (parse-sse-event "content_block_delta"
                   "{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello\"}}")
  (parse-sse-event "ping" "{}")
  (parse-sse-event "message_start"
                   "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_123\",\"model\":\"claude-sonnet-4-20250514\"}}")

  ;; Test read-sse-events with a string reader
  (require '[clojure.java.io :as io])
  (let [input (str "event: message_start\n"
                   "data: {\"type\":\"message_start\"}\n"
                   "\n"
                   "event: content_block_delta\n"
                   "data: {\"type\":\"content_block_delta\",\"delta\":{\"text\":\"Hi\"}}\n"
                   "\n")
        reader (io/reader (java.io.StringReader. input))]
    (vec (read-sse-events reader)))
  ,)
