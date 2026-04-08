(ns opencode.logic.prompt
  "System prompt construction for the agentic loop.
   Builds a system prompt string including role, environment info, available tools,
   and instructions. Inspired by the TypeScript SystemPrompt.environment function
   but simplified for MVP."
  (:require
   [clojure.string :as str])
  (:import
   (java.time LocalDate)))

;; ---------------------------------------------------------------------------
;; System prompt builder
;; ---------------------------------------------------------------------------

(defn build-system-prompt
  "Builds a system prompt string from config and available tools.
   Includes role, working directory, OS info, current date, tool listing,
   and concise instructions for the LLM."
  [_config tools]
  (let [cwd     (System/getProperty "user.dir")
        os-name (System/getProperty "os.name")
        os-ver  (System/getProperty "os.version")
        date    (str (LocalDate/now))
        tool-section (if (seq tools)
                       (str "## Available Tools\n\n"
                            (str/join "\n"
                                      (map (fn [t]
                                             (str "- **" (:tool/name t) "**: "
                                                  (:tool/description t)))
                                           tools)))
                       "## Available Tools\n\nNo tools available.")]
    (str/join "\n\n"
              ["# Role"
               "You are an AI coding assistant."
               "## Environment"
               (str "<env>\n"
                    "  Working directory: " cwd "\n"
                    "  Platform: " os-name " " os-ver "\n"
                    "  Today's date: " date "\n"
                    "</env>")
               tool-section
               "## Instructions"
               "Use tools to help the user. Be concise."])))

(comment
  ;; REPL exploration
  (build-system-prompt {} [])
  (build-system-prompt {} [{:tool/name "bash" :tool/description "Run shell commands"}
                           {:tool/name "read_file" :tool/description "Read a file"}])
  ,)
