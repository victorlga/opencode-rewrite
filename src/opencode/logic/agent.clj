(ns opencode.logic.agent
  "Core agentic loop for opencode.
   Takes a user message context, calls the LLM, consumes the stream,
   executes tool calls, and loops until the LLM finishes (no more tool calls).
   This is the brain of the application."
  (:require
   [clojure.core.async :as async]
   [cognitect.anomalies :as anom]
   [opencode.adapter.llm.provider :as llm]
   [opencode.domain.message :as message]
   [opencode.domain.session :as session]
   [opencode.domain.tool :as tool]
   [opencode.logic.event-bus :as event-bus]
   [opencode.logic.permission :as permission]
   [opencode.logic.prompt :as prompt]
   [opencode.logic.ui :as ui]))

;; ---------------------------------------------------------------------------
;; Constants
;; ---------------------------------------------------------------------------

(def ^:private max-iterations
  "Maximum number of LLM call iterations before forcing a stop.
   Prevents infinite tool-call loops."
  25)

(def ^:private stream-read-timeout-ms
  "Timeout in milliseconds when reading from the LLM stream channel."
  300000)

;; ---------------------------------------------------------------------------
;; Stream consumption
;; ---------------------------------------------------------------------------

(defn- consume-stream!
  "Reads events from the LLM stream channel, accumulating text and tool calls.
   Publishes :llm/stream-delta events to the event bus and displays text via UI.
   Returns a map with :text, :tool-calls, :finish-reason, and :error (if any)."
  [stream-ch event-bus ui-adapter]
  (loop [text-acc    (StringBuilder.)
         tool-calls  []]
    (let [timeout-ch (async/timeout stream-read-timeout-ms)
          [evt port] (async/alts!! [stream-ch timeout-ch])]
      (cond
        ;; Timeout — distinguish from normal channel close
        (and (nil? evt) (= port timeout-ch))
        {:text          (let [s (str text-acc)] (when-not (empty? s) s))
         :tool-calls    tool-calls
         :finish-reason :timeout
         :error         nil}

        ;; Channel closed — finalize with what we have
        (nil? evt)
        {:text          (let [s (str text-acc)] (when-not (empty? s) s))
         :tool-calls    tool-calls
         :finish-reason :stop
         :error         nil}

        ;; Error event from stream
        (= :error (:type evt))
        {:text          (let [s (str text-acc)] (when-not (empty? s) s))
         :tool-calls    tool-calls
         :finish-reason :error
         :error         (:error evt)}

        ;; Text delta — accumulate + publish + display
        (= :text-delta (:type evt))
        (do
          (.append text-acc (:text evt))
          (when event-bus
            (event-bus/publish! event-bus :llm/stream-delta {:text (:text evt)}))
          (when ui-adapter
            (ui/display-text! ui-adapter (:text evt)))
          (recur text-acc tool-calls))

        ;; Done event — extract assistant message
        (= :done (:type evt))
        (let [msg (:message evt)]
          {:text          (:message/content msg)
           :tool-calls    (or (:message/tool-calls msg) [])
           :finish-reason (:message/finish-reason msg)
           :error         nil})

        ;; Unknown event type — skip
        :else
        (recur text-acc tool-calls)))))

;; ---------------------------------------------------------------------------
;; Tool execution
;; ---------------------------------------------------------------------------

(defn- invoke-and-publish!
  "Invokes a tool, displays the result, publishes :tool/completed, and returns
   a tool-result message. Shared helper to avoid duplicating this logic across
   the :allow and :ask-then-:approved permission branches."
  [tool-name tool-id params context event-bus ui-adapter]
  (let [result      (tool/invoke-tool! tool-name params context)
        result-text (or (:output result) (pr-str result))]
    (when ui-adapter
      (ui/display-tool-result! ui-adapter tool-name result-text))
    (when event-bus
      (event-bus/publish! event-bus :tool/completed {:tool-name tool-name
                                                     :result    result}))
    (message/tool-result tool-id result-text)))

(defn- execute-tool-calls!
  "Executes a vector of tool calls, checking permissions and publishing events.
   Returns a vector of tool-result messages."
  [tool-calls context event-bus ui-adapter]
  (mapv
   (fn [tc]
     (let [tool-name (:tool-call/name tc)
           tool-id   (:tool-call/id tc)
           params    (:tool-call/arguments tc)]
       ;; Display the tool call
       (when ui-adapter
         (ui/display-tool-call! ui-adapter tool-name params))
       (when event-bus
         (event-bus/publish! event-bus :tool/executing {:tool-name tool-name
                                                        :params    params}))
       ;; Check permission
       (let [perm (permission/check-permission tool-name context)]
         (if (= :ask perm)
           ;; Need to ask user
           (let [answer (if ui-adapter
                          (permission/request-permission! ui-adapter tool-name params)
                          :denied)]
             (if (= :approved answer)
               (invoke-and-publish! tool-name tool-id params context event-bus ui-adapter)
               ;; Permission denied
               (do
                 (when event-bus
                   (event-bus/publish! event-bus :tool/completed {:tool-name tool-name
                                                                  :result    "Permission denied"}))
                 (message/tool-result tool-id "Permission denied"))))
           ;; Permission :allow — execute directly
           (invoke-and-publish! tool-name tool-id params context event-bus ui-adapter)))))
   tool-calls))

