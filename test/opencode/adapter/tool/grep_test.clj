(ns opencode.adapter.tool.grep-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [babashka.fs :as fs]
   [matcher-combinators.test :refer [match?]]
   [opencode.adapter.tool.grep]
   [opencode.domain.tool :as tool])
  (:import
   (java.util UUID)))

;; ---------------------------------------------------------------------------
;; Temp directory fixture
;; ---------------------------------------------------------------------------

(def ^:dynamic *tmp-dir* nil)

(defn- make-context []
  {:ctx/session-id      (UUID/randomUUID)
   :ctx/project-dir     (str *tmp-dir*)
   :ctx/dangerous-mode? false})

(use-fixtures :each
  (fn [f]
    (let [tmp (str (fs/create-temp-dir {:prefix "grep-test"}))]
      (binding [*tmp-dir* tmp]
        ;; Create test file tree
        (spit (str (fs/path tmp "hello.txt")) "hello world\nfoo bar\nbaz qux")
        (spit (str (fs/path tmp "code.clj")) "(defn greet [] \"hello\")\n(defn bye [] \"goodbye\")")
        (fs/create-dirs (str (fs/path tmp "sub")))
        (spit (str (fs/path tmp "sub" "nested.txt")) "nested hello content")
        (spit (str (fs/path tmp "data.js")) "const x = 42;\nfunction hello() {}\n")
        (try (f) (finally (fs/delete-tree tmp)))))))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest finds-matches-across-files-test
  (testing "finds matches across multiple files"
    (let [result (tool/execute-tool! "grep"
                                      {:pattern "hello"}
                                      (make-context))]
      (is (match? {:output #"hello"} result))
      ;; Should find hello in hello.txt, code.clj, sub/nested.txt, data.js
      (is (>= (count (re-seq #"hello" (:output result))) 3)))))

(deftest no-matches-test
  (testing "returns empty for non-matching pattern"
    (let [result (tool/execute-tool! "grep"
                                      {:pattern "zzzznotfound"}
                                      (make-context))]
      (is (match? {:output #"[Nn]o matches"} result)))))

(deftest include-filter-test
  (testing "--include filter works"
    (let [result (tool/execute-tool! "grep"
                                      {:pattern "hello"
                                       :include "*.clj"}
                                      (make-context))]
      (is (match? {:output #"hello"} result))
      ;; Should only match in .clj files
      (is (not (.contains ^String (:output result) ".txt")))
      (is (not (.contains ^String (:output result) ".js"))))))

(deftest specific-path-test
  (testing "searches within specific subdirectory"
    (let [result (tool/execute-tool! "grep"
                                      {:pattern "nested"
                                       :path "sub"}
                                      (make-context))]
      (is (match? {:output #"nested"} result)))))

(deftest regex-pattern-test
  (testing "regex patterns work"
    (let [result (tool/execute-tool! "grep"
                                      {:pattern "def[n]"
                                       :include "*.clj"}
                                      (make-context))]
      (is (match? {:output #"defn"} result)))))
