(ns opencode.adapter.tool.file-edit
  "EditTool — search/replace with fuzzy matching.
   Tries exact match first, then whitespace-normalized, then line-trimmed.
   Replaces only the first occurrence unless old-string appears once.
   Registered as \"edit_file\" in the tool registry on namespace load."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [cognitect.anomalies :as anom]
   [opencode.domain.tool :as tool]))

;; ---------------------------------------------------------------------------
;; Tool definition + registration
;; ---------------------------------------------------------------------------

(tool/register-tool!
  {:tool/name        "edit_file"
   :tool/description "Replace exact text in a file. Searches for old_string and replaces with new_string."
   :tool/parameters  [:map
                      [:path :string]
                      [:old_string :string]
                      [:new_string :string]]
   :tool/dangerous?  true})

;; ---------------------------------------------------------------------------
;; Matching strategies
;; ---------------------------------------------------------------------------

(defn- exact-match
  "Returns the index of old-str in content, or nil if not found."
  [content old-str]
  (str/index-of content old-str))

(defn- whitespace-normalized-match
  "Finds matching lines in content where each line matches old-str's lines
   after trimming. Returns [start-idx end-idx] of the original content
   that corresponds to the match, or nil."
  [content old-str]
  (let [content-lines (str/split-lines content)
        search-lines  (str/split-lines old-str)
        search-norm   (mapv str/trim search-lines)
        search-count  (count search-lines)]
    (when (pos? search-count)
      (loop [i 0]
        (when (<= (+ i search-count) (count content-lines))
          (let [candidate (subvec (vec content-lines) i (+ i search-count))
                cand-norm (mapv str/trim candidate)]
            (if (= cand-norm search-norm)
              ;; Found — compute char offsets in original content
              (let [before-lines (take i content-lines)
                    start-idx    (if (zero? i)
                                   0
                                   (+ (reduce + (map count before-lines))
                                      i)) ;; +i for newlines
                    matched-text (str/join "\n" candidate)
                    end-idx      (+ start-idx (count matched-text))]
                [start-idx end-idx])
              (recur (inc i)))))))))

(defn- find-nearest-lines
  "Returns a context snippet of the 5 lines most similar to the start
   of old-str, for inclusion in the error message."
  [content old-str]
  (let [content-lines  (str/split-lines content)
        search-first   (str/trim (first (str/split-lines old-str)))
        scored         (map-indexed
                         (fn [idx line]
                           {:line-num (inc idx)
                            :text     line
                            :score    (if (str/includes? (str/lower-case line)
                                                        (str/lower-case search-first))
                                        1.0
                                        0.0)})
                         content-lines)
        best           (->> scored
                            (sort-by :score >)
                            (take 5)
                            (sort-by :line-num))]
    (str/join "\n" (map (fn [{:keys [line-num text]}]
                          (str "  " line-num ": " text))
                        best))))

;; ---------------------------------------------------------------------------
;; Execution
;; ---------------------------------------------------------------------------

(defmethod tool/execute-tool! "edit_file"
  [_tool-name params context]
  (let [project-dir (:ctx/project-dir context)
        raw-path    (:path params)
        abs-path    (if (fs/absolute? raw-path)
                      raw-path
                      (str (fs/path project-dir raw-path)))
        old-str     (:old_string params)
        new-str     (:new_string params)]
    (if-not (:ctx/dangerous-mode? context)
      {::anom/category ::anom/forbidden
       ::anom/message  "edit_file requires dangerous mode to be enabled"}
      (try
        (cond
          ;; File doesn't exist
          (not (fs/exists? abs-path))
          {::anom/category ::anom/not-found
           ::anom/message  (str "File not found: " abs-path)}

          ;; old_string same as new_string — no-op
          (= old-str new-str)
          {::anom/category ::anom/incorrect
           ::anom/message  "No changes to apply: old_string and new_string are identical."}

          :else
          (let [content (slurp abs-path)]
            (cond
              ;; Strategy 1: exact match
              (exact-match content old-str)
              (let [idx       (str/index-of content old-str)
                    last-idx  (str/last-index-of content old-str)
                    unique?   (= idx last-idx)]
                (if unique?
                  (let [new-content (str (subs content 0 idx)
                                        new-str
                                        (subs content (+ idx (count old-str))))]
                    (spit abs-path new-content)
                    {:output (str "Edited " abs-path ": replaced " (count old-str) " chars")})
                  ;; Multiple matches — replace first occurrence
                  (let [new-content (str (subs content 0 idx)
                                        new-str
                                        (subs content (+ idx (count old-str))))]
                    (spit abs-path new-content)
                    {:output (str "Edited " abs-path ": replaced first occurrence of "
                                  (count old-str) " chars (multiple matches found)")})))

              ;; Strategy 2: whitespace-normalized match (trim each line)
              :else
              (if-let [[start end] (whitespace-normalized-match content old-str)]
                (let [new-content (str (subs content 0 start)
                                      new-str
                                      (subs content end))]
                  (spit abs-path new-content)
                  {:output (str "Edited " abs-path ": replaced " (count old-str)
                                " chars (matched after whitespace normalization)")})
                ;; Strategy 3: not found — return anomaly with context
                (let [snippet (find-nearest-lines content old-str)]
                  {::anom/category ::anom/not-found
                   ::anom/message  (str "old_string not found in " abs-path
                                        ". Nearest lines:\n" snippet)})))))
        (catch Exception e
          {::anom/category ::anom/fault
           ::anom/message  (ex-message e)})))))

(comment
  ;; REPL exploration
  (tool/execute-tool! "edit_file"
                      {:path "/tmp/test-edit.txt"
                       :old_string "hello"
                       :new_string "world"}
                      {:ctx/session-id      (java.util.UUID/randomUUID)
                       :ctx/project-dir     "."
                       :ctx/dangerous-mode? true})
  ,)
