# Devin Session Prompts

Run these in order. One session per prompt. Each produces a PR.
After merging each PR, start the next session.

---

## SESSION 1: Config System (~3-5 ACU)

```
Working repo: https://github.com/victorlga/opencode-rewrite
Reference repo: https://github.com/victorlga/opencode (dev branch, TypeScript)
Use playbook: !clj-migrate

Reference TypeScript files:
- packages/opencode/src/config/config.ts (Config.state, 7-layer merge, Zod schemas)

## Pre-flight
Run `bash script/setup.sh` to install tooling and resolve dependencies.
Verify: `clj -M -e '(println "ready")'` and `clj-kondo --version` both succeed.
If either fails, STOP and report the error.

## Task
The project skeleton (deps.edn, directory structure, resources/config.edn, tests.edn, dev/user.clj)
already exists in the repo. Do NOT recreate or overwrite these files.
Your job is to create the Integrant system, config loading, main entry point, and tests.

## Deliverables

### 1. src/opencode/config.clj
- (ns opencode.config) with requires for aero, malli, integrant, io, anomalies
- Define a Malli schema for the config shape matching resources/config.edn:
  [:map
   [:opencode [:map
     [:llm [:map
       [:provider :string]
       [:api-key [:maybe :string]]
       [:model :string]]]
     [:tools [:map
       [:allowed [:set :keyword]]]]
     [:ui [:map
       [:type :keyword]]]
     [:project [:map
       [:directory :string]]]]]]
- (defn load-config [] ...) — reads "config.edn" from classpath via aero.core/read-config,
  validates with Malli schema, throws ex-info on invalid config with humanize'd errors
- (defn validate-config [config] ...) — returns config if valid, anomaly map if not
- Integrant init-key for :opencode/config that calls load-config

### 2. src/opencode/system.clj
- (ns opencode.system) requiring integrant.core
- (defn system-config [] ...) — returns the Integrant system map: {:opencode/config {}}
- (defn start! [] ...) — calls ig/init on system-config, returns the running system
- (defn stop! [system] ...) — calls ig/halt! on system
- This file will grow as we add components in future sessions

### 3. src/opencode/main.clj
- (ns opencode.main) requiring tools.cli, opencode.system
- Define CLI options: --help, --version, --dangerously-skip-permissions
- (defn -main [& args] ...) — parse opts, handle --help/--version,
  otherwise call (system/start!) and print "opencode-rewrite started"
- For now, just start the system and exit (no interactive loop yet)
- Verify: `clj -M -m opencode.main` prints startup message and exits cleanly

### 4. test/opencode/config_test.clj
- Test 1: load-config successfully loads and validates the default config
- Test 2: validate-config rejects a config with missing :llm key
  (use (is (match? {::anom/category ::anom/incorrect} (validate-config bad-config))))
- Test 3: validate-config rejects a config with wrong type for :model (e.g., integer instead of string)
- Use matcher-combinators for all assertions

## Verification
- `clj -M -m opencode.main` prints startup message and exits
- `clj -M:test` passes all tests
- `clj-kondo --lint src test` has zero errors
```

---

## SESSION 2: Message Domain + Session + Event Bus (~5-7 ACU)

