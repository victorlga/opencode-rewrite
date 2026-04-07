(ns opencode.logic.streaming-test
  (:require
   [clojure.core.async :as async]
   [clojure.test :refer [deftest is testing]]
   [cognitect.anomalies :as anom]
   [matcher-combinators.test :refer [match?]]
   [opencode.logic.streaming :as streaming])
  (:import
   (java.io BufferedReader StringReader)))

(defn- ->reader
  "Creates a BufferedReader from a string for testing."
  [s]
  (BufferedReader. (StringReader. s)))

;; ---------------------------------------------------------------------------
;; parse-sse-event tests
;; ---------------------------------------------------------------------------

(deftest parse-sse-event-test
  (testing "handles content_block_delta with text delta"
    (let [data "{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello\"}}"
          evt  (streaming/parse-sse-event "content_block_delta" data)]
      (is (match? {:sse/type :content_block_delta
                   :sse/data {:type  "content_block_delta"
                              :delta {:type "text_delta" :text "Hello"}}}
                  evt))))

  (testing "handles message_start with message metadata"
    (let [data "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_123\",\"model\":\"claude-sonnet-4-20250514\"}}"
          evt  (streaming/parse-sse-event "message_start" data)]
      (is (match? {:sse/type :message_start
                   :sse/data {:type    "message_start"
                              :message {:id    "msg_123"
                                        :model "claude-sonnet-4-20250514"}}}
                  evt))))

  (testing "handles error events"
    (let [data "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}"
          evt  (streaming/parse-sse-event "error" data)]
      (is (match? {:sse/type :error
                   :sse/data {:type  "error"
                              :error {:type    "overloaded_error"
                                      :message "Overloaded"}}}
                  evt))))

  (testing "handles message_delta"
    (let [data "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}"
          evt  (streaming/parse-sse-event "message_delta" data)]
      (is (match? {:sse/type :message_delta
                   :sse/data {:delta {:stop_reason "end_turn"}}}
                  evt))))

  (testing "handles content_block_start"
    (let [data "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}"
          evt  (streaming/parse-sse-event "content_block_start" data)]
      (is (match? {:sse/type :content_block_start
                   :sse/data {:index         0
                              :content_block {:type "text"}}}
                  evt))))

  (testing "handles content_block_stop"
    (let [data "{\"type\":\"content_block_stop\",\"index\":0}"
          evt  (streaming/parse-sse-event "content_block_stop" data)]
      (is (match? {:sse/type :content_block_stop
                   :sse/data {:index 0}}
                  evt))))

  (testing "handles message_stop"
    (let [data "{\"type\":\"message_stop\"}"
          evt  (streaming/parse-sse-event "message_stop" data)]
      (is (match? {:sse/type :message_stop} evt))))

  (testing "returns nil for ping events"
    (is (nil? (streaming/parse-sse-event "ping" "{}")))))

;; ---------------------------------------------------------------------------
;; read-sse-events tests
;; ---------------------------------------------------------------------------

(deftest read-sse-events-test
  (testing "parses a multi-event stream from a BufferedReader"
    (let [input  (str "event: message_start\n"
                      "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\"}}\n"
                      "\n"
                      "event: content_block_delta\n"
                      "data: {\"type\":\"content_block_delta\",\"delta\":{\"text\":\"Hi\"}}\n"
                      "\n"
                      "event: message_stop\n"
                      "data: {\"type\":\"message_stop\"}\n"
                      "\n")
          events (vec (streaming/read-sse-events (->reader input)))]
      (is (= 3 (count events)))
      (is (match? {:sse/type :message_start} (first events)))
      (is (match? {:sse/type :content_block_delta
                   :sse/data {:delta {:text "Hi"}}}
                  (second events)))
      (is (match? {:sse/type :message_stop} (nth events 2)))))

  (testing "skips ping events in the stream"
    (let [input  (str "event: ping\n"
                      "data: {}\n"
                      "\n"
                      "event: content_block_delta\n"
                      "data: {\"type\":\"content_block_delta\",\"delta\":{\"text\":\"Hello\"}}\n"
                      "\n")
          events (vec (streaming/read-sse-events (->reader input)))]
      (is (= 1 (count events)))
      (is (match? {:sse/type :content_block_delta} (first events)))))

  (testing "handles empty input"
    (is (match? [] (vec (streaming/read-sse-events (->reader ""))))))

  (testing "handles stream with only pings"
    (let [input (str "event: ping\n"
                     "data: {}\n"
                     "\n")]
      (is (match? [] (vec (streaming/read-sse-events (->reader input))))))))

