(ns opencode.adapter.tool.file-read-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [babashka.fs :as fs]
   [cognitect.anomalies :as anom]
   [matcher-combinators.test :refer [match?]]
   [opencode.adapter.tool.file-read]
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
    (let [tmp (str (fs/create-temp-dir {:prefix "file-read-test"}))]
      (binding [*tmp-dir* tmp]
        (try (f) (finally (fs/delete-tree tmp)))))))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest read-normal-file-test
  (testing "reads a normal text file"
    (let [file-path (str (fs/path *tmp-dir* "hello.txt"))]
      (spit file-path "line one\nline two\nline three")
      (is (match? {:output "line one\nline two\nline three"}
                  (tool/execute-tool! "read_file"
                                     {:path "hello.txt"}
                                     (make-context)))))))

(deftest read-missing-file-test
  (testing "returns anomaly for missing file"
    (is (match? {::anom/category ::anom/not-found}
                (tool/execute-tool! "read_file"
                                   {:path "nonexistent.txt"}
                                   (make-context))))))

(deftest read-with-offset-and-limit-test
  (testing "line offset + limit works"
    (let [file-path (str (fs/path *tmp-dir* "lines.txt"))
          content   (apply str (interpose "\n" (map #(str "line " %) (range 1 11))))]
      (spit file-path content)
      (is (match? {:output "line 4\nline 5\nline 6"}
                  (tool/execute-tool! "read_file"
                                     {:path "lines.txt" :offset 3 :limit 3}
                                     (make-context)))))))

(deftest read-binary-file-test
  (testing "returns anomaly for binary file"
    (let [file-path (str (fs/path *tmp-dir* "binary.bin"))
          bytes     (byte-array (concat (.getBytes "hello" "UTF-8") [0 0 0] (.getBytes "world" "UTF-8")))]
      (with-open [os (java.io.FileOutputStream. file-path)]
        (.write os bytes))
      (is (match? {::anom/category ::anom/incorrect
                   ::anom/message  "Binary file detected"}
                  (tool/execute-tool! "read_file"
                                     {:path "binary.bin"}
                                     (make-context)))))))

(deftest read-truncation-test
  (testing "truncation at 50KB"
    (let [file-path (str (fs/path *tmp-dir* "large.txt"))
          ;; Create a file with > 50KB of content
          big-line  (apply str (repeat 1000 "x"))
          content   (apply str (interpose "\n" (repeat 100 big-line)))]
      (spit file-path content)
      (let [result (tool/execute-tool! "read_file"
                                       {:path "large.txt"}
                                       (make-context))]
        (is (string? (:output result)))
        (is (.contains ^String (:output result) "[Content truncated"))))))

(deftest read-absolute-path-test
  (testing "reads file by absolute path"
    (let [file-path (str (fs/path *tmp-dir* "abs.txt"))]
      (spit file-path "absolute content")
      (is (match? {:output "absolute content"}
                  (tool/execute-tool! "read_file"
                                     {:path file-path}
                                     (make-context)))))))

(deftest read-empty-file-test
  (testing "reads empty file without error"
    (let [file-path (str (fs/path *tmp-dir* "empty.txt"))]
      (spit file-path "")
      (is (match? {:output ""}
                  (tool/execute-tool! "read_file"
                                     {:path "empty.txt"}
                                     (make-context)))))))