```
Working repo: https://github.com/victorlga/opencode-rewrite
Reference repo: https://github.com/victorlga/opencode (dev branch)
Use playbook: !clj-migrate

Reference TypeScript files:
- packages/opencode/src/session/message-v2.ts (MessageV2 types, Part discriminated unions)
- packages/opencode/src/bus/bus-event.ts (BusEvent.define, event type registry)
- packages/opencode/src/bus/index.ts (Bus.publish, Bus.subscribe)

## Task
Create the core data model (messages, sessions) and the event bus.
These are pure data — no I/O, no state, no side effects in domain code.

In the TypeScript source, messages use discriminated unions with a :type field for Parts,
and a 4-state machine for ToolPart (pending → running → completed/error).
For the Clojure MVP, keep it simpler: just model the core message types and tool calls.

## Deliverables

### 1. src/opencode/domain/message.clj
Malli schemas for the message model:
- Role enum: [:enum :system :user :assistant :tool]
- ToolCall schema:
  [:map
   [:tool-call/id :string]
   [:tool-call/name :string]
   [:tool-call/arguments [:map-of :keyword :any]]]
- UserMessage schema:
  [:map
   [:message/role [:= :user]]
   [:message/content :string]]
- AssistantMessage schema:
  [:map
   [:message/role [:= :assistant]]
   [:message/content [:maybe :string]]
   [:message/tool-calls {:optional true} [:vector ToolCall]]
   [:message/finish-reason [:enum :stop :tool-calls :length :error]]]
- ToolResultMessage schema:
  [:map
   [:message/role [:= :tool]]
   [:message/tool-call-id :string]
   [:message/content :string]]
- SystemMessage schema:
  [:map
   [:message/role [:= :system]]
   [:message/content :string]]
- Message schema: [:or SystemMessage UserMessage AssistantMessage ToolResultMessage]
- Constructor functions:
  (defn system-message [content] ...)
  (defn user-message [content] ...)
  (defn assistant-message [content tool-calls finish-reason] ...)
  (defn tool-result [call-id content] ...)
- All constructors must validate output with Malli and throw on invalid data

### 2. src/opencode/domain/session.clj
Pure functions for session manipulation:
- Session schema:
  [:map
   [:session/id :uuid]
   [:session/title :string]
   [:session/messages [:vector Message]]
   [:session/created-at inst?]
   [:session/model :string]
   [:session/tokens [:map
     [:input :int]
     [:output :int]]]]
- (defn create-session [model] ...) — returns new session with random UUID, empty messages,
  zero tokens, title "New Session", created-at (Instant/now)
- (defn append-message [session message] ...) — returns session with message conj'd to :session/messages
- (defn get-messages [session] ...) — returns :session/messages vector
- (defn session-token-count [session] ...) — returns :session/tokens map
- (defn update-tokens [session input-delta output-delta] ...) — adds deltas to current token counts

### 3. src/opencode/logic/event_bus.clj
core.async pub/sub event bus:
- (defn create-bus [] ...) — returns map:
  {:bus/channel (async/chan (async/sliding-buffer 1024))
   :bus/publication (async/pub channel :event/type)}
- (defn publish! [bus event-type data] ...) — puts event map on channel:
  {:event/type event-type :event/data data :event/timestamp (Instant/now)}
  Use async/put! (non-blocking). Returns true if put succeeded.
- (defn subscribe [bus event-type buffer-size] ...) — creates subscriber channel
  with (async/sub publication event-type (async/chan (async/sliding-buffer buffer-size)))
  Returns the subscriber channel.
- (defn unsubscribe [bus event-type channel] ...) — calls async/unsub
- (defn close-bus! [bus] ...) — closes the main channel
- Event types (as keywords — no need to pre-register for MVP):
  :llm/stream-delta, :llm/stream-done, :llm/error,
  :tool/executing, :tool/completed, :tool/error,
  :session/updated, :permission/requested, :permission/replied
- Wire as Integrant init-key :opencode/event-bus (no config dependency needed)
- Update system.clj: add :opencode/event-bus {} to system-config

### 4. Tests for all three namespaces
- test/opencode/domain/message_test.clj:
  - Test each constructor produces a map matching its Malli schema
  - Test user-message with empty string still validates
  - Test assistant-message with tool-calls and without
  - Test validation rejects bad data (e.g., wrong role keyword)
- test/opencode/domain/session_test.clj:
  - Test create-session returns valid session schema
  - Test append-message + get-messages round-trips
  - Test update-tokens accumulates correctly
  - Test session-token-count returns correct map
- test/opencode/logic/event_bus_test.clj:
  - Test publish! + subscribe delivers events of matching type
  - Test subscriber does not receive events of non-matching type
  - Test close-bus! causes subscriber channel to close
  - Use (async/alts!! [ch (async/timeout 1000)]) for timeouts in tests

## Verification
- `clj -M:test` all tests pass
- `clj-kondo --lint src test` zero errors
```

---

## SESSION 3: Provider Protocol + Model Registry + SSE Streaming (~5-7 ACU)

```
Working repo: https://github.com/victorlga/opencode-rewrite
Reference repo: https://github.com/victorlga/opencode (dev branch)
Use playbook: !clj-migrate

Reference TypeScript files:
- packages/opencode/src/provider/provider.ts (Provider.Model schema, Provider.getModel)
- packages/opencode/src/provider/models.ts (model metadata from models.dev)

## Task
Create the LLM provider abstraction layer: the protocol, model metadata registry,
and SSE stream parsing. This session does NOT implement any HTTP calls — that's Session 4.
After this session, we have the interfaces and parsers that the Anthropic adapter needs.

## Deliverables

### 1. src/opencode/adapter/llm/provider.clj
LLMProvider protocol definition:
```clojure
(defprotocol LLMProvider
  (complete [this messages opts]
    "Synchronous completion. Returns assistant message map or anomaly.")
  (stream [this messages opts]
    "Streaming completion. Returns core.async channel of stream events.")
  (list-models [this]
    "Returns vector of model metadata maps."))
