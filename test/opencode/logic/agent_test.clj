(ns opencode.logic.agent-test
  (:require
   [clojure.core.async :as async]
   [clojure.test :refer [deftest is testing]]
   [cognitect.anomalies :as anom]
   [matcher-combinators.test :refer [match?]]
   [opencode.adapter.llm.provider :as llm]
   [opencode.domain.message :as message]
   [opencode.domain.session :as session]
   [opencode.domain.tool :as tool]
   [opencode.logic.agent :as agent]
   [opencode.logic.event-bus :as event-bus]
   [opencode.logic.ui :as ui]
   ;; Load tool adapters so execute-tool! multimethods are registered
   opencode.adapter.tool.file-read))

;; ---------------------------------------------------------------------------
;; Test helpers
;; ---------------------------------------------------------------------------

(defn- mock-ui
  "Creates a no-op UIAdapter for testing. ask-permission! returns :approved."
  []
  (reify ui/UIAdapter
    (display-text! [_ _text])
    (display-tool-call! [_ _tool-name _params])
    (display-tool-result! [_ _tool-name _result])
    (display-error! [_ _error])
    (ask-permission! [_ _tool-name _params] :approved)
    (get-input! [_ _prompt] "")))

(defn- mock-context
  "Creates a tool context map for testing."
  [session-id]
  {:ctx/session-id      session-id
   :ctx/project-dir     "/tmp"
   :ctx/dangerous-mode? true})

(defn- make-text-provider
  "Creates a mock LLM provider that returns a simple text response (no tool calls).
   The stream channel emits a :text-delta then a :done event."
  [response-text]
  (reify llm/LLMProvider
    (complete [_ _messages _opts]
      (message/assistant-message response-text nil :stop))
    (stream [_ _messages _opts]
      (let [ch (async/chan 8)]
        (async/thread
          (async/>!! ch {:type :text-delta :text response-text})
          (async/>!! ch {:type :done
                         :message (message/assistant-message response-text nil :stop)})
          (async/close! ch))
        ch))
    (list-models [_] [])))

(defn- make-tool-then-text-provider
  "Creates a mock LLM provider that returns a tool call on the first invocation,
   then a text response on subsequent calls. Uses a call-count atom for state."
  [tool-call-id tool-name tool-args final-text]
  (let [call-count (atom 0)]
    (reify llm/LLMProvider
      (complete [_ _messages _opts]
        (message/assistant-message final-text nil :stop))
      (stream [_ _messages _opts]
        (let [ch   (async/chan 8)
              call (swap! call-count inc)]
          (async/thread
            (if (= 1 call)
              ;; First call: return tool call
              (let [tc   [{:tool-call/id        tool-call-id
                           :tool-call/name      tool-name
                           :tool-call/arguments tool-args}]
                    msg  (message/assistant-message nil tc :tool-calls)]
                (async/>!! ch {:type :done :message msg}))
              ;; Subsequent calls: return text
              (do
                (async/>!! ch {:type :text-delta :text final-text})
                (async/>!! ch {:type :done
                               :message (message/assistant-message final-text nil :stop)})))
            (async/close! ch))
          ch))
      (list-models [_] []))))

(defn- make-infinite-tool-provider
  "Creates a mock LLM provider that always returns tool calls (infinite loop scenario).
   Used to test the max-iterations guard."
  [tmp-path]
  (let [counter (atom 0)]
    (reify llm/LLMProvider
      (complete [_ _messages _opts]
        (message/assistant-message nil
                                   [{:tool-call/id        "tc_loop"
                                     :tool-call/name      "read_file"
                                     :tool-call/arguments {:path tmp-path}}]
                                   :tool-calls))
      (stream [_ _messages _opts]
        (let [ch (async/chan 8)]
          (async/thread
            (let [n   (swap! counter inc)
                  tc  [{:tool-call/id        (str "tc_" n)
                        :tool-call/name      "read_file"
                        :tool-call/arguments {:path tmp-path}}]
                  msg (message/assistant-message nil tc :tool-calls)]
              (async/>!! ch {:type :done :message msg}))
            (async/close! ch))
          ch))
      (list-models [_] []))))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest simple-text-response-test
  (testing "agent loop with simple text response (no tool calls)"
    (let [bus      (event-bus/create-bus)
          ui       (mock-ui)
          s        (session/create-session "test-model")
          s-with-msg (session/append-message s (message/user-message "Hello!"))
          provider (make-text-provider "Hello there!")
          result   (agent/run-agent-loop!
                     {:provider   provider
                      :session    s-with-msg
                      :tools      []
                      :event-bus  bus
                      :ui-adapter ui
                      :context    (mock-context (:session/id s))})]
      (is (not (message/anomaly? result)))
      ;; Session should have user message + assistant message = 2 messages
      (is (= 2 (count (session/get-messages result))))
      (let [assistant-msg (last (session/get-messages result))]
        (is (match? {:message/role          :assistant
                     :message/content       "Hello there!"
                     :message/finish-reason :stop}
                    assistant-msg)))
      (event-bus/close-bus! bus))))

