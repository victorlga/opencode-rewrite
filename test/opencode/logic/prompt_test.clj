(ns opencode.logic.prompt-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [opencode.logic.prompt :as prompt]))

(deftest build-system-prompt-contains-role-test
  (testing "system prompt includes 'AI coding assistant' role"
    (let [result (prompt/build-system-prompt {} [])]
      (is (string? result))
      (is (re-find #"AI coding assistant" result)))))

(deftest build-system-prompt-includes-tool-names-test
  (testing "system prompt lists tool names and descriptions when tools provided"
    (let [tools  [{:tool/name        "bash"
                   :tool/description "Run shell commands"
                   :tool/parameters  [:map]
                   :tool/dangerous?  true}
                  {:tool/name        "read_file"
                   :tool/description "Read a file from disk"
                   :tool/parameters  [:map]
                   :tool/dangerous?  false}]
          result (prompt/build-system-prompt {} tools)]
      (is (re-find #"bash" result))
      (is (re-find #"Run shell commands" result))
      (is (re-find #"read_file" result))
      (is (re-find #"Read a file from disk" result)))))

(deftest build-system-prompt-includes-working-directory-test
  (testing "system prompt includes the working directory"
    (let [result (prompt/build-system-prompt {} [])]
      (is (re-find #"Working directory:" result))
      ;; Should contain the actual user.dir value
      (is (re-find (re-pattern (System/getProperty "user.dir")) result)))))

(deftest build-system-prompt-empty-tools-test
  (testing "system prompt works with empty tools list"
    (let [result (prompt/build-system-prompt {} [])]
      (is (string? result))
      (is (re-find #"No tools available" result)))))

(deftest build-system-prompt-includes-date-test
  (testing "system prompt includes today's date"
    (let [result (prompt/build-system-prompt {} [])
          today  (str (java.time.LocalDate/now))]
      (is (re-find (re-pattern today) result)))))

(deftest build-system-prompt-includes-platform-test
  (testing "system prompt includes OS information"
    (let [result (prompt/build-system-prompt {} [])]
      (is (re-find #"Platform:" result))
      (is (re-find (re-pattern (System/getProperty "os.name")) result)))))
