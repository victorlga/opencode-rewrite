(ns opencode.adapter.tool.file-edit-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [babashka.fs :as fs]
   [cognitect.anomalies :as anom]
   [matcher-combinators.test :refer [match?]]
   [opencode.adapter.tool.file-edit]
   [opencode.domain.tool :as tool])
  (:import
   (java.util UUID)))

;; ---------------------------------------------------------------------------
;; Temp directory fixture
;; ---------------------------------------------------------------------------

(def ^:dynamic *tmp-dir* nil)

(defn- make-context
  ([] (make-context true))
  ([dangerous?]
   {:ctx/session-id      (UUID/randomUUID)
    :ctx/project-dir     (str *tmp-dir*)
    :ctx/dangerous-mode? dangerous?}))

(use-fixtures :each
  (fn [f]
    (let [tmp (str (fs/create-temp-dir {:prefix "file-edit-test"}))]
      (binding [*tmp-dir* tmp]
        (try (f) (finally (fs/delete-tree tmp)))))))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest exact-replacement-test
  (testing "exact string replacement works"
    (let [file-path (str (fs/path *tmp-dir* "exact.txt"))]
      (spit file-path "hello world\nfoo bar\nbaz qux")
      (let [result (tool/execute-tool! "edit_file"
                                        {:path "exact.txt"
                                         :old_string "foo bar"
                                         :new_string "replaced"}
                                        (make-context))]
        (is (match? {:output #"Edited.*replaced 7 chars"} result))
        (is (= "hello world\nreplaced\nbaz qux" (slurp file-path)))))))

(deftest whitespace-normalized-match-test
  (testing "whitespace-normalized matching works (extra indentation in file)"
    (let [file-path (str (fs/path *tmp-dir* "ws.txt"))]
      (spit file-path "  hello world\n    foo bar\n  baz qux")
      (let [result (tool/execute-tool! "edit_file"
                                        {:path "ws.txt"
                                         :old_string "foo bar"
                                         :new_string "replaced"}
                                        (make-context))]
        ;; whitespace-normalized should match "    foo bar" -> "replaced"
        (is (match? {:output #"Edited"} result))
        (let [new-content (slurp file-path)]
          (is (not (.contains ^String new-content "foo bar"))))))))

(deftest not-found-returns-anomaly-test
  (testing "returns anomaly with context when old_string not found"
    (let [file-path (str (fs/path *tmp-dir* "nf.txt"))]
      (spit file-path "alpha\nbeta\ngamma\ndelta\nepsilon")
      (is (match? {::anom/category ::anom/not-found
                   ::anom/message  #"old_string not found"}
                  (tool/execute-tool! "edit_file"
                                     {:path "nf.txt"
                                      :old_string "nonexistent text"
                                      :new_string "replacement"}
                                     (make-context)))))))

(deftest replaces-first-occurrence-test
  (testing "replaces only first occurrence when old_string appears multiple times"
    (let [file-path (str (fs/path *tmp-dir* "multi.txt"))]
      (spit file-path "aaa\nbbb\naaa\nbbb")
      (let [result (tool/execute-tool! "edit_file"
                                        {:path "multi.txt"
                                         :old_string "aaa"
                                         :new_string "zzz"}
                                        (make-context))]
        (is (match? {:output #"first occurrence"} result))
        (is (= "zzz\nbbb\naaa\nbbb" (slurp file-path)))))))

(deftest missing-file-returns-anomaly-test
  (testing "returns not-found anomaly for missing file"
    (is (match? {::anom/category ::anom/not-found}
                (tool/execute-tool! "edit_file"
                                   {:path "ghost.txt"
                                    :old_string "a"
                                    :new_string "b"}
                                   (make-context))))))

(deftest requires-dangerous-mode-test
  (testing "returns forbidden when dangerous mode is off"
    (let [file-path (str (fs/path *tmp-dir* "safe.txt"))]
      (spit file-path "content")
      (is (match? {::anom/category ::anom/forbidden}
                  (tool/execute-tool! "edit_file"
                                     {:path "safe.txt"
                                      :old_string "content"
                                      :new_string "new"}
                                     (make-context false)))))))

(deftest identical-strings-returns-anomaly-test
  (testing "returns incorrect anomaly when old_string equals new_string"
    (let [file-path (str (fs/path *tmp-dir* "id.txt"))]
      (spit file-path "content")
      (is (match? {::anom/category ::anom/incorrect
                   ::anom/message  #"identical"}
                  (tool/execute-tool! "edit_file"
                                     {:path "id.txt"
                                      :old_string "content"
                                      :new_string "content"}
                                     (make-context)))))))