(deftest tool-call-then-text-response-test
  (testing "agent loop: tool call -> tool result -> final text response"
    (let [tmp-file (java.io.File/createTempFile "agent-test" ".txt")]
      (spit tmp-file "test file content")
      (try
        (let [bus      (event-bus/create-bus)
              ui       (mock-ui)
              s        (session/create-session "test-model")
              s-with-msg (session/append-message s (message/user-message "Read a file"))
              provider (make-tool-then-text-provider
                         "tc_1" "read_file" {:path (.getAbsolutePath tmp-file)}
                         "The file contains: test data")
              result   (agent/run-agent-loop!
                         {:provider   provider
                          :session    s-with-msg
                          :tools      (tool/all-tools)
                          :event-bus  bus
                          :ui-adapter ui
                          :context    (mock-context (:session/id s))})]
          (is (not (message/anomaly? result)))
          ;; user + assistant(tool-call) + tool-result + assistant(text) = 4 messages
          (let [msgs (session/get-messages result)]
            (is (= 4 (count msgs)))
            ;; First message: user
            (is (= :user (:message/role (nth msgs 0))))
            ;; Second: assistant with tool call
            (is (= :assistant (:message/role (nth msgs 1))))
            (is (= :tool-calls (:message/finish-reason (nth msgs 1))))
            ;; Third: tool result
            (is (= :tool (:message/role (nth msgs 2))))
            (is (= "tc_1" (:message/tool-call-id (nth msgs 2))))
            ;; Tool result should contain the file content
            (is (re-find #"test file content" (:message/content (nth msgs 2))))
            ;; Fourth: final assistant text
            (is (= :assistant (:message/role (nth msgs 3))))
            (is (= "The file contains: test data" (:message/content (nth msgs 3))))
            (is (= :stop (:message/finish-reason (nth msgs 3)))))
          (event-bus/close-bus! bus))
        (finally
          (.delete tmp-file))))))

(deftest max-iterations-guard-test
  (testing "agent loop terminates after max iterations and doesn't hang"
    (let [tmp-file (java.io.File/createTempFile "agent-loop" ".txt")]
      (spit tmp-file "loop test content")
      (try
        (let [bus      (event-bus/create-bus)
              ui       (mock-ui)
              s        (session/create-session "test-model")
              s-with-msg (session/append-message s (message/user-message "Do something"))
              provider (make-infinite-tool-provider (.getAbsolutePath tmp-file))
              result   (agent/run-agent-loop!
                         {:provider   provider
                          :session    s-with-msg
                          :tools      (tool/all-tools)
                          :event-bus  bus
                          :ui-adapter ui
                          :context    (mock-context (:session/id s))})]
          (is (not (message/anomaly? result)))
          ;; Should have terminated — last message should indicate max iterations
          (let [msgs (session/get-messages result)
                last-msg (last msgs)]
            ;; We should have more messages than just the user message
            (is (> (count msgs) 1))
            ;; Last message should be the "max iterations" assistant message
            (is (= :assistant (:message/role last-msg)))
            (is (re-find #"maximum" (:message/content last-msg))))
          (event-bus/close-bus! bus))
        (finally
          (.delete tmp-file))))))

(deftest error-handling-test
  (testing "agent loop handles LLM stream errors gracefully"
    (let [bus      (event-bus/create-bus)
          ui       (mock-ui)
          s        (session/create-session "test-model")
          s-with-msg (session/append-message s (message/user-message "Hello!"))
          provider (reify llm/LLMProvider
                     (complete [_ _messages _opts]
                       {::anom/category ::anom/fault
                        ::anom/message "API error"})
                     (stream [_ _messages _opts]
                       (let [ch (async/chan 1)]
                         (async/put! ch {:type  :error
                                         :error {::anom/category ::anom/fault
                                                 ::anom/message  "API error"}})
                         (async/close! ch)
                         ch))
                     (list-models [_] []))
          result   (agent/run-agent-loop!
                     {:provider   provider
                      :session    s-with-msg
                      :tools      []
                      :event-bus  bus
                      :ui-adapter ui
                      :context    (mock-context (:session/id s))})]
      (is (not (message/anomaly? result)))
      ;; Should have user message + error assistant message
      (let [msgs (session/get-messages result)]
        (is (= 2 (count msgs)))
        (is (= :error (:message/finish-reason (last msgs)))))
      (event-bus/close-bus! bus))))

(deftest nil-event-bus-and-ui-test
  (testing "agent loop works with nil event-bus and ui-adapter"
    (let [s        (session/create-session "test-model")
          s-with-msg (session/append-message s (message/user-message "Hello!"))
          provider (make-text-provider "Hi!")
          result   (agent/run-agent-loop!
                     {:provider   provider
                      :session    s-with-msg
                      :tools      []
                      :event-bus  nil
                      :ui-adapter nil
                      :context    (mock-context (:session/id s))})]
      (is (not (message/anomaly? result)))
      (is (= 2 (count (session/get-messages result)))))))

(deftest event-bus-receives-events-test
  (testing "agent loop publishes :session/updated events to the bus"
    (let [bus    (event-bus/create-bus)
          sub-ch (event-bus/subscribe! bus :session/updated 16)
          ui     (mock-ui)
          s      (session/create-session "test-model")
          s-with-msg (session/append-message s (message/user-message "Hello!"))
          provider (make-text-provider "Hello!")
          _result (agent/run-agent-loop!
                    {:provider   provider
                     :session    s-with-msg
                     :tools      []
                     :event-bus  bus
                     :ui-adapter ui
                     :context    (mock-context (:session/id s))})
          ;; Read the event with timeout
          [evt _] (async/alts!! [sub-ch (async/timeout 2000)])]
      (is (some? evt))
      (is (match? {:event/type :session/updated} evt))
      (event-bus/unsubscribe! bus :session/updated sub-ch)
      (event-bus/close-bus! bus))))
