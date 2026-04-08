(ns opencode.logic.ui
  "UI adapter protocol (port) for opencode.
   Defines the contract for all user-facing I/O. Business logic never calls
   println directly — it goes through this protocol (AGENTS.md compliance).
   Lives in logic layer because it's a port definition, not an adapter.
   MVP implementation: ReplUI (JLine readline). Future: TUI via event bus.")

(defprotocol UIAdapter
  (display-text! [this text]
    "Display text output to the user.")
  (display-tool-call! [this tool-name params]
    "Show the user which tool is being called with what params.")
  (display-tool-result! [this tool-name result]
    "Show the user the result of a tool call.")
  (display-error! [this error]
    "Show an error to the user.")
  (ask-permission! [this tool-name params]
    "Ask user permission for a tool call. Returns :approved or :denied.")
  (get-input! [this prompt]
    "Get text input from the user. Blocks until input received. Returns string."))