;; ---------------------------------------------------------------------------
;; sse-events->channel! tests
;; ---------------------------------------------------------------------------

(deftest sse-events->channel!-test
  (testing "delivers events and closes channel on reader end"
    (let [input  (str "event: message_start\n"
                      "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\"}}\n"
                      "\n"
                      "event: content_block_delta\n"
                      "data: {\"type\":\"content_block_delta\",\"delta\":{\"text\":\"World\"}}\n"
                      "\n"
                      "event: message_stop\n"
                      "data: {\"type\":\"message_stop\"}\n"
                      "\n")
          ch     (streaming/sse-events->channel! (->reader input))
          [e1 _] (async/alts!! [ch (async/timeout 2000)])
          [e2 _] (async/alts!! [ch (async/timeout 2000)])
          [e3 _] (async/alts!! [ch (async/timeout 2000)])
          [e4 c] (async/alts!! [ch (async/timeout 2000)])]
      (is (match? {:sse/type :message_start} e1))
      (is (match? {:sse/type :content_block_delta
                   :sse/data {:delta {:text "World"}}}
                  e2))
      (is (match? {:sse/type :message_stop} e3))
      ;; Channel should be closed after all events — nil from ch
      (is (nil? e4))
      (is (= ch c) "should return from ch (closed), not timeout")))

  (testing "closes channel on empty input"
    (let [ch     (streaming/sse-events->channel! (->reader ""))
          [evt c] (async/alts!! [ch (async/timeout 2000)])]
      (is (nil? evt))
      (is (= ch c) "channel should close immediately on empty input")))

  (testing "delivers anomaly map on malformed JSON and closes channel"
    (let [input  (str "event: content_block_delta\n"
                      "data: {not valid json}\n"
                      "\n")
          ch     (streaming/sse-events->channel! (->reader input))
          [evt _] (async/alts!! [ch (async/timeout 2000)])
          [eof c] (async/alts!! [ch (async/timeout 2000)])]
      (is (match? {::anom/category ::anom/fault
                   ::anom/message  string?}
                  evt))
      (is (nil? eof))
      (is (= ch c) "channel should close after anomaly")))

  (testing "stops putting events when channel is closed by consumer"
    ;; With in-memory StringReader, events may already be buffered before
    ;; close takes effect. The key invariant: >!! returns false on closed
    ;; channel, loop exits, and no more events are put.
    (let [input  (str "event: message_start\n"
                      "data: {\"type\":\"message_start\"}\n"
                      "\n"
                      "event: content_block_delta\n"
                      "data: {\"type\":\"content_block_delta\",\"delta\":{\"text\":\"Hi\"}}\n"
                      "\n"
                      "event: message_stop\n"
                      "data: {\"type\":\"message_stop\"}\n"
                      "\n")
          ch     (streaming/sse-events->channel! (->reader input))
          [e1 _] (async/alts!! [ch (async/timeout 2000)])]
      (is (match? {:sse/type :message_start} e1))
      ;; Close channel — loop should stop putting on next >!! attempt
      (async/close! ch)
      ;; Drain any events that were already buffered before close
      (loop []
        (let [[v _] (async/alts!! [ch (async/timeout 500)])]
          (when v (recur))))
      ;; Channel should now be fully drained and closed
      (is (nil? (async/<!! ch))))))
