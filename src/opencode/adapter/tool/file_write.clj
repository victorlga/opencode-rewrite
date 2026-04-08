(ns opencode.adapter.tool.file-write
  "WriteTool — creates or overwrites a file with given content.
   Creates parent directories if needed.
   Registered as \"write_file\" in the tool registry on namespace load."
  (:require
   [babashka.fs :as fs]
   [cognitect.anomalies :as anom]
   [opencode.domain.tool :as tool]))

;; ---------------------------------------------------------------------------
;; Tool definition + registration
;; ---------------------------------------------------------------------------

(tool/register-tool!
  {:tool/name        "write_file"
   :tool/description "Create or overwrite a file with the given content."
   :tool/parameters  [:map
                      [:path :string]
                      [:content :string]]
   :tool/dangerous?  true})

;; ---------------------------------------------------------------------------
;; Execution
;; ---------------------------------------------------------------------------

(defmethod tool/execute-tool! "write_file"
  [_tool-name params context]
  (let [project-dir (:ctx/project-dir context)
        raw-path    (:path params)
        abs-path    (if (fs/absolute? raw-path)
                      raw-path
                      (str (fs/path project-dir raw-path)))
        content     (:content params)]
    (if-not (:ctx/dangerous-mode? context)
      {::anom/category ::anom/forbidden
       ::anom/message  "write_file requires dangerous mode to be enabled"}
      (try
        (let [parent (fs/parent abs-path)]
          (when (and parent (not (fs/exists? parent)))
            (fs/create-dirs parent)))
        (spit abs-path content)
        {:output (str "Wrote " (count content) " bytes to " abs-path)}
        (catch Exception e
          {::anom/category ::anom/fault
           ::anom/message  (ex-message e)})))))

(comment
  ;; REPL exploration
  (tool/execute-tool! "write_file"
                      {:path "/tmp/test-write.txt" :content "hello world"}
                      {:ctx/session-id      (java.util.UUID/randomUUID)
                       :ctx/project-dir     "."
                       :ctx/dangerous-mode? true})
  ,)
