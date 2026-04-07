(ns opencode.adapter.llm.anthropic
  "Anthropic Messages API implementation of the LLMProvider protocol.
   Converts between our domain message format and the Anthropic wire format,
   makes HTTP calls via hato, and parses streaming SSE responses via core.async.

   This is the first adapter that makes real HTTP calls to an external service."
  (:require
   [clojure.core.async :as async]
   [clojure.string :as str]
   [cognitect.anomalies :as anom]
   [hato.client :as hc]
   [integrant.core :as ig]
   [jsonista.core :as json]
   [malli.core :as m]
   [malli.error :as me]
   [opencode.adapter.llm.model-registry :as registry]
   [opencode.adapter.llm.provider :as provider]
   [opencode.domain.message :as message]
   [opencode.logic.streaming :as streaming])
  (:import
   (java.io BufferedReader InputStreamReader)))

;; ---------------------------------------------------------------------------
;; JSON encoding
;; ---------------------------------------------------------------------------

(def ^:private object-mapper
  "Shared jsonista ObjectMapper configured for keyword keys."
  (json/object-mapper {:decode-key-fn keyword}))

(def ^:private api-url
  "Anthropic Messages API endpoint."
  "https://api.anthropic.com/v1/messages")

(def ^:private api-version
  "Anthropic API version header value."
  "2023-06-01")

;; ---------------------------------------------------------------------------
;; Message format conversion: domain -> Anthropic wire format
;; ---------------------------------------------------------------------------

(defn- tool-call->content-block
  "Converts a domain ToolCall map to an Anthropic tool_use content block."
  [tool-call]
  {:type  "tool_use"
   :id    (:tool-call/id tool-call)
   :name  (:tool-call/name tool-call)
   :input (:tool-call/arguments tool-call)})

(defn- convert-message
  "Converts a single domain message to Anthropic wire format.
   System messages are filtered out upstream (they go in the top-level system param).
   Returns nil for system messages."
  [msg]
  (case (:message/role msg)
    :system nil

    :user
    {:role "user" :content (:message/content msg)}

    :assistant
    (let [content (if-let [tool-calls (seq (:message/tool-calls msg))]
                    (cond-> []
                      (:message/content msg)
                      (conj {:type "text" :text (:message/content msg)})

                      true
                      (into (map tool-call->content-block) tool-calls))
                    (:message/content msg))]
      {:role "assistant" :content content})

    :tool
    {:role    "user"
     :content [{:type        "tool_result"
                :tool_use_id (:message/tool-call-id msg)
                :content     (:message/content msg)}]}))

