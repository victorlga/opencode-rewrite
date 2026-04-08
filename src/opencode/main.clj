(ns opencode.main
  "Entry point for opencode-rewrite.
   Parses CLI arguments, starts the Integrant system, and runs an interactive
   REPL loop that accepts natural language input, calls Claude, executes tools,
   and iterates on results. THIS IS THE MVP."
  (:require
   [clojure.string :as str]
   [clojure.tools.cli :as cli]
   [cognitect.anomalies :as anom]
   [opencode.adapter.persistence :as persistence]
   [opencode.domain.message :as message]
   [opencode.domain.session :as session]
   [opencode.domain.tool :as tool]
   [opencode.logic.agent :as agent]
   [opencode.logic.ui :as ui]
   [opencode.system :as system]))

(def version "0.1.0-SNAPSHOT")

(def cli-options
  "CLI option specifications for tools.cli."
  [["-h" "--help" "Show this help message"]
   ["-v" "--version" "Print version and exit"]
   [nil "--dangerously-skip-permissions" "Skip all permission checks (YOLO mode)"]])

(defn- usage
  "Returns a usage summary string."
  [summary]
  (str "opencode-rewrite -- AI coding agent in Clojure\n\n"
       "Usage: clj -M -m opencode.main [options]\n\n"
       "Options:\n"
       summary))

;; ---------------------------------------------------------------------------
;; Special command handlers
;; ---------------------------------------------------------------------------

(defn- handle-new-session!
  "Creates a new session, saves it to the store, and returns it.
   Displays a confirmation message via the UI adapter."
  [model store ui]
  (let [new-session (session/create-session model)]
    (persistence/save-session! store new-session)
    (ui/display-text! ui (str "New session created: " (:session/id new-session)))
    new-session))

(defn- handle-list-sessions!
  "Lists all sessions from the store, displaying their IDs and titles."
  [store ui]
  (let [sessions (persistence/list-sessions store)]
    (if (empty? sessions)
      (ui/display-text! ui "No sessions found.")
      (doseq [s sessions]
        (ui/display-text! ui (str "  " (:session/id s) "  " (:session/title s)))))))

;; ---------------------------------------------------------------------------
;; Interactive REPL loop
;; ---------------------------------------------------------------------------

(defn- run-interactive-loop!
  "Runs the main interactive loop. Reads user input, dispatches special commands,
   or sends input to the agent loop for LLM processing.
   Returns nil on clean exit."
  [{:keys [provider session-store event-bus ui-adapter model project-dir dangerous-mode?]}]
  (let [initial-session (handle-new-session! model session-store ui-adapter)]
    (loop [current-session initial-session]
      (let [input (ui/get-input! ui-adapter "user> ")]
        (cond
          ;; EOF or interrupt — exit
          (nil? input)
          (ui/display-text! ui-adapter "Goodbye!")

          ;; Special commands
          (contains? #{"/quit" "/exit"} (str/trim input))
          (ui/display-text! ui-adapter "Goodbye!")

          (= "/new" (str/trim input))
          (recur (handle-new-session! model session-store ui-adapter))

          (= "/sessions" (str/trim input))
          (do
            (handle-list-sessions! session-store ui-adapter)
            (recur current-session))

          ;; Empty input — skip
          (str/blank? input)
          (recur current-session)

          ;; Normal input — send to agent loop
          :else
          (let [user-msg         (message/user-message (str/trim input))
                session-with-msg (session/append-message current-session user-msg)]
            ;; Guard: append-message can return an anomaly on validation failure
            (if (contains? session-with-msg ::anom/category)
              (do
                (ui/display-error! ui-adapter
                                   (str "Failed to add message: " (::anom/message session-with-msg)))
                (recur current-session))
              (let [tools   (tool/all-tools)
                    context {:ctx/session-id      (:session/id current-session)
                             :ctx/project-dir     project-dir
                             :ctx/dangerous-mode? dangerous-mode?}]
                (try
                  (let [result-session (agent/run-agent-loop!
                                        {:provider   provider
                                         :session    session-with-msg
                                         :tools      tools
                                         :event-bus  event-bus
                                         :ui-adapter ui-adapter
                                         :context    context})]
                    ;; Save updated session to store
                    (persistence/save-session! session-store result-session)
                    (recur result-session))
                  (catch Exception e
                    (ui/display-error! ui-adapter (str "Unexpected error: " (ex-message e)))
                    (recur current-session)))))))))))

;; ---------------------------------------------------------------------------
;; Entry point
;; ---------------------------------------------------------------------------

(defn -main
  "Application entry point. Parses CLI args, starts the Integrant system,
   and runs the interactive REPL loop."
  [& args]
  (let [{:keys [options summary errors]} (cli/parse-opts args cli-options)]
    (cond
      errors
      (do (binding [*out* *err*]
            (doseq [e errors]
              (println e)))
          (System/exit 1))

      (:help options)
      (println (usage summary))

      (:version options)
      (println (str "opencode-rewrite " version))

      :else
      (let [sys      (system/start!)
            stopped? (atom false)
            stop-once! (fn []
                         (when (compare-and-set! stopped? false true)
                           (system/stop! sys)))]
        ;; Register shutdown hook for clean teardown (guarded by stop-once!)
        (.addShutdownHook (Runtime/getRuntime)
                          (Thread. ^Runnable stop-once!))
        (try
          (let [config          (:opencode/config sys)
                provider        (:opencode/llm-provider sys)
                event-bus       (:opencode/event-bus sys)
                ui-adapter      (:opencode/ui sys)
                session-store   (:opencode/session-store sys)
                model           (:model (:llm config))
                project-dir     (or (:directory (:project config))
                                    (System/getProperty "user.dir"))
                dangerous-mode? (boolean (:dangerously-skip-permissions options))]
            (ui/display-text! ui-adapter
                              (str "opencode-rewrite v" version " | model: " model))
            (ui/display-text! ui-adapter
                              "Type your message, or /quit to exit, /new for new session, /sessions to list.")
            (run-interactive-loop!
              {:provider        provider
               :session-store   session-store
               :event-bus       event-bus
               :ui-adapter      ui-adapter
               :model           model
               :project-dir     project-dir
               :dangerous-mode? dangerous-mode?}))
          (finally
            (stop-once!)))))))

(comment
  ;; REPL exploration
  (-main)
  (-main "--help")
  (-main "--version")
  (-main "--dangerously-skip-permissions")
  ,)
