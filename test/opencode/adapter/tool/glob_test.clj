(ns opencode.adapter.tool.glob-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [babashka.fs :as fs]
   [matcher-combinators.test :refer [match?]]
   [opencode.adapter.tool.glob]
   [opencode.domain.tool :as tool])
  (:import
   (java.util UUID)))

;; ---------------------------------------------------------------------------
;; Temp directory fixture with file tree
;; ---------------------------------------------------------------------------

(def ^:dynamic *tmp-dir* nil)

(defn- make-context []
  {:ctx/session-id      (UUID/randomUUID)
   :ctx/project-dir     (str *tmp-dir*)
   :ctx/dangerous-mode? false})

(defn- create-test-tree!
  "Creates a test file tree in *tmp-dir*:
   ├── foo.clj
   ├── bar.clj
   ├── readme.md
   └── sub/
       ├── baz.clj
       ├── deep.md
       └── nested/
           └── inner.txt"
  []
  (spit (str (fs/path *tmp-dir* "foo.clj")) "(ns foo)")
  (spit (str (fs/path *tmp-dir* "bar.clj")) "(ns bar)")
  (spit (str (fs/path *tmp-dir* "readme.md")) "# Hello")
  (fs/create-dirs (fs/path *tmp-dir* "sub" "nested"))
  (spit (str (fs/path *tmp-dir* "sub" "baz.clj")) "(ns sub.baz)")
  (spit (str (fs/path *tmp-dir* "sub" "deep.md")) "# Deep")
  (spit (str (fs/path *tmp-dir* "sub" "nested" "inner.txt")) "inner"))

(use-fixtures :each
  (fn [f]
    (let [tmp (str (fs/create-temp-dir {:prefix "glob-test"}))]
      (binding [*tmp-dir* tmp]
        (create-test-tree!)
        (try (f) (finally (fs/delete-tree tmp)))))))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest glob-clj-pattern-test
  (testing "*.clj pattern finds .clj files in root"
    (let [result (tool/execute-tool! "glob"
                                     {:pattern "*.clj"}
                                     (make-context))]
      (is (string? (:output result)))
      (is (.contains ^String (:output result) "bar.clj"))
      (is (.contains ^String (:output result) "foo.clj")))))

(deftest glob-recursive-md-test
  (testing "**/*.md pattern finds nested .md files"
    (let [result (tool/execute-tool! "glob"
                                     {:pattern "**/*.md"}
                                     (make-context))]
      (is (string? (:output result)))
      ;; **/*.md matches files in subdirectories (Java NIO glob semantics)
      (is (.contains ^String (:output result) "deep.md")))))

(deftest glob-no-match-test
  (testing "returns empty for non-matching pattern"
    (is (match? {:output "No files found"}
                (tool/execute-tool! "glob"
                                   {:pattern "*.xyz"}
                                   (make-context))))))

(deftest glob-subdirectory-test
  (testing "search in subdirectory with :path option"
    (let [result (tool/execute-tool! "glob"
                                     {:pattern "*.clj" :path "sub"}
                                     (make-context))]
      (is (string? (:output result)))
      (is (.contains ^String (:output result) "baz.clj"))
      (is (not (.contains ^String (:output result) "foo.clj"))))))

(deftest glob-recursive-all-test
  (testing "**/* pattern finds all files recursively"
    (let [result (tool/execute-tool! "glob"
                                     {:pattern "**/*"}
                                     (make-context))
          output (:output result)]
      ;; **/* matches files in subdirectories (Java NIO glob semantics)
      (is (.contains ^String output "baz.clj"))
      (is (.contains ^String output "inner.txt")))))