;; ---------------------------------------------------------------------------
;; Core agentic loop
;; ---------------------------------------------------------------------------

(defn run-agent-loop!
  "Core agentic loop. Sends messages to LLM, executes tool calls, loops until done.
   Returns the final session with all messages appended.
   Publishes events to the event bus throughout.

   Required keys in opts:
     :provider    — LLMProvider instance
     :session     — session map
     :tools       — vector of tool definition maps
     :event-bus   — event bus (or nil)
     :ui-adapter  — UIAdapter (or nil)
     :context     — tool context map (:ctx/session-id, :ctx/project-dir, :ctx/dangerous-mode?)"
  [{:keys [provider session tools event-bus ui-adapter context]}]
  (let [system-prompt  (prompt/build-system-prompt {} tools)
        api-tools      (tool/tools-for-api tools)
        max-tokens     4096
        latest-session (atom session)]
    (try
      (loop [current-session session
             iteration       0]
        (if (>= iteration max-iterations)
          ;; Max iterations reached — append a stop message
          (let [final-msg    (message/assistant-message
                               "I've reached the maximum number of iterations. Please review the results so far."
                               nil :stop)
                final-session (session/append-message current-session final-msg)]
            (when event-bus
              (event-bus/publish! event-bus :session/updated {:session-id (:session/id final-session)}))
            final-session)

          ;; Normal iteration — track latest session for error recovery
          (do
            (reset! latest-session current-session)
            (let [stream-ch  (llm/stream provider (session/get-messages current-session)
                                         {:system    system-prompt
                                          :tools     api-tools
                                          :max-tokens max-tokens})
                  result     (consume-stream! stream-ch event-bus ui-adapter)]
              ;; Handle error or timeout
              (if (or (:error result) (= :timeout (:finish-reason result)))
                (let [error-msg (message/assistant-message
                                  (or (:text result)
                                      (str "Error: " (::anom/message (:error result) "Unknown error")))
                                  nil :error)
                      err-session (session/append-message current-session error-msg)]
                  (when event-bus
                    (event-bus/publish! event-bus :llm/error {:error (:error result)}))
                  err-session)

                ;; Build assistant message from stream result
                (let [assistant-msg (message/assistant-message
                                      (:text result)
                                      (when (seq (:tool-calls result))
                                        (:tool-calls result))
                                      (:finish-reason result))
                      updated-session (session/append-message current-session assistant-msg)]
                  (when event-bus
                    (event-bus/publish! event-bus :session/updated
                                       {:session-id (:session/id updated-session)}))

                  (if (seq (:tool-calls result))
                    ;; Tool calls present — execute them, append results, loop
                    (let [tool-results (execute-tool-calls!
                                        (:tool-calls result) context event-bus ui-adapter)
                          session-with-results (reduce session/append-message
                                                       updated-session
                                                       tool-results)]
                      (recur session-with-results (inc iteration)))

                    ;; No tool calls — done!
                    updated-session)))))))
      (catch Exception e
        (when event-bus
          (event-bus/publish! event-bus :llm/error {:error (ex-message e)}))
        (let [error-msg (message/assistant-message
                          (str "Internal error: " (ex-message e))
                          nil :error)]
          (session/append-message @latest-session error-msg))))))

(comment
  ;; REPL exploration — requires a running system with provider, tools, etc.
  ;; (def sys (opencode.system/start!))
  ;; (def provider (:opencode/llm-provider sys))
  ;; (def bus (:opencode/event-bus sys))
  ;; (def ui (:opencode/ui sys))
  ;; (def s (session/create-session "claude-sonnet-4-20250514"))
  ;; (def s2 (session/append-message s (message/user-message "Hello!")))
  ;; (def tools (tool/all-tools))
  ;; (def ctx {:ctx/session-id (:session/id s2)
  ;;           :ctx/project-dir (System/getProperty "user.dir")
  ;;           :ctx/dangerous-mode? false})
  ;; (run-agent-loop! {:provider provider :session s2 :tools tools
  ;;                   :event-bus bus :ui-adapter ui :context ctx})
  ,)
