(ns opencode.logic.permission-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [opencode.logic.ui :as ui]
   [opencode.logic.permission :as permission]))

(def ^:private safe-context
  {:ctx/session-id      (java.util.UUID/randomUUID)
   :ctx/project-dir     "/tmp/test"
   :ctx/dangerous-mode? false})

(def ^:private dangerous-context
  {:ctx/session-id      (java.util.UUID/randomUUID)
   :ctx/project-dir     "/tmp/test"
   :ctx/dangerous-mode? true})

(deftest read-tools-allowed-test
  (testing "read-only tools get :allow"
    (is (= :allow (permission/check-permission "read_file" safe-context)))
    (is (= :allow (permission/check-permission "glob" safe-context)))
    (is (= :allow (permission/check-permission "grep" safe-context)))))

(deftest write-tools-ask-test
  (testing "write/edit/bash tools get :ask"
    (is (= :ask (permission/check-permission "write_file" safe-context)))
    (is (= :ask (permission/check-permission "edit_file" safe-context)))
    (is (= :ask (permission/check-permission "bash" safe-context)))))

(deftest unknown-tool-defaults-to-ask-test
  (testing "unknown tool name defaults to :ask"
    (is (= :ask (permission/check-permission "unknown_tool" safe-context)))
    (is (= :ask (permission/check-permission "some_new_tool" safe-context)))))

(deftest dangerous-mode-overrides-test
  (testing "dangerous-mode? true overrides all tools to :allow"
    (is (= :allow (permission/check-permission "bash" dangerous-context)))
    (is (= :allow (permission/check-permission "write_file" dangerous-context)))
    (is (= :allow (permission/check-permission "edit_file" dangerous-context)))
    (is (= :allow (permission/check-permission "read_file" dangerous-context)))
    (is (= :allow (permission/check-permission "unknown_tool" dangerous-context)))))

(deftest request-permission-approved-test
  (testing "request-permission! delegates to UI adapter and returns :approved"
    (let [mock-ui (reify ui/UIAdapter
                    (ask-permission! [_ _tool-name _params] :approved)
                    (display-text! [_ _text])
                    (display-tool-call! [_ _tool-name _params])
                    (display-tool-result! [_ _tool-name _result])
                    (display-error! [_ _error])
                    (get-input! [_ _prompt] ""))]
      (is (= :approved
             (permission/request-permission! mock-ui "bash" {:command "ls"}))))))

(deftest request-permission-denied-test
  (testing "request-permission! delegates to UI adapter and returns :denied"
    (let [mock-ui (reify ui/UIAdapter
                    (ask-permission! [_ _tool-name _params] :denied)
                    (display-text! [_ _text])
                    (display-tool-call! [_ _tool-name _params])
                    (display-tool-result! [_ _tool-name _result])
                    (display-error! [_ _error])
                    (get-input! [_ _prompt] ""))]
      (is (= :denied
             (permission/request-permission! mock-ui "bash" {:command "rm -rf /"}))))))
