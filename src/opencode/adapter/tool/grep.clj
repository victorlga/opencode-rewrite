(ns opencode.adapter.tool.grep
  "GrepTool — searches file contents using ripgrep (with grep fallback).
   Returns matches in file:line:content format, truncated at 100 matches.
   Registered as \"grep\" in the tool registry on namespace load."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.string :as str]
   [cognitect.anomalies :as anom]
   [opencode.domain.tool :as tool]))

;; ---------------------------------------------------------------------------
;; Constants
;; ---------------------------------------------------------------------------

(def ^:private max-matches
  "Maximum number of grep results before truncation."
  100)

;; ---------------------------------------------------------------------------
;; Tool definition + registration
;; ---------------------------------------------------------------------------

(tool/register-tool!
  {:tool/name        "grep"
   :tool/description "Search file contents using a regular expression pattern."
   :tool/parameters  [:map
                      [:pattern :string]
                      [:path {:optional true} :string]
                      [:include {:optional true} :string]]
   :tool/dangerous?  false})

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(def ^:private ripgrep-available?
  "Delay that resolves to true if ripgrep (rg) is on the PATH, false otherwise.
   Memoized via delay — shells out to `which rg` only once."
  (delay
    (try
      (let [result (p/process ["which" "rg"] {:out :string :err :string})]
        (zero? (:exit @result)))
      (catch Exception _
        false))))

(defn- build-command
  "Builds the grep command vector. Prefers ripgrep if available."
  [pattern search-path include use-rg?]
  (if use-rg?
    (cond-> ["rg" "-n" "--no-heading" pattern]
      include (into ["--glob" include])
      true    (conj search-path))
    (cond-> ["grep" "-Ern" pattern]
      include (into ["--include" include])
      true    (conj search-path))))

(defn- parse-match-line
  "Parses a grep/rg output line into a map.
   Expected format: file:line:content"
  [line]
  (when-not (str/blank? line)
    (let [parts (str/split line #":" 3)]
      (when (>= (count parts) 3)
        (let [[file line-num content] parts
              parsed-num (try (Integer/parseInt line-num)
                              (catch NumberFormatException _ nil))]
          (when parsed-num
            {:file    file
             :line    parsed-num
             :content content}))))))

;; ---------------------------------------------------------------------------
;; Execution
;; ---------------------------------------------------------------------------

(defmethod tool/execute-tool! "grep"
  [_tool-name params context]
  (try
    (let [project-dir (:ctx/project-dir context)
          raw-path    (or (:path params) ".")
          search-path (if (fs/absolute? raw-path)
                        raw-path
                        (str (fs/path project-dir raw-path)))
          pattern     (:pattern params)
          include     (:include params)
          use-rg?     @ripgrep-available?
          cmd         (build-command pattern search-path include use-rg?)
          proc        (p/process cmd {:out :string :err :string})
          result      @proc
          exit-code   (:exit result)
          stdout      (:out result)]
      ;; Exit codes: 0 = matches found, 1 = no matches, 2+ = errors
      (cond
        ;; No matches
        (= exit-code 1)
        {:output "No matches found"}

        ;; Error with no output
        (and (>= exit-code 2) (str/blank? stdout))
        {::anom/category ::anom/fault
         ::anom/message  (str "grep failed (exit " exit-code "): " (:err result))}

        ;; Parse matches (exit 0, or exit 2 with partial output)
        :else
        (let [lines     (str/split-lines (str/trim stdout))
              matches   (keep parse-match-line lines)
              total     (count matches)
              truncated? (> total max-matches)
              display   (if truncated?
                          (take max-matches matches)
                          matches)
              formatted (map (fn [{:keys [file line content]}]
                               (str file ":" line ": " content))
                             display)
              output    (str/join "\n" formatted)]
          (if (str/blank? output)
            {:output "No matches found"}
            (if truncated?
              {:output (str output "\n\n(Results truncated: showing first "
                            max-matches " of " total
                            " matches. Use a more specific path or pattern.)")}
              {:output output})))))
    (catch Exception e
      {::anom/category ::anom/fault
       ::anom/message  (ex-message e)})))

(comment
  ;; REPL exploration
  (tool/execute-tool! "grep"
                      {:pattern "defmethod" :path "src"}
                      {:ctx/session-id      (java.util.UUID/randomUUID)
                       :ctx/project-dir     "."
                       :ctx/dangerous-mode? false})
  ,)
