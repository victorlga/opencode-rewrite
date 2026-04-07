(ns opencode.adapter.llm.anthropic-test
  (:require
   [clojure.core.async :as async]
   [clojure.test :refer [deftest is testing]]
   [matcher-combinators.test :refer [match?]]
   [opencode.adapter.llm.anthropic :as anthropic]
   [opencode.domain.message :as message]))

;; ---------------------------------------------------------------------------
;; messages->anthropic tests
;; ---------------------------------------------------------------------------

(deftest messages->anthropic-test
  (testing "converts user message to Anthropic format"
    (let [msgs   [(message/user-message "Hello!")]
          result (anthropic/messages->anthropic msgs)]
      (is (match? {:messages [{:role "user" :content "Hello!"}]
                   :system   nil}
                  result))))

  (testing "extracts system message to separate :system key"
    (let [msgs   [(message/system-message "You are helpful.")
                  (message/user-message "Hi")]
          result (anthropic/messages->anthropic msgs)]
      (is (match? {:messages [{:role "user" :content "Hi"}]
                   :system   "You are helpful."}
                  result))))

  (testing "converts assistant message with text content"
    (let [msgs   [(message/assistant-message "Sure thing!" nil :stop)]
          result (anthropic/messages->anthropic msgs)]
      (is (match? {:messages [{:role "assistant" :content "Sure thing!"}]}
                  result))))

  (testing "converts assistant message with tool calls"
    (let [msgs   [(message/assistant-message
                    "Let me check."
                    [{:tool-call/id "tc_1"
                      :tool-call/name "bash"
                      :tool-call/arguments {:command "ls"}}]
                    :tool-calls)]
          result (anthropic/messages->anthropic msgs)]
      (is (match? {:messages [{:role    "assistant"
                               :content [{:type "text" :text "Let me check."}
                                         {:type  "tool_use"
                                          :id    "tc_1"
                                          :name  "bash"
                                          :input {:command "ls"}}]}]}
                  result))))

  (testing "converts assistant message with tool calls but no text"
    (let [msgs   [(message/assistant-message
                    nil
                    [{:tool-call/id "tc_1"
                      :tool-call/name "bash"
                      :tool-call/arguments {:command "ls"}}]
                    :tool-calls)]
          result (anthropic/messages->anthropic msgs)]
      (is (match? {:messages [{:role    "assistant"
                               :content [{:type  "tool_use"
                                          :id    "tc_1"
                                          :name  "bash"
                                          :input {:command "ls"}}]}]}
                  result))))

  (testing "converts tool result to tool_result content block"
    (let [msgs   [(message/tool-result "tc_1" "file1.txt\nfile2.txt")]
          result (anthropic/messages->anthropic msgs)]
      (is (match? {:messages [{:role    "user"
                               :content [{:type        "tool_result"
                                          :tool_use_id "tc_1"
                                          :content     "file1.txt\nfile2.txt"}]}]}
                  result))))

  (testing "handles full conversation with all message types"
    (let [msgs   [(message/system-message "You are helpful.")
                  (message/user-message "List files")
                  (message/assistant-message
                    nil
                    [{:tool-call/id "tc_1"
                      :tool-call/name "bash"
                      :tool-call/arguments {:command "ls"}}]
                    :tool-calls)
                  (message/tool-result "tc_1" "a.txt\nb.txt")
                  (message/assistant-message "Here are the files: a.txt, b.txt" nil :stop)]
          result (anthropic/messages->anthropic msgs)]
      (is (match? {:system   "You are helpful."
                   :messages [{:role "user" :content "List files"}
                              {:role    "assistant"
                               :content [{:type "tool_use" :id "tc_1"}]}
                              {:role    "user"
                               :content [{:type "tool_result" :tool_use_id "tc_1"}]}
                              {:role    "assistant"
                               :content "Here are the files: a.txt, b.txt"}]}
                  result)))))

;; ---------------------------------------------------------------------------
;; anthropic-response->message tests
;; ---------------------------------------------------------------------------

