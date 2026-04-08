(ns opencode.logic.permission
  "Permission checking for tool execution.
   Implements a simple allow/ask rule table inspired by the TypeScript permission
   system. Safe tools (read-only) get :allow; dangerous tools (write/shell) get
   :ask. The --dangerously-skip-permissions flag overrides all to :allow.
   No persistence or multi-layer merge for MVP — just a flat lookup table."
  (:require
   [opencode.logic.ui :as ui]))

;; ---------------------------------------------------------------------------
;; Default permission rules
;; ---------------------------------------------------------------------------

(def default-rules
  "Default permission rules for each tool.
   :allow — tool runs without asking.
   :ask   — tool requires user approval before execution."
  {"read_file"  :allow
   "glob"       :allow
   "grep"       :allow
   "write_file" :ask
   "edit_file"  :ask
   "bash"       :ask})

;; ---------------------------------------------------------------------------
;; Permission checking
;; ---------------------------------------------------------------------------

(defn check-permission
  "Checks whether a tool is allowed to run.
   If dangerous-mode? is true in context, always returns :allow.
   Otherwise looks up tool-name in the rules table, defaulting to :ask."
  [tool-name context]
  (if (:ctx/dangerous-mode? context)
    :allow
    (get default-rules tool-name :ask)))

(defn request-permission!
  "Asks the user for permission to run a tool via the UI adapter.
   Returns :approved or :denied."
  [ui-adapter tool-name params]
  (ui/ask-permission! ui-adapter tool-name params))

(comment
  ;; REPL exploration
  (check-permission "read_file" {:ctx/dangerous-mode? false})
  ;; => :allow

  (check-permission "bash" {:ctx/dangerous-mode? false})
  ;; => :ask

  (check-permission "bash" {:ctx/dangerous-mode? true})
  ;; => :allow

  (check-permission "unknown_tool" {:ctx/dangerous-mode? false})
  ;; => :ask
  ,)