```
- No implementation yet — just the protocol and its docstrings
- Also define a Malli schema for the opts map:
  [:map
   [:system {:optional true} :string]
   [:tools {:optional true} [:vector :map]]
   [:max-tokens {:optional true} :int]
   [:temperature {:optional true} :double]]

### 2. src/opencode/adapter/llm/model_registry.clj
Model metadata as plain data:
- Define a Malli schema for model info:
  [:map
   [:model/id :string]
   [:model/provider :keyword]
   [:model/context-window :int]
   [:model/max-output :int]
   [:model/cost [:map [:input :double] [:output :double]]]
   [:model/capabilities {:optional true} [:map
     [:tool-calls :boolean]
     [:streaming :boolean]]]]
- Define model metadata as a map literal (not fetched from network for MVP):
  {"claude-sonnet-4-20250514" {:model/id "claude-sonnet-4-20250514"
                                :model/provider :anthropic
                                :model/context-window 200000
                                :model/max-output 16384
                                :model/cost {:input 3.0 :output 15.0}
                                :model/capabilities {:tool-calls true :streaming true}}
   "claude-haiku-3-5-20241022" {:model/id "claude-haiku-3-5-20241022"
                                 :model/provider :anthropic
                                 :model/context-window 200000
                                 :model/max-output 8192
                                 :model/cost {:input 0.80 :output 4.0}
                                 :model/capabilities {:tool-calls true :streaming true}}}
- (defn get-model [model-id] ...) — returns model map or anomaly {:cognitect.anomalies/category :not-found}
- (defn models-for-provider [provider-kw] ...) — filters and returns vector of models

### 3. src/opencode/logic/streaming.clj
SSE parsing utilities for the Anthropic Messages API streaming format:
- Anthropic SSE format: lines of "event: <type>\n" followed by "data: <json>\n" then blank line
- Event types to parse: message_start, content_block_start, content_block_delta,
  content_block_stop, message_delta, message_stop, error, ping
- (defn parse-sse-event [event-type data-str] ...) — takes event type string and JSON data string,
  returns parsed map: {:sse/type :content_block_delta :sse/data {...parsed json...}}
  Use jsonista for JSON parsing. Return nil for "ping" events.
- (defn read-sse-events [reader] ...) — takes a java.io.BufferedReader, returns a lazy seq of
  parsed SSE event maps. Reads line by line, accumulates event-type + data lines,
  emits event on blank line. Stops when reader returns nil (stream closed).
  IMPORTANT: This function is blocking — it will be called from async/thread, never from go blocks.
- (defn sse-events->channel [reader] ...) — wraps read-sse-events in an async/thread,
  puts each parsed event onto a core.async channel, closes channel when reader ends.
  Returns the channel immediately.

### 4. Tests
- test/opencode/adapter/llm/model_registry_test.clj:
  - Test get-model returns valid model for known ID
  - Test get-model returns anomaly for unknown ID
  - Test models-for-provider returns only Anthropic models
  - Test all model entries match the Malli schema
- test/opencode/logic/streaming_test.clj:
  - Test parse-sse-event handles content_block_delta with text delta
  - Test parse-sse-event handles message_start with message metadata
  - Test parse-sse-event handles error events
  - Test parse-sse-event returns nil for ping
  - Test read-sse-events parses a multi-event string from a BufferedReader
    (use java.io.StringReader wrapped in BufferedReader for test input)
  - Test sse-events->channel delivers events and closes channel on reader end

## Verification
- `clj -M:test` all tests pass
- `clj-kondo --lint src test` zero errors
```

---

## SESSION 4: Anthropic API Implementation (~5-7 ACU)

```
Working repo: https://github.com/victorlga/opencode-rewrite
Reference repo: https://github.com/victorlga/opencode (dev branch)
Use playbook: !clj-migrate

Reference TypeScript files:
- packages/opencode/src/provider/provider.ts (Anthropic custom loader, lines ~270-320)
- Review the Anthropic Messages API: https://docs.anthropic.com/en/api/messages

## Task
Implement the Anthropic provider using the LLMProvider protocol from Session 3.
This is the first session that makes real HTTP calls.
After this session, we can call Claude and get streaming responses.

## Deliverables

### 1. src/opencode/adapter/llm/anthropic.clj
Anthropic Messages API implementation:
- (defrecord AnthropicProvider [api-key model-id http-client])
  implements LLMProvider protocol
- Message format conversion helpers:
  (defn messages->anthropic [messages] ...)
  - Filter out :system messages (they go in the top-level "system" param)
  - Convert :user messages to {:role "user" :content content}
  - Convert :assistant messages to {:role "assistant" :content content}
    If assistant has :message/tool-calls, format as tool_use content blocks:
    {:type "tool_use" :id call-id :name tool-name :input arguments}
  - Convert :tool messages to {:role "user" :content [{:type "tool_result"
    :tool_use_id call-id :content content}]}
  (defn anthropic-response->message [response] ...)
  - Parse response body: extract content blocks, detect tool_use blocks
  - Build assistant-message with accumulated text, tool-calls, and stop_reason mapping
    ("end_turn" → :stop, "tool_use" → :tool-calls, "max_tokens" → :length)
- `complete` implementation:
  - POST https://api.anthropic.com/v1/messages
  - Headers: {"x-api-key" api-key, "anthropic-version" "2023-06-01",
              "content-type" "application/json"}
  - Body (jsonista): {:model model-id, :messages (messages->anthropic msgs),
                      :max_tokens (or (:max-tokens opts) 4096),
                      :system (extract system message content from msgs),
                      :tools (when (:tools opts) ...)}
  - Use hato: (hato/post url {:headers h :body json-body :as :stream})
  - Parse response with jsonista, convert to our message format
  - On HTTP error: return anomaly with category based on status code
    (401 → :forbidden, 429 → :busy, 500+ → :fault, other → :incorrect)
- `stream` implementation:
  - Same endpoint with :stream true in body
  - Use hato with {:as :stream} to get InputStream
  - Wrap InputStream in BufferedReader
  - Call (streaming/sse-events->channel reader) to get event channel
  - Create output channel. In async/thread, consume SSE events and emit:
    - content_block_delta with type "text_delta" → {:type :text-delta :text "..."}
    - content_block_start with type "tool_use" → start accumulating tool call
    - content_block_delta with type "input_json_delta" → accumulate tool args JSON
    - content_block_stop (for tool_use) → {:type :tool-call :tool-call {...}}
    - message_stop → {:type :done :message (build final assistant-message)}
    - error → {:type :error :error anomaly-map}
  - Return the output channel
- `list-models`: delegate to model-registry/models-for-provider
- Wire as Integrant init-key :opencode/llm-provider depending on :opencode/config
  Extract api-key and model from config, create AnthropicProvider with hato client

### 2. Update src/opencode/system.clj
Add :opencode/llm-provider to system-config, depending on :opencode/config:
  :opencode/llm-provider {:config (ig/ref :opencode/config)}

### 3. Tests
- test/opencode/adapter/llm/anthropic_test.clj:
  - Test messages->anthropic converts each message type correctly
  - Test messages->anthropic extracts system message to separate return value
  - Test messages->anthropic handles tool calls in assistant messages
  - Test messages->anthropic converts tool results to tool_result content blocks
  - Test anthropic-response->message parses a text-only response
  - Test anthropic-response->message parses a response with tool_use blocks
  - Test anthropic-response->message maps stop_reason correctly
  - DO NOT make real API calls — test only the conversion functions
  - Add a rich comment block at the bottom with a manual REPL test
    that actually calls Claude (requires ANTHROPIC_API_KEY env var)

## Verification
- `clj -M:test` all tests pass
- `clj-kondo --lint src test` zero errors
- Manual REPL test (in rich comment block): call Claude with "Say hello in 3 words"
```

