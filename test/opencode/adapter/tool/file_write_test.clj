(ns opencode.adapter.tool.file-write-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [babashka.fs :as fs]
   [cognitect.anomalies :as anom]
   [matcher-combinators.test :refer [match?]]
   [opencode.adapter.tool.file-write]
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
    (let [tmp (str (fs/create-temp-dir {:prefix "file-write-test"}))]
      (binding [*tmp-dir* tmp]
        (try (f) (finally (fs/delete-tree tmp)))))))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest write-new-file-test
  (testing "writes new file in temp dir"
    (let [result (tool/execute-tool! "write_file"
                                     {:path "new.txt" :content "hello world"}
                                     (make-context))
          file   (str (fs/path *tmp-dir* "new.txt"))]
      (is (match? {:output #"Wrote 11 bytes"} result))
      (is (= "hello world" (slurp file))))))

(deftest write-creates-parent-dirs-test
  (testing "creates parent directories"
    (let [result (tool/execute-tool! "write_file"
                                     {:path "deep/nested/dir/file.txt"
                                      :content "nested content"}
                                     (make-context))
          file   (str (fs/path *tmp-dir* "deep" "nested" "dir" "file.txt"))]
      (is (match? {:output #"Wrote 14 bytes"} result))
      (is (= "nested content" (slurp file))))))

(deftest write-overwrites-existing-test
  (testing "overwrites existing file"
    (let [file-path (str (fs/path *tmp-dir* "existing.txt"))]
      (spit file-path "old content")
      (let [result (tool/execute-tool! "write_file"
                                       {:path "existing.txt" :content "new content"}
                                       (make-context))]
        (is (match? {:output #"Wrote 11 bytes"} result))
        (is (= "new content" (slurp file-path)))))))

(deftest write-requires-dangerous-mode-test
  (testing "returns forbidden when dangerous mode is off"
    (is (match? {::anom/category ::anom/forbidden}
                (tool/execute-tool! "write_file"
                                   {:path "file.txt" :content "content"}
                                   (make-context false))))))

(deftest write-absolute-path-test
  (testing "writes to absolute path"
    (let [abs-path (str (fs/path *tmp-dir* "abs-write.txt"))
          result   (tool/execute-tool! "write_file"
                                       {:path abs-path :content "absolute"}
                                       (make-context))]
      (is (match? {:output #"Wrote 8 bytes"} result))
      (is (= "absolute" (slurp abs-path))))))