(deftest anthropic-response->message-test
  (testing "parses text-only response"
    (let [response {:content     [{:type "text" :text "Hello, world!"}]
                    :stop_reason "end_turn"}
          msg      (anthropic/anthropic-response->message response)]
      (is (match? {:message/role          :assistant
                   :message/content       "Hello, world!"
                   :message/finish-reason :stop}
                  msg))
      (is (nil? (:message/tool-calls msg)))))

  (testing "parses response with tool_use blocks"
    (let [response {:content     [{:type  "tool_use"
                                   :id    "tc_1"
                                   :name  "bash"
                                   :input {:command "ls -la"}}]
                    :stop_reason "tool_use"}
          msg      (anthropic/anthropic-response->message response)]
      (is (match? {:message/role          :assistant
                   :message/content       nil
                   :message/finish-reason :tool-calls
                   :message/tool-calls    [{:tool-call/id        "tc_1"
                                            :tool-call/name      "bash"
                                            :tool-call/arguments {:command "ls -la"}}]}
                  msg))))

  (testing "parses response with mixed text and tool_use"
    (let [response {:content     [{:type "text" :text "I'll run that for you."}
                                  {:type  "tool_use"
                                   :id    "tc_2"
                                   :name  "read-file"
                                   :input {:path "/tmp/test.txt"}}]
                    :stop_reason "tool_use"}
          msg      (anthropic/anthropic-response->message response)]
      (is (match? {:message/role          :assistant
                   :message/content       "I'll run that for you."
                   :message/finish-reason :tool-calls
                   :message/tool-calls    [{:tool-call/id   "tc_2"
                                            :tool-call/name "read-file"}]}
                  msg))))

  (testing "maps stop_reason correctly"
    (is (match? {:message/finish-reason :stop}
                (anthropic/anthropic-response->message
                  {:content [{:type "text" :text "done"}]
                   :stop_reason "end_turn"})))
    (is (match? {:message/finish-reason :tool-calls}
                (anthropic/anthropic-response->message
                  {:content [{:type "tool_use" :id "t" :name "x" :input {}}]
                   :stop_reason "tool_use"})))
    (is (match? {:message/finish-reason :length}
                (anthropic/anthropic-response->message
                  {:content [{:type "text" :text "truncated..."}]
                   :stop_reason "max_tokens"}))))

  (testing "handles empty input in tool_use"
    (let [response {:content     [{:type "tool_use" :id "tc_1" :name "bash" :input nil}]
                    :stop_reason "tool_use"}
          msg      (anthropic/anthropic-response->message response)]
      (is (match? {:message/tool-calls [{:tool-call/arguments {}}]}
                  msg)))))

;; ---------------------------------------------------------------------------
;; process-stream-events! tests
;; ---------------------------------------------------------------------------