---

## SESSION 5: Tool Framework + Read/Write/Glob (~5-7 ACU)

```
Working repo: https://github.com/victorlga/opencode-rewrite
Reference repo: https://github.com/victorlga/opencode (dev branch)
Use playbook: !clj-migrate

Reference TypeScript files:
- packages/opencode/src/tool/tool.ts (Tool.define, Tool.Context, parameter validation)
- packages/opencode/src/tool/read.ts (ReadTool — line ranges, binary detection, truncation)
- packages/opencode/src/tool/write.ts (WriteTool — create dirs, overwrite)
- packages/opencode/src/tool/glob.ts (GlobTool — babashka.fs/glob)

## Task
Build the tool system framework and implement the 3 simplest, safest tools.
After this session, tools can be registered, discovered, converted to Anthropic API format,
and executed. These tools are all read-safe or create-only, no destructive operations.

## Deliverables

### 1. src/opencode/domain/tool.clj
Tool framework:
- Malli schema for a tool definition:
  [:map
   [:tool/name :string]
   [:tool/description :string]
   [:tool/parameters :any]  ;; Malli schema (will be converted to JSON Schema for API)
   [:tool/dangerous? :boolean]]
- Tool registry: an atom holding a map of tool-name → tool-definition
- (defn register-tool! [tool-def] ...) — validates and adds to registry atom
- (defn get-tool [tool-name] ...) — looks up tool in registry, returns def or nil
- (defn all-tools [] ...) — returns all registered tool definitions
- (defn tools-for-api [tools] ...) — converts tool defs to Anthropic API tool format:
  For each tool, use (malli.json-schema/transform (:tool/parameters tool)) to get
  JSON Schema, then build: {:name name :description desc :input_schema json-schema}
  Return vector of these maps.
- Tool execution multimethod:
  (defmulti execute-tool! (fn [tool-name _params _context] tool-name))
  (defmethod execute-tool! :default [tool-name _ _]
    {::anom/category ::anom/unsupported :message (str "Unknown tool: " tool-name)})
- Tool context map (passed to execute-tool!):
  {:ctx/session-id uuid, :ctx/project-dir string, :ctx/dangerous-mode? boolean}

### 2. src/opencode/adapter/tool/file_read.clj
ReadTool implementation:
- Register tool definition on namespace load:
  {:tool/name "read_file"
   :tool/description "Read the contents of a file. Returns file content as text."
   :tool/parameters [:map
     [:path :string]
     [:offset {:optional true} :int]
     [:limit {:optional true} :int]]
   :tool/dangerous? false}
- defmethod execute-tool! "read_file":
  - Resolve path relative to project dir from context
  - If file doesn't exist: return anomaly :not-found
  - If file is binary (check first 8KB for null bytes): return anomaly :incorrect with
    "Binary file detected" message
  - Read file as string via slurp
  - Apply :offset and :limit (line-based: skip offset lines, take limit lines)
  - If content exceeds 50KB: truncate and append
    "\n[Content truncated. Use grep to search or read_file with offset/limit.]"
  - Return {:output content}

### 3. src/opencode/adapter/tool/file_write.clj
WriteTool implementation:
- Register tool definition:
  {:tool/name "write_file"
   :tool/description "Create or overwrite a file with the given content."
   :tool/parameters [:map [:path :string] [:content :string]]
   :tool/dangerous? true}
- defmethod execute-tool! "write_file":
  - Resolve path relative to project dir
  - Create parent directories if needed (babashka.fs/create-dirs)
  - Write content via spit
  - Return {:output (str "Wrote " (count content) " bytes to " absolute-path)}

### 4. src/opencode/adapter/tool/glob.clj
GlobTool implementation:
- Register tool definition:
  {:tool/name "glob"
   :tool/description "Find files matching a glob pattern."
   :tool/parameters [:map
     [:pattern :string]
     [:path {:optional true :default "."} :string]]
   :tool/dangerous? false}
- defmethod execute-tool! "glob":
  - Resolve :path relative to project dir
  - Call (babashka.fs/glob path pattern)
  - Convert results to relative path strings (relative to project dir)
  - Sort alphabetically
  - If more than 200 results, truncate and add count message
  - Return {:output (str/join "\n" file-paths)}

### 5. Tests
- test/opencode/domain/tool_test.clj:
  - Test register-tool! + get-tool round-trips
  - Test tools-for-api generates valid JSON Schema structure
  - Test execute-tool! :default returns unsupported anomaly
- test/opencode/adapter/tool/file_read_test.clj:
  - Create temp dir with test files using babashka.fs
  - Test reads a normal text file
  - Test returns anomaly for missing file
  - Test line offset + limit works
  - Test truncation at 50KB (create a large temp file)
- test/opencode/adapter/tool/file_write_test.clj:
  - Test writes new file in temp dir
  - Test creates parent directories
  - Test overwrites existing file
- test/opencode/adapter/tool/glob_test.clj:
  - Create temp dir tree with various files
  - Test "*.clj" pattern finds .clj files
  - Test "**/*.md" pattern finds nested .md files
  - Test returns empty for non-matching pattern

## Verification
- `clj -M:test` all tests pass
- `clj-kondo --lint src test` zero errors
```

