(ns opencode.adapter.tool.file-read
  "ReadTool — reads file contents with optional line offset/limit,
   binary detection, and truncation at 50KB.
   Registered as \"read_file\" in the tool registry on namespace load."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [cognitect.anomalies :as anom]
   [opencode.domain.tool :as tool]))

;; ---------------------------------------------------------------------------
;; Constants
;; ---------------------------------------------------------------------------

(def ^:private max-bytes
  "Maximum output size in bytes before truncation."
  (* 50 1024))

;; ---------------------------------------------------------------------------
;; Binary detection
;; ---------------------------------------------------------------------------

(defn- binary-file?
  "Returns true if the file appears to be binary.
   Checks the first 8KB for null bytes."
  [path]
  (let [f    (fs/file path)
        size (.length f)
        sample-size (min 8192 size)]
    (when (pos? sample-size)
      (with-open [is (java.io.FileInputStream. f)]
        (let [buf (byte-array sample-size)
              n   (.read is buf)]
          (when (pos? n)
            (loop [i 0]
              (if (>= i n)
                false
                (if (zero? (aget buf i))
                  true
                  (recur (inc i)))))))))))

;; ---------------------------------------------------------------------------
;; Tool definition + registration
;; ---------------------------------------------------------------------------

(tool/register-tool!
  {:tool/name        "read_file"
   :tool/description "Read the contents of a file. Returns file content as text."
   :tool/parameters  [:map
                      [:path :string]
                      [:offset {:optional true} :int]
                      [:limit {:optional true} :int]]
   :tool/dangerous?  false})

;; ---------------------------------------------------------------------------
;; Execution
;; ---------------------------------------------------------------------------

(defmethod tool/execute-tool! "read_file"
  [_tool-name params context]
  (let [project-dir (:ctx/project-dir context)
        raw-path    (:path params)
        abs-path    (if (fs/absolute? raw-path)
                      raw-path
                      (str (fs/path project-dir raw-path)))]
    (cond
      ;; File doesn't exist
      (not (fs/exists? abs-path))
      {::anom/category ::anom/not-found
       ::anom/message  (str "File not found: " abs-path)}

      ;; Binary file
      (binary-file? abs-path)
      {::anom/category ::anom/incorrect
       ::anom/message  "Binary file detected"}

      :else
      (let [content   (slurp abs-path)
            all-lines (str/split-lines content)
            offset    (or (:offset params) 0)
            limit     (or (:limit params) (count all-lines))
            selected  (->> all-lines
                          (drop offset)
                          (take limit))
            output    (str/join "\n" selected)
            truncated? (> (count (.getBytes output "UTF-8")) max-bytes)]
        (if truncated?
          (let [byte-arr (.getBytes output "UTF-8")
                ;; Walk backwards from max-bytes to find a valid UTF-8 boundary.
                ;; A continuation byte has the form 10xxxxxx (top 2 bits = 0x80).
                ;; Stop at the first byte that is NOT a continuation byte.
                safe-end (loop [i max-bytes]
                           (if (<= i 0)
                             0
                             (let [b (bit-and (aget byte-arr i) 0xFF)]
                               (if (not= (bit-and b 0xC0) 0x80)
                                 i
                                 (recur (dec i))))))
                truncated-output (String. byte-arr 0 safe-end "UTF-8")]
            {:output (str truncated-output
                          "\n[Content truncated. Use grep to search or read_file with offset/limit.]")})
          {:output output})))))

(comment
  ;; REPL exploration
  (tool/execute-tool! "read_file"
                      {:path "deps.edn"}
                      {:ctx/session-id      (java.util.UUID/randomUUID)
                       :ctx/project-dir     "."
                       :ctx/dangerous-mode? false})
  ,)
