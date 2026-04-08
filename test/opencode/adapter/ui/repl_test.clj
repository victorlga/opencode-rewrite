(ns opencode.adapter.ui.repl-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [opencode.logic.ui :as ui]
   [opencode.adapter.ui.repl :as repl]))

(deftest ansi-wraps-text-test
  (testing "ansi helper wraps text with escape codes and reset"
    (is (= "\033[1;36mhello\033[0m"
           (repl/ansi "1;36" "hello")))))

(deftest ansi-empty-text-test
  (testing "ansi helper works with empty string"
    (is (= "\033[0;31m\033[0m"
           (repl/ansi "0;31" "")))))

(deftest repl-ui-satisfies-protocol-test
  (testing "ReplUI record satisfies UIAdapter protocol"
    ;; Use nil line-reader — we won't call interactive methods
    (let [ui (repl/->ReplUI nil nil)]
      (is (true? (satisfies? ui/UIAdapter ui))))))

(deftest repl-ui-record-fields-test
  (testing "ReplUI has a :line-reader field"
    (let [ui (repl/->ReplUI :fake-reader :fake-terminal)]
      (is (= :fake-reader (:line-reader ui)))
      (is (= :fake-terminal (:terminal ui))))))