---

## SESSION 6: Edit + Bash + Grep Tools (~5-7 ACU)

```
Working repo: https://github.com/victorlga/opencode-rewrite
Reference repo: https://github.com/victorlga/opencode (dev branch)
Use playbook: !clj-migrate

Reference TypeScript files:
- packages/opencode/src/tool/edit.ts (fuzzy matching, whitespace normalization)
- packages/opencode/src/tool/bash.ts (PTY execution, tree-sitter analysis, timeout)
- packages/opencode/src/tool/grep.ts (ripgrep-powered search)

## Task
Implement the 3 more complex tools: edit (search/replace with fuzzy matching),
bash (shell execution), and grep (content search).
After this session, the full tool suite is complete.

## Deliverables

### 1. src/opencode/adapter/tool/file_edit.clj
EditTool implementation — the most complex tool:
- Register tool definition:
  {:tool/name "edit_file"
   :tool/description "Replace exact text in a file. Searches for old_string and replaces with new_string."
   :tool/parameters [:map
     [:path :string]
     [:old-string :string]
     [:new-string :string]]
   :tool/dangerous? true}
- defmethod execute-tool! "edit_file":
  - Read file content
  - Try exact string match first (str/includes?)
  - If found: replace FIRST occurrence only (use str/replace-first), write back, return success
  - If not found: try whitespace-normalized match:
    - Normalize both file content and old-string: trim each line, collapse multiple spaces
    - If normalized match found: find the corresponding original lines, replace them
  - If still not found: find the closest matching region using a simple similarity heuristic
    (e.g., longest common substring), return anomaly :not-found with context snippet showing
    the 5 nearest lines
  - Return {:output (str "Edited " path ": replaced " (count old-string) " chars")}

### 2. src/opencode/adapter/tool/bash.clj
BashTool implementation:
- Register tool definition:
  {:tool/name "bash"
   :tool/description "Execute a shell command and return its output."
   :tool/parameters [:map
     [:command :string]
     [:timeout {:optional true :default 120000} :int]]
   :tool/dangerous? true}
- defmethod execute-tool! "bash":
  - Use babashka.process:
    (p/process ["bash" "-c" command] {:out :string :err :string :timeout timeout-ms})
  - Wait for completion with p/check (catches non-zero exit)
  - Wrap in try/catch for timeout (ExceptionInfo with :type :timeout)
  - Combine stdout + stderr
  - Truncate combined output at 50KB
  - Return {:output combined-output :exit-code exit-code}
  - On timeout: return anomaly :busy with "Command timed out" message
  - On non-zero exit: still return output (not an anomaly — non-zero exit is often informative)

### 3. src/opencode/adapter/tool/grep.clj
GrepTool implementation:
- Register tool definition:
  {:tool/name "grep"
   :tool/description "Search file contents using a regular expression pattern."
   :tool/parameters [:map
     [:pattern :string]
     [:path {:optional true :default "."} :string]
     [:include {:optional true} :string]]
   :tool/dangerous? false}
- defmethod execute-tool! "grep":
  - Build grep command:
    If ripgrep available (check with p/process ["which" "rg"]):
      ["rg" "-n" "--no-heading" pattern path] + (when include ["--glob" include])
    Else fallback:
      ["grep" "-rn" pattern path] + (when include ["--include" include])
  - Execute with babashka.process, capture stdout
  - Parse output lines into [{:file "path" :line 42 :content "matching line"} ...]
  - Truncate at 100 matches
  - Return {:output (str/join "\n" formatted-matches)}
    Format: "file:line: content"

### 4. Tests
- test/opencode/adapter/tool/file_edit_test.clj:
  - Test exact string replacement works
  - Test whitespace-normalized matching works (extra indentation in file)
  - Test returns anomaly with context when old-string not found
  - Test replaces only first occurrence when old-string appears multiple times
- test/opencode/adapter/tool/bash_test.clj:
  - Test "echo hello" returns {:output "hello\n" :exit-code 0}
  - Test "exit 1" returns exit-code 1 (not an anomaly)
  - Test timeout with "sleep 5" and timeout 100ms returns :busy anomaly
- test/opencode/adapter/tool/grep_test.clj:
  - Create temp dir with files containing known patterns
  - Test finds matches across multiple files
  - Test returns empty for non-matching pattern
  - Test --include filter works

## Verification
- `clj -M:test` all tests pass
- All 6 tools are independently executable from REPL
- `clj-kondo --lint src test` zero errors
```

