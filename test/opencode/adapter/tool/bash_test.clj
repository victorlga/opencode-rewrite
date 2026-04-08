(ns opencode.adapter.tool.bash-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [cognitect.anomalies :as anom]
   [matcher-combinators.test :refer [match?]]
   [opencode.adapter.tool.bash]
   [opencode.domain.tool :as tool])
  (:import
   (java.util UUID)))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- make-context
  ([] (make-context true))
  ([dangerous?]
   {:ctx/session-id      (UUID/randomUUID)
    :ctx/project-dir     "/tmp"
    :ctx/dangerous-mode? dangerous?}))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest echo-test
  (testing "echo hello returns output with exit-code 0"
    (let [result (tool/execute-tool! "bash"
                                      {:command "echo hello"}
                                      (make-context))]
      (is (match? {:output #"hello" :exit-code 0} result)))))

(deftest non-zero-exit-test
  (testing "exit 1 returns exit-code 1, not an anomaly"
    (let [result (tool/execute-tool! "bash"
                                      {:command "exit 1"}
                                      (make-context))]
      (is (= 1 (:exit-code result)))
      (is (nil? (::anom/category result))))))

(deftest timeout-test
  (testing "timeout with sleep returns :busy anomaly"
    (let [result (tool/execute-tool! "bash"
                                      {:command "sleep 10" :timeout 200}
                                      (make-context))]
      (is (match? {::anom/category ::anom/busy
                   ::anom/message  #"timed out"}
                  result)))))

(deftest stderr-captured-test
  (testing "stderr output is captured"
    (let [result (tool/execute-tool! "bash"
                                      {:command "echo err >&2"}
                                      (make-context))]
      (is (match? {:output #"err" :exit-code 0} result)))))

(deftest requires-dangerous-mode-test
  (testing "returns forbidden when dangerous mode is off"
    (is (match? {::anom/category ::anom/forbidden}
                (tool/execute-tool! "bash"
                                   {:command "echo hello"}
                                   (make-context false))))))

(deftest combined-output-test
  (testing "stdout and stderr are combined"
    (let [result (tool/execute-tool! "bash"
                                      {:command "echo out && echo err >&2"}
                                      (make-context))]
      (is (match? {:exit-code 0} result))
      (is (.contains ^String (:output result) "out"))
      (is (.contains ^String (:output result) "err")))))
