(ns opencode.domain.message-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [cognitect.anomalies :as anom]
   [malli.core :as m]
   [matcher-combinators.test :refer [match?]]
   [opencode.domain.message :as message]))

;; ---------------------------------------------------------------------------
;; Constructor + schema validation tests
;; ---------------------------------------------------------------------------

(deftest system-message-test
  (testing "creates a valid system message"
    (let [msg (message/system-message "You are a helpful assistant.")]
      (is (match? {:message/role :system
                   :message/content "You are a helpful assistant."}
                  msg))
      (is (m/validate message/SystemMessage msg))
      (is (m/validate message/Message msg)))))

(deftest user-message-test
  (testing "creates a valid user message"
    (let [msg (message/user-message "Hello!")]
      (is (match? {:message/role :user
                   :message/content "Hello!"}
                  msg))
      (is (m/validate message/UserMessage msg))
      (is (m/validate message/Message msg))))

  (testing "user message with empty string still validates"
    (let [msg (message/user-message "")]
      (is (match? {:message/content ""} msg))
      (is (m/validate message/UserMessage msg)))))

(deftest assistant-message-test
  (testing "creates a valid assistant message without tool calls"
    (let [msg (message/assistant-message "Sure, I can help." nil :stop)]
      (is (match? {:message/role          :assistant
                   :message/content       "Sure, I can help."
                   :message/finish-reason :stop}
                  msg))
      (is (nil? (:message/tool-calls msg)))
      (is (m/validate message/AssistantMessage msg))
      (is (m/validate message/Message msg))))

  (testing "creates a valid assistant message with nil content and tool calls"
    (let [tool-calls [{:tool-call/id        "tc_1"
                       :tool-call/name      "bash"
                       :tool-call/arguments {:command "ls"}}]
          msg        (message/assistant-message nil tool-calls :tool-calls)]
      (is (match? {:message/role          :assistant
                   :message/content       nil
                   :message/finish-reason :tool-calls}
                  msg))
      (is (= 1 (count (:message/tool-calls msg))))
      (is (m/validate message/AssistantMessage msg))))

  (testing "creates assistant message with multiple tool calls"
    (let [tool-calls [{:tool-call/id        "tc_1"
                       :tool-call/name      "bash"
                       :tool-call/arguments {:command "ls"}}
                      {:tool-call/id        "tc_2"
                       :tool-call/name      "read_file"
                       :tool-call/arguments {:path "/tmp/test.txt"}}]
          msg        (message/assistant-message nil tool-calls :tool-calls)]
      (is (= 2 (count (:message/tool-calls msg))))
      (is (m/validate message/AssistantMessage msg)))))

(deftest tool-result-test
  (testing "creates a valid tool result message"
    (let [msg (message/tool-result "tc_1" "file1.txt\nfile2.txt")]
      (is (match? {:message/role        :tool
                   :message/tool-call-id "tc_1"
                   :message/content     "file1.txt\nfile2.txt"}
                  msg))
      (is (m/validate message/ToolResultMessage msg))
      (is (m/validate message/Message msg)))))

;; ---------------------------------------------------------------------------
;; Validation rejection tests
;; ---------------------------------------------------------------------------

(deftest validation-returns-anomaly-on-bad-data
  (testing "user-message returns anomaly for non-string content"
    (is (match? {::anom/category ::anom/incorrect
                 ::anom/message  "Invalid message data"}
                (message/user-message 42))))

  (testing "system-message returns anomaly for nil content"
    (is (match? {::anom/category ::anom/incorrect}
                (message/system-message nil))))

  (testing "assistant-message returns anomaly for invalid finish-reason"
    (is (match? {::anom/category ::anom/incorrect}
                (message/assistant-message "Hi" nil :invalid-reason))))

  (testing "tool-result returns anomaly for nil call-id"
    (is (match? {::anom/category ::anom/incorrect}
                (message/tool-result nil "output")))))

;; ---------------------------------------------------------------------------
;; Schema validation tests
;; ---------------------------------------------------------------------------

(deftest schema-validation-test
  (testing "Role schema validates correct roles"
    (doseq [role [:system :user :assistant :tool]]
      (is (m/validate message/Role role))))

  (testing "Role schema rejects invalid role"
    (is (not (m/validate message/Role :invalid))))

  (testing "ToolCall schema validates correct data"
    (is (m/validate message/ToolCall
                    {:tool-call/id        "tc_1"
                     :tool-call/name      "bash"
                     :tool-call/arguments {:command "ls"}})))

  (testing "ToolCall schema rejects missing fields"
    (is (not (m/validate message/ToolCall
                         {:tool-call/id "tc_1"})))))