---

## SESSION 7: UI Adapter + Persistence + Permission (~5-7 ACU)

```
Working repo: https://github.com/victorlga/opencode-rewrite
Reference repo: https://github.com/victorlga/opencode (dev branch)
Use playbook: !clj-migrate

Reference TypeScript files:
- packages/opencode/src/permission/ (permission rules, multi-layer merge)
- No direct TS equivalent for UI/persistence — these are simpler in our Clojure MVP

## Task
Build the supporting infrastructure the agentic loop needs:
UI adapter (how the user sees output and gives permission), persistence (session storage),
and the permission system (which tools need approval).
After this session, all dependencies for the agentic loop are in place.

## Deliverables

### 1. src/opencode/adapter/ui/protocol.clj
UI adapter protocol:
```clojure
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
```

### 2. src/opencode/adapter/ui/repl.clj
JLine readline implementation:
- (defrecord ReplUI [line-reader]) implementing UIAdapter
- Create LineReader via (-> (LineReaderBuilder/builder) (.terminal terminal) (.build))
  where terminal = (TerminalBuilder/builder) (.system true) (.build)
- display-text!: print with white ANSI color, newline
- display-tool-call!: print "[tool-name] " in cyan, then params summary
- display-tool-result!: print first 500 chars of result in dim/gray
- display-error!: print in red with "Error: " prefix
- ask-permission!: print prompt in yellow, read "y/n" via line-reader, return keyword
- get-input!: read via (.readLine line-reader prompt)
- ANSI helpers: (defn- ansi [code text] (str "\033[" code "m" text "\033[0m"))
- Wire as Integrant init-key :opencode/ui (no dependencies)

### 3. src/opencode/adapter/persistence.clj
Atom-backed session store:
- (defrecord SessionStore [sessions-atom])
- (defn create-store [] (->SessionStore (atom {})))
- (defn save-session! [store session] ...)
  — swap! sessions-atom assoc (:session/id session) session
- (defn load-session [store session-id] ...)
  — get from atom, return session or nil
- (defn list-sessions [store] ...)
  — vals from atom, sorted by :session/created-at descending
- (defn delete-session! [store session-id] ...)
  — swap! sessions-atom dissoc session-id
- Wire as Integrant init-key :opencode/session-store (no dependencies)

### 4. src/opencode/logic/permission.clj
Simple permission checking:
- Default permission rules as data:
  {"read_file" :allow
   "glob" :allow
   "grep" :allow
   "write_file" :ask
   "edit_file" :ask
   "bash" :ask}
- (defn check-permission [tool-name context] ...)
  — if (:ctx/dangerous-mode? context) → :allow (skip all checks)
  — else look up tool-name in rules, default to :ask
- (defn request-permission! [ui-adapter tool-name params] ...)
  — calls (ui/ask-permission! ui-adapter tool-name params)
  — returns :approved or :denied

### 5. Update src/opencode/system.clj
Add all new components:
  :opencode/ui {}
  :opencode/session-store {}

### 6. Tests
- test/opencode/adapter/persistence_test.clj:
  - Test save + load round-trip
  - Test load returns nil for unknown ID
  - Test list-sessions returns sorted by created-at
  - Test delete removes session
- test/opencode/logic/permission_test.clj:
  - Test read tools get :allow
  - Test write/bash tools get :ask
  - Test unknown tool gets :ask (default)
  - Test dangerous-mode? overrides to :allow for all tools
- test/opencode/adapter/ui/repl_test.clj:
  - Test ANSI helper wraps text correctly
  - Test ReplUI satisfies UIAdapter protocol (use satisfies? check)
  - Do NOT test interactive I/O (JLine requires a real terminal) —
    just test the helper functions and protocol satisfaction

## Verification
- `clj -M:test` all tests pass
- `clj-kondo --lint src test` zero errors
```

---

## SESSION 8: Agent Loop + System Prompt (~5-7 ACU)

