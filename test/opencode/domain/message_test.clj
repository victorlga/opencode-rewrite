(ns opencode.domain.message-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [malli.core :as m]
   [opencode.domain.message :as message]))

;; ---------------------------------------------------------------------------
;; Constructor + schema validation tests
;; ---------------------------------------------------------------------------

(deftest system-message-test
  (testing "creates a valid system message"
    (let [msg (message/system-message "You are a helpful assistant.")]
      (is (= :system (:message/role msg)))
      (is (= "You are a helpful assistant." (:message/content msg)))
      (is (m/validate message/SystemMessage msg))
      (is (m/validate message/Message msg)))))

(deftest user-message-test
  (testing "creates a valid user message"
    (let [msg (message/user-message "Hello!")]
      (is (= :user (:message/role msg)))
      (is (= "Hello!" (:message/content msg)))
      (is (m/validate message/UserMessage msg))
      (is (m/validate message/Message msg))))

  (testing "user message with empty string still validates"
    (let [msg (message/user-message "")]
      (is (= "" (:message/content msg)))
      (is (m/validate message/UserMessage msg)))))

(deftest assistant-message-test
  (testing "creates a valid assistant message without tool calls"
    (let [msg (message/assistant-message "Sure, I can help." nil :stop)]
      (is (= :assistant (:message/role msg)))
      (is (= "Sure, I can help." (:message/content msg)))
      (is (= :stop (:message/finish-reason msg)))
      (is (nil? (:message/tool-calls msg)))
      (is (m/validate message/AssistantMessage msg))
      (is (m/validate message/Message msg))))

  (testing "creates a valid assistant message with nil content and tool calls"
    (let [tool-calls [{:tool-call/id        "tc_1"
                       :tool-call/name      "bash"
                       :tool-call/arguments {:command "ls"}}]
          msg        (message/assistant-message nil tool-calls :tool-calls)]
      (is (= :assistant (:message/role msg)))
      (is (nil? (:message/content msg)))
      (is (= :tool-calls (:message/finish-reason msg)))
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
      (is (= :tool (:message/role msg)))
      (is (= "tc_1" (:message/tool-call-id msg)))
      (is (= "file1.txt\nfile2.txt" (:message/content msg)))
      (is (m/validate message/ToolResultMessage msg))
      (is (m/validate message/Message msg)))))

;; ---------------------------------------------------------------------------
;; Validation rejection tests
;; ---------------------------------------------------------------------------

(deftest validation-rejects-bad-data
  (testing "user-message rejects non-string content"
    (is (thrown? clojure.lang.ExceptionInfo
                (message/user-message 42))))

  (testing "system-message rejects nil content"
    (is (thrown? clojure.lang.ExceptionInfo
                (message/system-message nil))))

  (testing "assistant-message rejects invalid finish-reason"
    (is (thrown? clojure.lang.ExceptionInfo
                (message/assistant-message "Hi" nil :invalid-reason))))

  (testing "tool-result rejects nil call-id"
    (is (thrown? clojure.lang.ExceptionInfo
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
