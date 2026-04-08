(ns opencode.adapter.tool.bash
  "BashTool — executes shell commands via babashka.process.
   Returns combined stdout+stderr, truncated at 50KB.
   Non-zero exit is NOT an anomaly (often informative).
   Registered as \"bash\" in the tool registry on namespace load."
  (:require
   [babashka.process :as p]
   [clojure.string :as str]
   [cognitect.anomalies :as anom]
   [opencode.domain.tool :as tool]))

;; ---------------------------------------------------------------------------
;; Constants
;; ---------------------------------------------------------------------------

(def ^:private default-timeout-ms
  "Default command timeout in milliseconds (2 minutes)."
  120000)

(def ^:private max-output-bytes
  "Maximum combined output size in bytes before truncation."
  (* 50 1024))

;; ---------------------------------------------------------------------------
;; Tool definition + registration
;; ---------------------------------------------------------------------------

(tool/register-tool!
  {:tool/name        "bash"
   :tool/description "Execute a shell command and return its output."
   :tool/parameters  [:map
                      [:command :string]
                      [:timeout {:optional true} :int]]
   :tool/dangerous?  true})

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- truncate-output
  "Truncates output to max-output-bytes at a UTF-8 safe boundary."
  [^String output]
  (let [byte-arr (.getBytes output "UTF-8")]
    (if (<= (alength byte-arr) max-output-bytes)
      output
      (let [safe-end (loop [i max-output-bytes]
                       (if (<= i 0)
                         0
                         (let [b (bit-and (aget byte-arr i) 0xFF)]
                           (if (not= (bit-and b 0xC0) 0x80)
                             i
                             (recur (dec i))))))
            truncated (String. byte-arr 0 safe-end "UTF-8")]
        (str truncated "\n[Output truncated at 50KB]")))))

;; ---------------------------------------------------------------------------
;; Execution
;; ---------------------------------------------------------------------------

(defmethod tool/execute-tool! "bash"
  [_tool-name params context]
  (if-not (:ctx/dangerous-mode? context)
    {::anom/category ::anom/forbidden
     ::anom/message  "bash requires dangerous mode to be enabled"}
    (try
      (let [command    (:command params)
            timeout-ms (or (:timeout params) default-timeout-ms)
            project-dir (:ctx/project-dir context)
            proc       (p/process ["bash" "-c" command]
                                  {:out :string
                                   :err :string
                                   :dir project-dir})
            result     (deref proc timeout-ms ::timeout)]
        (if (= result ::timeout)
          (do
            (p/destroy proc)
            {::anom/category ::anom/busy
             ::anom/message  (str "Command timed out after " timeout-ms "ms")})
          (let [stdout   (:out result)
                stderr   (:err result)
                exit     (:exit result)
                combined (cond
                           (and (not (str/blank? stdout))
                                (not (str/blank? stderr)))
                           (str stdout "\n" stderr)

                           (not (str/blank? stdout))
                           stdout

                           (not (str/blank? stderr))
                           stderr

                           :else "")
                output   (truncate-output combined)]
            {:output    output
             :exit-code exit})))
      (catch Exception e
        {::anom/category ::anom/fault
         ::anom/message  (ex-message e)}))))

(comment
  ;; REPL exploration
  (tool/execute-tool! "bash"
                      {:command "echo hello"}
                      {:ctx/session-id      (java.util.UUID/randomUUID)
                       :ctx/project-dir     "."
                       :ctx/dangerous-mode? true})

  (tool/execute-tool! "bash"
                      {:command "exit 1"}
                      {:ctx/session-id      (java.util.UUID/randomUUID)
                       :ctx/project-dir     "."
                       :ctx/dangerous-mode? true})
  ,)