```
Working repo: https://github.com/victorlga/opencode-rewrite
Reference repo: https://github.com/victorlga/opencode (dev branch)
Use playbook: !clj-migrate

Reference TypeScript files:
- packages/opencode/src/session/prompt.ts (SessionPrompt.loop — the agentic loop state machine)
- packages/opencode/src/session/processor.ts (SessionProcessor.process — stream consumption)

## Task
Implement the core agentic loop: build system prompt, call LLM, consume stream,
execute tool calls, loop until done. This is the brain of the application.
After this session, we have a function that takes a user message and returns a
complete conversation with tool calls resolved.

## Deliverables

### 1. src/opencode/logic/prompt.clj
System prompt construction:
- (defn build-system-prompt [config tools] ...) → string
  Build a system prompt including:
  - Role: "You are an AI coding assistant."
  - Working directory: (System/getProperty "user.dir")
  - OS: (System/getProperty "os.name") + (System/getProperty "os.version")
  - Date: (str (java.time.LocalDate/now))
  - Available tools: for each tool in tools, list name + description
  - Instructions: "Use tools to help the user. Be concise."
  Format as a single string with clear sections.

### 2. src/opencode/logic/agent.clj
The agentic loop — the core of the application:
```clojure
(defn run-agent-loop!
  "Core agentic loop. Sends messages to LLM, executes tool calls, loops until done.
   Returns the final session with all messages appended.
   Publishes events to the event bus throughout."
  [{:keys [provider session tools event-bus ui-adapter context]}]
  ...)
```
Implementation (loop/recur based):
- Build system prompt from config + tools
- Build messages for LLM: [system-prompt] + session messages
- Convert tools to API format via domain.tool/tools-for-api
- Start loop (max 25 iterations):
  1. Call (llm/stream provider messages {:system system-prompt :tools api-tools :max-tokens max-output})
  2. Consume the event channel:
     - :text-delta → accumulate into text buffer, publish :llm/stream-delta to event bus,
       call (ui/display-text! ui-adapter text-delta) for live streaming
     - :tool-call → collect into tool-calls vector
     - :done → extract final assistant message
     - :error → return session with error (don't loop)
  3. Append assistant message to session
  4. Publish :session/updated to event bus
  5. If no tool calls (finish-reason :stop): return session (done!)
  6. If tool calls present:
     a. For each tool call:
        - Check permission via permission/check-permission
        - If :ask → call permission/request-permission!, if :denied → add tool result "Permission denied"
        - If :allow or :approved → call (tool/execute-tool! tool-name args context)
        - Display tool call and result via UI adapter
        - Publish :tool/executing and :tool/completed to event bus
        - Build tool-result message
     b. Append all tool-result messages to session
     c. Continue loop (back to step 1 with updated messages)
  7. If max iterations reached: append a text-only message asking LLM to stop,
     do one final call without tools, return session
- Error handling: wrap the whole loop in try/catch.
  On exception: publish :llm/error, return session with error appended.

### 3. Tests
- test/opencode/logic/prompt_test.clj:
  - Test build-system-prompt returns a string containing "AI coding assistant"
  - Test build-system-prompt includes tool names when tools are provided
  - Test build-system-prompt includes working directory
  - Test build-system-prompt works with empty tools list
- test/opencode/logic/agent_test.clj:
  Create a mock LLM provider (reify LLMProvider):
  - Test simple text response (no tool calls):
    Mock provider returns channel with [{:type :text-delta :text "Hello"}
                                        {:type :done :message assistant-msg}]
    Assert: session has 1 new assistant message with content "Hello"
  - Test tool call → tool result → final response:
    Mock provider returns tool call on first call, then text on second call.
    Use a stateful atom to track which call number we're on.
    Assert: session has assistant(tool-call) + tool-result + assistant(text) messages
  - Test max iterations guard:
    Mock provider always returns tool calls (infinite loop scenario).
    Assert: loop terminates after max iterations, doesn't hang.
  - Use a mock UI adapter (reify UIAdapter — all methods are no-ops or return :approved)
  - Use a real event bus (create-bus) to verify events are published

## Verification
- `clj -M:test` all tests pass
- `clj-kondo --lint src test` zero errors
```

---

## SESSION 9: Main Entry Point + System Wiring — MVP (~3-5 ACU)