(deftest process-stream-events!-test
  (testing "processes text-only stream into text-delta and done events"
    (let [sse-ch (async/chan 10)
          out-ch (async/chan 10)]
      ;; Simulate SSE events
      (async/put! sse-ch {:sse/type :message-start
                          :sse/data {:type "message_start"
                                     :message {:id "msg_1" :model "claude-sonnet-4-20250514"}}})
      (async/put! sse-ch {:sse/type :content-block-start
                          :sse/data {:type "content_block_start"
                                     :index 0
                                     :content_block {:type "text" :text ""}}})
      (async/put! sse-ch {:sse/type :content-block-delta
                          :sse/data {:type "content_block_delta"
                                     :delta {:type "text_delta" :text "Hello"}}})
      (async/put! sse-ch {:sse/type :content-block-delta
                          :sse/data {:type "content_block_delta"
                                     :delta {:type "text_delta" :text " World"}}})
      (async/put! sse-ch {:sse/type :content-block-stop
                          :sse/data {:type "content_block_stop" :index 0}})
      (async/put! sse-ch {:sse/type :message-delta
                          :sse/data {:type "message_delta"
                                     :delta {:stop_reason "end_turn"}}})
      (async/put! sse-ch {:sse/type :message-stop
                          :sse/data {:type "message_stop"}})
      (async/close! sse-ch)

      (#'anthropic/process-stream-events! sse-ch out-ch)

      (let [[e1 _] (async/alts!! [out-ch (async/timeout 5000)])
            [e2 _] (async/alts!! [out-ch (async/timeout 5000)])
            [e3 _] (async/alts!! [out-ch (async/timeout 5000)])
            [e4 c] (async/alts!! [out-ch (async/timeout 5000)])]
        (is (match? {:type :text-delta :text "Hello"} e1))
        (is (match? {:type :text-delta :text " World"} e2))
        (is (match? {:type :done
                     :message {:message/role          :assistant
                               :message/content       "Hello World"
                               :message/finish-reason :stop}}
                    e3))
        ;; Channel should close after done
        (is (nil? e4))
        (is (= out-ch c)))))

  (testing "processes tool_use stream events"
    (let [sse-ch (async/chan 10)
          out-ch (async/chan 10)]
      (async/put! sse-ch {:sse/type :message-start
                          :sse/data {:type "message_start"
                                     :message {:id "msg_1"}}})
      (async/put! sse-ch {:sse/type :content-block-start
                          :sse/data {:type "content_block_start"
                                     :index 0
                                     :content_block {:type "tool_use"
                                                     :id   "tc_1"
                                                     :name "bash"}}})
      (async/put! sse-ch {:sse/type :content-block-delta
                          :sse/data {:type "content_block_delta"
                                     :delta {:type "input_json_delta"
                                             :partial_json "{\"command\":"}}})
      (async/put! sse-ch {:sse/type :content-block-delta
                          :sse/data {:type "content_block_delta"
                                     :delta {:type "input_json_delta"
                                             :partial_json " \"ls\"}"}}})
      (async/put! sse-ch {:sse/type :content-block-stop
                          :sse/data {:type "content_block_stop" :index 0}})
      (async/put! sse-ch {:sse/type :message-delta
                          :sse/data {:type "message_delta"
                                     :delta {:stop_reason "tool_use"}}})
      (async/put! sse-ch {:sse/type :message-stop
                          :sse/data {:type "message_stop"}})
      (async/close! sse-ch)

      (#'anthropic/process-stream-events! sse-ch out-ch)

      (let [[e1 _] (async/alts!! [out-ch (async/timeout 5000)])
            [e2 c] (async/alts!! [out-ch (async/timeout 5000)])]
        (is (match? {:type    :done
                     :message {:message/role          :assistant
                               :message/finish-reason :tool-calls
                               :message/tool-calls    [{:tool-call/id        "tc_1"
                                                        :tool-call/name      "bash"
                                                        :tool-call/arguments {:command "ls"}}]}}
                    e1))
        (is (nil? e2))
        (is (= out-ch c)))))

  (testing "delivers error event on SSE error"
    (let [sse-ch (async/chan 10)
          out-ch (async/chan 10)]
      (async/put! sse-ch {:sse/type :error
                          :sse/data {:type "error"
                                     :error {:type    "overloaded_error"
                                             :message "Overloaded"}}})
      (async/close! sse-ch)

      (#'anthropic/process-stream-events! sse-ch out-ch)

      (let [[e1 _] (async/alts!! [out-ch (async/timeout 5000)])]
        (is (match? {:type  :error
                     :error {:cognitect.anomalies/category :cognitect.anomalies/fault
                             :cognitect.anomalies/message  "Overloaded"}}
                    e1))))))

(comment
  ;; Manual REPL test (requires ANTHROPIC_API_KEY env var)
  ;; (require '[opencode.adapter.llm.provider :as provider])
  ;; (require '[clojure.core.async :as async])
  ;; (def p (anthropic/make-provider (System/getenv "ANTHROPIC_API_KEY")
  ;;                                 "claude-sonnet-4-20250514"))
  ;;
  ;; ;; Synchronous completion
  ;; (provider/complete p [(message/user-message "Say hello in 3 words")] {:max-tokens 100})
  ;;
  ;; ;; Streaming completion
  ;; (let [ch (provider/stream p [(message/user-message "Say hello in 3 words")] {:max-tokens 100})]
  ;;   (loop []
  ;;     (when-let [evt (async/<!! ch)]
  ;;       (println evt)
  ;;       (recur))))
  ,)