(defn- merge-consecutive-same-role
  "Merges consecutive messages with the same :role into single messages.
   Required because the Anthropic API enforces strict user/assistant turn alternation,
   but our domain model produces separate tool-result messages (each with role \"user\")
   when an assistant invokes multiple tools. Consecutive same-role messages have their
   :content arrays concatenated (string content is wrapped in a vector first)."
  [msgs]
  (reduce
   (fn [acc msg]
     (let [prev (peek acc)]
       (if (and prev (= (:role prev) (:role msg)))
         (let [prev-content (if (vector? (:content prev))
                              (:content prev)
                              [{:type "text" :text (:content prev)}])
               msg-content  (if (vector? (:content msg))
                              (:content msg)
                              [{:type "text" :text (:content msg)}])]
           (conj (pop acc) (assoc prev :content (into prev-content msg-content))))
         (conj acc msg))))
   []
   msgs))

(defn messages->anthropic
  "Converts a vector of domain messages to Anthropic wire format.
   Returns a map with :messages (converted messages, system filtered out)
   and :system (system message content string, or nil).
   Consecutive same-role messages are merged to satisfy Anthropic's turn alternation."
  [messages]
  (let [system-msg  (first (filter #(= :system (:message/role %)) messages))
        other-msgs  (remove #(= :system (:message/role %)) messages)]
    {:messages (merge-consecutive-same-role
                (into [] (keep convert-message) other-msgs))
     :system   (:message/content system-msg)}))

;; ---------------------------------------------------------------------------
;; Response conversion: Anthropic wire format -> domain
;; ---------------------------------------------------------------------------

(def ^:private stop-reason-mapping
  "Maps Anthropic stop_reason strings to our domain finish-reason keywords."
  {"end_turn"   :stop
   "tool_use"   :tool-calls
   "max_tokens" :length})

(defn- content-block->tool-call
  "Converts an Anthropic tool_use content block to a domain ToolCall map."
  [block]
  {:tool-call/id        (:id block)
   :tool-call/name      (:name block)
   :tool-call/arguments (or (:input block) {})})

(defn anthropic-response->message
  "Parses an Anthropic Messages API response body into a domain assistant message.
   Extracts text content, tool calls, and maps the stop_reason."
  [response]
  (let [content-blocks (:content response)
        text-blocks    (filter #(= "text" (:type %)) content-blocks)
        tool-blocks    (filter #(= "tool_use" (:type %)) content-blocks)
        text           (when (seq text-blocks)
                         (str/join "" (map :text text-blocks)))
        tool-calls     (when (seq tool-blocks)
                         (mapv content-block->tool-call tool-blocks))
        stop-reason    (get stop-reason-mapping
                            (:stop_reason response)
                            :error)]
    (message/assistant-message text tool-calls stop-reason)))

;; ---------------------------------------------------------------------------
;; HTTP helpers
;; ---------------------------------------------------------------------------

(defn- status->anomaly-category
  "Maps an HTTP status code to a cognitect anomaly category."
  [status]
  (cond
    (= 401 status) ::anom/forbidden
    (= 403 status) ::anom/forbidden
    (= 429 status) ::anom/busy
    (>= status 500) ::anom/fault
    :else           ::anom/incorrect))

(defn- request-headers
  "Returns the HTTP headers map for Anthropic API requests."
  [api-key]
  {"x-api-key"         api-key
   "anthropic-version"  api-version
   "content-type"       "application/json"})

(defn- build-request-body
  "Builds the Anthropic API request body map from messages and options."
  [model-id messages opts]
  (let [{:keys [messages system]} (messages->anthropic messages)]
    (cond-> {:model      model-id
             :messages   messages
             :max_tokens (or (:max-tokens opts) 4096)}
      system               (assoc :system system)
      (:tools opts)        (assoc :tools (:tools opts))
      (:temperature opts)  (assoc :temperature (:temperature opts)))))

;; ---------------------------------------------------------------------------
;; Stream event processing
;; ---------------------------------------------------------------------------

(defn- process-stream-events!
  "Consumes SSE events from sse-ch, transforms them into domain stream events,
   and puts them onto out-ch. Accumulates tool call arguments across deltas.
   Closes out-ch when done.

   Domain stream event types:
     :text-delta    — {:type :text-delta :text \"...\"}
     :done          — {:type :done :message <assistant-message>}
                       (tool calls are accumulated and included in the :done message)
     :error         — {:type :error :error <anomaly-map>}"
  [sse-ch out-ch]
  (async/thread
    (try
      (loop [text-acc      (StringBuilder.)
             tool-calls    []
             current-tool  nil
             tool-args-acc (StringBuilder.)
             stop-reason   :stop]
        (let [[evt _] (async/alts!! [sse-ch (async/timeout 300000)])]
          (cond
            ;; Timeout or channel closed — finalize
            (nil? evt)
            (let [final-tool-calls (if current-tool
                                    (conj tool-calls
                                          (assoc current-tool
                                                 :tool-call/arguments
                                                 (try
                                                   (json/read-value (str tool-args-acc) object-mapper)
                                                   (catch Exception _ {}))))
                                    tool-calls)
                  text             (let [s (str text-acc)]
                                    (when-not (str/blank? s) s))
                  msg              (message/assistant-message
                                    text
                                    (when (seq final-tool-calls) final-tool-calls)
                                    stop-reason)]
              (async/>!! out-ch {:type :done :message msg}))

            ;; Anomaly from SSE parser
            (::anom/category evt)
            (async/>!! out-ch {:type :error :error evt})

            ;; Normal SSE event
            :else
            (let [sse-type (:sse/type evt)
                  data     (:sse/data evt)]
              (case sse-type
                :content-block-delta
                (let [delta (:delta data)]
                  (case (:type delta)
                    "text_delta"
                    (do
                      (.append text-acc (:text delta))
                      (when (async/>!! out-ch {:type :text-delta :text (:text delta)})
                        (recur text-acc tool-calls current-tool tool-args-acc stop-reason)))

                    "input_json_delta"
                    (do
                      (.append tool-args-acc (:partial_json delta))
                      (recur text-acc tool-calls current-tool tool-args-acc stop-reason))

                    ;; Unknown delta type — skip
                    (recur text-acc tool-calls current-tool tool-args-acc stop-reason)))

                :content-block-start
                (let [block (:content_block data)]
                  (if (= "tool_use" (:type block))
                    ;; Finalize previous tool if any, start new one
                    (let [updated-tools (if current-tool
                                          (conj tool-calls
                                                (assoc current-tool
                                                       :tool-call/arguments
                                                       (try
                                                         (json/read-value (str tool-args-acc) object-mapper)
                                                         (catch Exception _ {}))))
                                          tool-calls)
                          new-tool {:tool-call/id   (:id block)
                                    :tool-call/name (:name block)}]
                      (recur text-acc updated-tools new-tool (StringBuilder.) stop-reason))
                    ;; Non-tool content block start — just continue
                    (recur text-acc tool-calls current-tool tool-args-acc stop-reason)))

                :content-block-stop
                (recur text-acc tool-calls current-tool tool-args-acc stop-reason)

                :message-delta
                (let [new-stop (get stop-reason-mapping
                                   (get-in data [:delta :stop_reason])
                                   stop-reason)]
                  (recur text-acc tool-calls current-tool tool-args-acc new-stop))

                :message-start
                (recur text-acc tool-calls current-tool tool-args-acc stop-reason)

                :message-stop
                ;; Stream complete — finalize
                (let [final-tool-calls (if current-tool
                                         (conj tool-calls
                                               (assoc current-tool
                                                      :tool-call/arguments
                                                      (try
                                                        (json/read-value (str tool-args-acc) object-mapper)
                                                        (catch Exception _ {}))))
                                         tool-calls)
                      text             (let [s (str text-acc)]
                                         (when-not (str/blank? s) s))
                      msg              (message/assistant-message
                                         text
                                         (when (seq final-tool-calls) final-tool-calls)
                                         stop-reason)]
                  (async/>!! out-ch {:type :done :message msg}))

                :error
                (async/>!! out-ch {:type  :error
                                   :error {::anom/category ::anom/fault
                                           ::anom/message  (get-in data [:error :message]
                                                                   "Unknown streaming error")}})

                ;; Unknown event type — skip
                (recur text-acc tool-calls current-tool tool-args-acc stop-reason))))))
      (catch Exception e
        (async/>!! out-ch {:type  :error
                           :error {::anom/category ::anom/fault
                                   ::anom/message  (ex-message e)}}))
      (finally
        (async/close! sse-ch)
        (async/close! out-ch)))))

;; ---------------------------------------------------------------------------
;; Input validation (AGENTS.md: "NEVER skip Malli validation at adapter boundaries")
;; ---------------------------------------------------------------------------

(def ^:private Messages
  "Schema for the messages vector passed to complete/stream."
  [:vector message/Message])

(defn- validate-inputs
  "Validates messages and opts at the adapter boundary. Returns nil if valid,
   or an anomaly map if validation fails."
  [messages opts]
  (cond
    (not (m/validate Messages messages))
    {::anom/category ::anom/incorrect
     ::anom/message  "Invalid messages"
     :errors         (me/humanize (m/explain Messages messages))}

    (and (some? opts) (not (m/validate provider/CompletionOpts opts)))
    {::anom/category ::anom/incorrect
     ::anom/message  "Invalid completion options"
     :errors         (me/humanize (m/explain provider/CompletionOpts opts))}))

;; ---------------------------------------------------------------------------
;; AnthropicProvider record + LLMProvider implementation
;; ---------------------------------------------------------------------------

(defrecord AnthropicProvider [api-key model-id http-client]
  provider/LLMProvider

  (complete [_this messages opts]
    (if-let [validation-error (validate-inputs messages opts)]
      validation-error
      (try
        (let [body     (build-request-body model-id messages opts)
              json-body (json/write-value-as-string body)
              response (hc/post api-url
                                {:headers      (request-headers api-key)
                                 :body         json-body
                                 :http-client  http-client
                                 :as           :string
                                 :throw-exceptions? false})
              status   (:status response)]
          (if (<= 200 status 299)
            (let [parsed (json/read-value (:body response) object-mapper)]
              (anthropic-response->message parsed))
            {::anom/category (status->anomaly-category status)
             ::anom/message  (str "Anthropic API error (HTTP " status ")")
             :http/status    status
             :http/body      (:body response)}))
        (catch Exception e
          {::anom/category ::anom/fault
           ::anom/message  (ex-message e)}))))

  (stream [_this messages opts]
    (if-let [validation-error (validate-inputs messages opts)]
      (let [err-ch (async/chan 1)]
        (async/put! err-ch {:type :error :error validation-error})
        (async/close! err-ch)
        err-ch)
      (try
        (let [body       (assoc (build-request-body model-id messages opts)
                                :stream true)
              json-body  (json/write-value-as-string body)
              response   (hc/post api-url
                                  {:headers      (request-headers api-key)
                                   :body         json-body
                                   :http-client  http-client
                                   :as           :stream
                                   :throw-exceptions? false})
              status     (:status response)]
          (if (<= 200 status 299)
            (let [reader  (BufferedReader. (InputStreamReader. (:body response) "UTF-8"))
                  sse-ch  (streaming/sse-events->channel! reader)
                  out-ch  (async/chan (async/buffer 64))]
              (process-stream-events! sse-ch out-ch)
              out-ch)
            (let [err-ch (async/chan 1)
                  body-str (slurp (:body response))]
              (async/put! err-ch {:type  :error
                                  :error {::anom/category (status->anomaly-category status)
                                          ::anom/message  (str "Anthropic API error (HTTP " status ")")
                                          :http/status    status
                                          :http/body      body-str}})
              (async/close! err-ch)
              err-ch)))
        (catch Exception e
          (let [err-ch (async/chan 1)]
            (async/put! err-ch {:type  :error
                                :error {::anom/category ::anom/fault
                                        ::anom/message  (ex-message e)}})
            (async/close! err-ch)
            err-ch)))))

  (list-models [_this]
    (registry/models-for-provider :anthropic)))

;; ---------------------------------------------------------------------------
;; Constructor
;; ---------------------------------------------------------------------------

(defn make-provider
  "Creates an AnthropicProvider instance.
   api-key: Anthropic API key string.
   model-id: Model identifier string (e.g., \"claude-sonnet-4-20250514\").
   http-client: (optional) A hato HttpClient instance. If not provided, builds a default one."
  ([api-key model-id]
   (make-provider api-key model-id (hc/build-http-client {:connect-timeout 30000
                                                          :redirect-policy :normal})))
  ([api-key model-id http-client]
   (->AnthropicProvider api-key model-id http-client)))

;; ---------------------------------------------------------------------------
;; Integrant component
;; ---------------------------------------------------------------------------

(defmethod ig/init-key :opencode/llm-provider [_ {:keys [config]}]
  (let [{:keys [api-key model]} (:llm config)]
    (make-provider api-key model)))

(comment
  ;; REPL exploration — message conversion
  (messages->anthropic
   [(message/system-message "You are helpful.")
    (message/user-message "Hello!")
    (message/assistant-message "Hi! How can I help?" nil :stop)
    (message/user-message "What's 2+2?")])

  ;; Response parsing
  (anthropic-response->message
   {:content     [{:type "text" :text "Hello!"}]
    :stop_reason "end_turn"})

  (anthropic-response->message
   {:content     [{:type "tool_use" :id "tc_1" :name "bash" :input {:command "ls"}}]
    :stop_reason "tool_use"})

  ;; Manual REPL test (requires ANTHROPIC_API_KEY env var)
  ;; (def p (make-provider (System/getenv "ANTHROPIC_API_KEY") "claude-sonnet-4-20250514"))
  ;; (provider/complete p [(message/user-message "Say hello in 3 words")] {:max-tokens 100})
  ;; (let [ch (provider/stream p [(message/user-message "Say hello in 3 words")] {:max-tokens 100})]
  ;;   (loop []
  ;;     (when-let [evt (async/<!! ch)]
  ;;       (println evt)
  ;;       (recur))))
  ,)
