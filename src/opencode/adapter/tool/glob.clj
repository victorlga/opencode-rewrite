(ns opencode.adapter.tool.glob
  "GlobTool — finds files matching a glob pattern.
   Registered as \"glob\" in the tool registry on namespace load."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [cognitect.anomalies :as anom]
   [opencode.domain.tool :as tool]))

;; ---------------------------------------------------------------------------
;; Constants
;; ---------------------------------------------------------------------------

(def ^:private max-results
  "Maximum number of glob results before truncation."
  200)

;; ---------------------------------------------------------------------------
;; Tool definition + registration
;; ---------------------------------------------------------------------------

(tool/register-tool!
  {:tool/name        "glob"
   :tool/description "Find files matching a glob pattern."
   :tool/parameters  [:map
                      [:pattern :string]
                      [:path {:optional true} :string]]
   :tool/dangerous?  false})

;; ---------------------------------------------------------------------------
;; Execution
;; ---------------------------------------------------------------------------

(defmethod tool/execute-tool! "glob"
  [_tool-name params context]
  (try
    (let [project-dir (:ctx/project-dir context)
          raw-path    (or (:path params) ".")
          search-dir  (if (fs/absolute? raw-path)
                        raw-path
                        (str (fs/path project-dir raw-path)))
          pattern     (:pattern params)
          results     (fs/glob search-dir pattern)
          rel-paths   (->> results
                           (map #(str (fs/relativize search-dir %)))
                           (sort))
          truncated?  (> (count rel-paths) max-results)
          display     (if truncated?
                        (take max-results rel-paths)
                        rel-paths)]
      (if (empty? display)
        {:output "No files found"}
        (let [output (str/join "\n" display)]
          (if truncated?
            {:output (str output "\n\n(Results truncated: showing first "
                          max-results " of " (count rel-paths)
                          " results. Use a more specific path or pattern.)")}
            {:output output}))))
    (catch Exception e
      {::anom/category ::anom/fault
       ::anom/message  (ex-message e)})))

(comment
  ;; REPL exploration
  (tool/execute-tool! "glob"
                      {:pattern "**/*.clj"}
                      {:ctx/session-id      (java.util.UUID/randomUUID)
                       :ctx/project-dir     "."
                       :ctx/dangerous-mode? false})
  ,)