```
Working repo: https://github.com/victorlga/opencode-rewrite
Reference repo: https://github.com/victorlga/opencode (dev branch)
Use playbook: !clj-migrate

## Task
Wire everything together: update main.clj with the interactive REPL loop,
ensure all Integrant components are connected, and verify the application works end-to-end.

THIS IS THE MVP MILESTONE. After this session, the application is a working agentic
coding assistant that accepts natural language input, calls Claude, executes tools,
and iterates on results.

## Deliverables

### 1. Update src/opencode/system.clj
Final Integrant system map with all components wired:
  {:opencode/config {}
   :opencode/event-bus {}
   :opencode/session-store {}
   :opencode/llm-provider {:config (ig/ref :opencode/config)}
   :opencode/ui {}}
Ensure all init-key and halt-key! methods are registered in the correct namespaces.
Add requires for all adapter namespaces so their Integrant methods are loaded.

### 2. Update src/opencode/main.clj
Interactive REPL loop:
- Start Integrant system
- Register shutdown hook: (.addShutdownHook (Runtime/getRuntime) (Thread. #(system/stop! system)))
- Create initial session via (session/create-session model-id)
- Print welcome message with model name
- Main loop:
  1. Get input via (ui/get-input! ui "user> ")
  2. Handle special commands:
     - "/quit" or "/exit" → stop system and exit
     - "/new" → create new session, print confirmation
     - "/sessions" → list sessions from store, print titles
     - "" (empty) → continue (skip)
  3. Create user message, append to session
  4. Call (agent/run-agent-loop! {:provider llm :session session :tools (tool/all-tools)
                                   :event-bus bus :ui-adapter ui
                                   :context {:ctx/session-id (:session/id session)
                                             :ctx/project-dir project-dir
                                             :ctx/dangerous-mode? dangerous-mode?}})
  5. Update session with result, save to store
  6. Loop back to step 1
- Wrap everything in try/catch for graceful error display

### 3. Ensure tool namespaces are loaded
All tool adapter namespaces (file_read, file_write, file_edit, glob, grep, bash)
must be required at startup so their defmethod registrations execute.
Add requires to system.clj or main.clj:
  (:require [opencode.adapter.tool.file-read]
            [opencode.adapter.tool.file-write]
            [opencode.adapter.tool.file-edit]
            [opencode.adapter.tool.glob]
            [opencode.adapter.tool.grep]
            [opencode.adapter.tool.bash])

### 4. No new tests needed
This session is integration wiring. Existing tests must still pass.
Manual testing is the primary verification.

## Verification
- `clj -M -m opencode.main` starts, shows welcome message, waits for input
- Type "What files are in this directory?" → Claude calls glob tool → shows file list
- Type "Read the deps.edn file" → Claude calls read_file → shows content
- Permission prompt appears for write/bash tools (unless --dangerously-skip-permissions)
- /quit exits cleanly
- /new creates a new session
- `clj -M:test` all existing tests still pass
- `clj-kondo --lint src test` zero errors

## THIS IS THE MVP MILESTONE
The application is now a working agentic coding assistant.
```

---

## SESSION 10 (POST-MVP): Context Management + Compaction (~3-5 ACU)

```
Working repo: https://github.com/victorlga/opencode-rewrite
Reference repo: https://github.com/victorlga/opencode (dev branch)
Use playbook: !clj-migrate

Reference TypeScript files:
- packages/opencode/src/session/overflow.ts (isOverflow — simple threshold check)
- packages/opencode/src/session/compaction.ts (prune, process, create — context management)

## Task
Add context window management to prevent the agent from exceeding the model's context limit.
Detect overflow, prune old tool outputs, and auto-compact via summarization.

## Deliverables

### 1. src/opencode/domain/conversation.clj
Pure functions for context management:
- (defn estimate-tokens [text] ...)
  Rough heuristic: (int (/ (count text) 4)) — characters divided by 4
  Good enough for MVP, can be replaced with tiktoken later
- (defn conversation-token-count [messages] ...)
  Sum estimate-tokens over all :message/content fields (including tool results)
- (defn overflow? [messages model-metadata] ...)
  Calculate usable context: (:model/context-window model) - (max (:model/max-output model) 32000) - 20000
  Return true if (conversation-token-count messages) >= usable context
- (defn prune-tool-outputs [messages keep-last-n] ...)
  Walk messages from oldest to newest. For tool-result messages older than the last
  keep-last-n user turns, replace :message/content with "[Tool output pruned]".
  Skip if replacement would save less than 20000 estimated tokens total.
  Return updated messages vector.
- (defn build-compaction-prompt [messages] ...)
  Return a string prompt asking the LLM to summarize the conversation:
  "Summarize this conversation as a structured summary with sections:
   ## Goal, ## Key Discoveries, ## Work Accomplished, ## Current State.
   Be concise but preserve important details, file paths, and decisions."

### 2. Update src/opencode/logic/agent.clj
Add overflow handling to the agentic loop, before each LLM call:
- After building messages, check (conversation/overflow? messages model-metadata)
- If overflow:
  1. Try pruning: (conversation/prune-tool-outputs messages 2)
  2. Recheck overflow with pruned messages
  3. If still overflow: call LLM with compaction prompt to generate summary,
     replace conversation history with [system-msg, summary-as-user-msg, last-2-user-turns]
  4. Publish :session/compacted event to bus
- If overflow after compaction: return anomaly :fault "Context overflow — conversation too large"

### 3. Tests
- test/opencode/domain/conversation_test.clj:
  - Test estimate-tokens returns reasonable count (e.g., 400 chars → ~100 tokens)
  - Test conversation-token-count sums across multiple messages
  - Test overflow? returns false for small conversations
  - Test overflow? returns true when messages exceed context limit
  - Test prune-tool-outputs replaces old tool outputs with placeholder
  - Test prune-tool-outputs preserves recent tool outputs (keep-last-n)
  - Test prune-tool-outputs skips if savings below threshold
  - Test build-compaction-prompt returns non-empty string containing "Goal"

## Verification
- `clj -M:test` all tests pass
- `clj -kondo --lint src test` zero errors
```
