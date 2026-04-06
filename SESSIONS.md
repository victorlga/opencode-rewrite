# Devin Session Prompts

Run these in order. One session per prompt. Each produces a PR.
After merging each PR, start the next session.

---

## SESSION 1: Project Skeleton + Config System (~5-8 ACU)

```
Working repo: https://github.com/victorlga/opencode-rewrite
Reference repo: https://github.com/victorlga/opencode (dev branch, TypeScript)
Use playbook: !clj-migrate

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
- Load config with Aero using (aero.core/read-config resource)
- Define Malli schema for the config shape
- Validate config on load, throw with clear error on invalid config
- Integrant init-key for :opencode/config

### 2. src/opencode/system.clj
- Integrant system map that currently only has :opencode/config
- (defn start! []) and (defn stop! [system]) helper functions
- Will be extended in future sessions

### 3. src/opencode/main.clj
- -main function that calls (system/start!) and prints "opencode-rewrite started"
- Parse --help and --version with tools.cli
- Verify it runs: `clj -M -m opencode.main`

### 4. test/opencode/config_test.clj
- Test config loading with a test EDN fixture
- Test Malli validation catches bad config
- Test env var override works

## Verification
- `clj -M -m opencode.main` prints startup message and exits
- `clj -M:test` passes all tests
- `clj-kondo --lint src test` has zero errors
```

---

## SESSION 2: Message Domain + Event Bus (~5-8 ACU)

```
Working repo: https://github.com/victorlga/opencode-rewrite
Reference repo: https://github.com/victorlga/opencode (dev branch)
Use playbook: !clj-migrate

Reference these TypeScript files for message structure:
- packages/opencode/src/session/message-v2.ts (MessageV2 types)
- packages/opencode/src/bus/ (event bus pattern)

## Task
Create the core data model (messages, sessions) and the event bus.
These are pure data — no I/O, no state, no side effects in domain code.

## Deliverables

### 1. src/opencode/domain/message.clj
Malli schemas for the message model. Translate from TypeScript MessageV2:
- Message roles: :system, :user, :assistant, :tool
- User message: {:message/role :user, :message/content "...", :message/parts [...]}
- Assistant message: {:message/role :assistant, :message/content "...",
                      :message/tool-calls [...], :message/finish-reason :stop/:tool-calls/:length/:error}
- Tool result: {:message/role :tool, :message/tool-call-id "...", :message/content "..."}
- Tool call: {:tool-call/id "...", :tool-call/name "...", :tool-call/arguments {...}}
- Constructor functions: (user-message content), (assistant-message content tool-calls finish),
  (tool-result call-id content)
- DO NOT model all TypeScript parts — keep it minimal for MVP

### 2. src/opencode/domain/session.clj
Pure functions for session data manipulation:
- Session schema: {:session/id uuid, :session/title str, :session/messages [...],
                   :session/created-at inst, :session/model str, :session/tokens {...}}
- (create-session model) → new session map
- (append-message session message) → updated session
- (get-messages session) → vector of messages
- (session-token-count session) → {:input n :output n}

### 3. src/opencode/logic/event_bus.clj
core.async pub/sub event bus:
- (create-bus) → {:ch channel, :pub pub-instance}
- (publish! bus event-type data) → puts {:event/type type :event/data data :event/time (Instant/now)} on channel
- (subscribe bus event-type buffer-size) → returns a channel receiving events of that type
- (close-bus! bus) → closes the main channel
- Event types: :llm/stream-delta, :llm/stream-done, :llm/error,
               :tool/executing, :tool/completed, :tool/error,
               :session/updated, :permission/requested, :permission/replied
- Wire as Integrant component :opencode/event-bus in system.clj

### 4. Tests for all three namespaces
- message_test.clj: constructors produce valid schemas, validation rejects bad data
- session_test.clj: create/append/query round-trips correctly
- event_bus_test.clj: publish + subscribe delivers events, close stops delivery

## Verification
- `clj -M:test` all tests pass
- `clj-kondo --lint src test` zero errors
```

---

## SESSION 3: Anthropic Provider + SSE Streaming (~8-10 ACU)

```
Working repo: https://github.com/victorlga/opencode-rewrite
Reference repo: https://github.com/victorlga/opencode (dev branch)
Use playbook: !clj-migrate

Reference these TypeScript files:
- packages/opencode/src/provider/provider.ts (provider abstraction)
- packages/opencode/src/provider/transform.ts (provider transforms)
- Check DeepWiki: https://deepwiki.com/anomalyco/opencode for provider architecture

## Task
Implement the LLM provider protocol and Anthropic implementation with streaming.
After this session, we can call Claude and get streaming responses.

## Deliverables

### 1. src/opencode/adapter/llm/provider.clj
Protocol definition:
```clojure
(defprotocol LLMProvider
  (complete [this messages opts]
    "Synchronous completion. Returns assistant message map or anomaly.")
  (stream [this messages opts]
    "Streaming completion. Returns core.async channel of stream events.")
  (list-models [this]
    "Returns vector of model metadata maps."))
```

### 2. src/opencode/adapter/llm/model_registry.clj
Model metadata for Anthropic models:
- claude-sonnet-4-20250514: context 200K, output 16K, cost input $3/M output $15/M
- claude-haiku-3-5: context 200K, output 8K, cost input $0.80/M output $4/M
- Schema: {:model/id str, :model/provider :anthropic, :model/context-window int,
           :model/max-output int, :model/cost {:input num :output num}}
- (get-model model-id) → model metadata or anomaly
- (models-for-provider provider-id) → vector of model maps

### 3. src/opencode/logic/streaming.clj
SSE parsing utilities:
- (parse-sse-line line) → event map or nil
  Anthropic SSE format: "event: <type>\ndata: <json>\n\n"
  Event types to handle: message_start, content_block_start, content_block_delta,
  content_block_stop, message_delta, message_stop, error
- (sse-reader->events reader) → lazy seq or channel of parsed events
- (stream-to-channel input-stream) → core.async channel emitting parsed SSE events
  Use async/thread for the blocking InputStream read loop. NEVER go block.

### 4. src/opencode/adapter/llm/anthropic.clj
Anthropic API implementation:
- AnthropicProvider record implementing LLMProvider protocol
- `complete`: POST to https://api.anthropic.com/v1/messages
  Headers: x-api-key, anthropic-version "2023-06-01", content-type application/json
  Body: model, messages (convert from our schema to Anthropic format), max_tokens, system
  Parse response into our message schema
- `stream`: Same endpoint with stream: true
  Returns core.async channel. Use async/thread to read InputStream line by line.
  Parse SSE events, accumulate text deltas, detect tool_use blocks.
  Emit events: {:type :text-delta :text "..."}, {:type :tool-call :tool-call {...}},
  {:type :done :message assistant-msg}, {:type :error :error anomaly}
  Close channel when stream ends.
- Helper: (our-messages->anthropic messages) — convert our message format to Anthropic API format
  System messages extracted to top-level `system` param
  Tool results use tool_result content blocks
- Helper: (anthropic-response->message response) — convert API response to our message format
- Wire as Integrant component :opencode/llm-provider depending on :opencode/config

### 5. Tests
- streaming_test.clj: parse-sse-line handles all Anthropic event types, handles malformed lines
- anthropic_test.clj: message format conversion round-trips, API error handling returns anomalies
  (Mock HTTP calls — do NOT make real API calls in tests)

## Verification
- `clj -M:test` all tests pass
- Manual REPL test (in rich comment block): call Claude with a simple message, print response
- `clj-kondo --lint src test` zero errors
```

---

## SESSION 4: Tool System — File Tools + Bash (~8-10 ACU)

```
Working repo: https://github.com/victorlga/opencode-rewrite
Reference repo: https://github.com/victorlga/opencode (dev branch)
Use playbook: !clj-migrate

Reference TypeScript files:
- packages/opencode/src/tool/tool.ts (Tool.define pattern)
- packages/opencode/src/tool/read.ts, write.ts, edit.ts, bash.ts, glob.ts, grep.ts

## Task
Build the tool system framework and implement 6 core tools.
After this session, tools can be registered, discovered, and executed.

## Deliverables

### 1. src/opencode/domain/tool.clj
Tool framework:
- Malli schema for tool definition:
  {:tool/name str, :tool/description str, :tool/parameters MalliSchema,
   :tool/dangerous? bool, :tool/execute fn}
- (tool-definitions) → returns map of all registered tools (from multimethod registry)
- (tools-for-api tools) → converts tool defs to Anthropic API tool format using Malli JSON Schema transform
- Multimethod: (execute-tool! tool-name params context) dispatching on tool-name

### 2. src/opencode/adapter/tool/file_read.clj
ReadTool: Read file contents
- Params: {:path str, :line-range (optional) [start end]}
- Reads file, returns content as string
- Handles: file not found (anomaly), binary detection (anomaly), line range slicing
- Truncates output at 50KB with message directing to use grep
- Register via defmethod execute-tool!

### 3. src/opencode/adapter/tool/file_write.clj
WriteTool: Create or overwrite a file
- Params: {:path str, :content str}
- Creates parent directories if needed
- Returns {:ok true :path absolute-path}

### 4. src/opencode/adapter/tool/file_edit.clj
EditTool: Search and replace in a file
- Params: {:path str, :old-string str, :new-string str}
- Read file, find old-string, replace with new-string, write back
- If old-string not found: try whitespace-normalized match (trim each line, compare)
- If still not found: return anomaly with snippet of file around closest match
- Register via defmethod

### 5. src/opencode/adapter/tool/bash.clj
BashTool: Execute shell commands
- Params: {:command str, :timeout (optional, default 120000) int}
- Use babashka.process: (p/process ["bash" "-c" command] {:out :string :err :string :timeout timeout})
- Return: {:stdout str, :stderr str, :exit-code int}
- Truncate combined output at 50KB
- Mark as :tool/dangerous? true

### 6. src/opencode/adapter/tool/glob.clj
GlobTool: Find files by pattern
- Params: {:pattern str, :path (optional, default ".") str}
- Use babashka.fs/glob
- Return: {:files [relative-path-strings]}

### 7. src/opencode/adapter/tool/grep.clj
GrepTool: Search file contents
- Params: {:pattern str, :path (optional) str, :include (optional) str}
- Use babashka.process to call `grep -rn` (or ripgrep if available)
- Return: {:matches [{:file str :line int :content str}]}
- Truncate at 100 matches

### 8. Tests for each tool
- Test file_read: reads real temp file, handles missing, handles line range
- Test file_write: creates file, creates dirs, overwrites
- Test file_edit: exact match, whitespace-tolerant match, not-found error
- Test bash: simple command, timeout, exit code
- Test glob: finds files matching pattern
- Test grep: finds matches in temp directory
- Test tool.clj: tools-for-api generates valid JSON schema

## Verification
- `clj -M:test` all tests pass
- Tools are independently executable from REPL
- `clj-kondo --lint src test` zero errors
```

---

## SESSION 5: The Agentic Loop — Bring It All Together (~8-10 ACU)

```
Working repo: https://github.com/victorlga/opencode-rewrite
Reference repo: https://github.com/victorlga/opencode (dev branch)
Use playbook: !clj-migrate

Reference TypeScript files:
- packages/opencode/src/session/prompt.ts (SessionPrompt.loop — the core loop)
- packages/opencode/src/session/processor.ts (stream processing)

## Task
Implement the agentic loop that ties everything together:
LLM call → stream response → execute tools → feed results back → repeat until done.
After this session, the agent can actually DO THINGS.

## Deliverables

### 1. src/opencode/logic/prompt.clj
System prompt construction:
- (build-system-prompt config session) → string
- Include: current working directory, OS info, date, available tools description
- Reference OpenCode's prompt construction from the TypeScript source for the exact wording
- Keep it simple for MVP — no skills, no custom instructions yet

### 2. src/opencode/logic/agent.clj
The agentic loop:
```clojure
(defn run-agent-loop!
  "Core loop: send messages to LLM, execute tool calls, repeat.
   Returns the final session with all messages appended.
   Publishes events to the event bus throughout."
  [provider session tools event-bus opts]
  ...)
```

Implementation:
- Build system prompt, prepare messages in Anthropic format
- Call (stream provider messages opts) to get event channel
- Consume stream channel:
  - :text-delta events → accumulate text, publish :llm/stream-delta to event bus
  - :tool-call events → collect tool calls
  - :done → finalize assistant message
  - :error → return anomaly
- After stream completes, append assistant message to session
- If assistant message has tool calls:
  - For each tool call: check permissions, execute tool, create tool result message
  - Publish :tool/executing and :tool/completed events
  - Append all tool results to session
  - LOOP: call LLM again with updated messages
- If no tool calls (finish-reason :stop): return final session
- Max iterations guard (default 25) to prevent infinite loops
- Publish :session/updated after each iteration

### 3. src/opencode/logic/permission.clj
Simple permission system for MVP:
- (check-permission tool-name context) → :allow | :ask | :deny
- Default rules: read tools → :allow, write/edit tools → :ask, bash → :ask
- If :ask → call (ui/ask-permission! ui-adapter tool-name params) and wait for response
- For now, --dangerously-skip-permissions flag auto-approves everything

### 4. src/opencode/adapter/persistence.clj
In-memory session store:
- Atom-backed: (atom {}) keyed by session ID
- (save-session! store session) → stores session
- (load-session store session-id) → returns session or nil
- (list-sessions store) → returns all sessions sorted by created-at desc
- (delete-session! store session-id) → removes session
- Wire as Integrant component :opencode/session-store
- Data shapes are plain maps — trivially replaceable with DataScript/SQLite later

### 5. src/opencode/adapter/ui/protocol.clj
UI adapter protocol:
```clojure
(defprotocol UIAdapter
  (display-text! [this text] "Display text to user")
  (display-tool-call! [this tool-name params] "Show tool being called")
  (display-tool-result! [this tool-name result] "Show tool result")
  (display-error! [this error] "Show error")
  (ask-permission! [this tool-name params] "Ask user permission. Returns :approved or :denied")
  (get-input! [this prompt] "Get text input from user. Returns string."))
```

### 6. src/opencode/adapter/ui/repl.clj
JLine readline implementation of UIAdapter:
- Use org.jline.reader.LineReader for input with history
- Display text with simple println (through the protocol — not direct)
- ask-permission! shows tool name + params, reads y/n
- Color output using ANSI codes for tool calls (cyan), errors (red), assistant text (white)

### 7. Wire everything in system.clj
Update Integrant system map:
- :opencode/config → config
- :opencode/event-bus → event bus
- :opencode/session-store → persistence (depends on config)
- :opencode/llm-provider → Anthropic (depends on config)
- :opencode/ui → REPL adapter (depends on config)

### 8. Update src/opencode/main.clj
The main interaction loop:
- Start system
- Create a new session
- Loop: get user input → run-agent-loop! → display response → repeat
- Handle /quit, /new (new session), /sessions (list)
- Ctrl+C graceful shutdown via shutdown hook

### 9. Tests
- agent_test.clj: mock LLM provider that returns canned responses
  - Test: simple text response (no tools)
  - Test: tool call → tool result → final response
  - Test: max iterations guard triggers
- permission_test.clj: default rules classify tools correctly

## Verification
- `clj -M -m opencode.main` starts, accepts input, calls Claude, shows streaming response
- Tool calls work: ask Claude to "list files in the current directory" → calls glob tool → shows results
- Permission prompts appear for write/bash tools
- `clj -M:test` all tests pass
- `clj-kondo --lint src test` zero errors

## THIS IS THE MVP MILESTONE
After this session, the application is a working agentic coding assistant.
It can: accept natural language input, call Claude, execute file and shell tools,
and iterate on tool results. It runs in a readline REPL interface.
```

---

## SESSION 6 (POST-MVP): Context Management + Compaction (~5-8 ACU)

```
Working repo: https://github.com/victorlga/opencode-rewrite
Reference repo: https://github.com/victorlga/opencode (dev branch)
Use playbook: !clj-migrate

Reference: packages/opencode/src/session/overflow.ts, compaction.ts

## Task
Add context window management: detect overflow, prune old tool outputs, and auto-compact.

## Deliverables

### 1. src/opencode/domain/conversation.clj
- (estimate-tokens text) → rough count (chars/4 heuristic for MVP)
- (conversation-token-count messages) → total estimated tokens
- (overflow? messages model-metadata) → true if approaching context limit
  Uses: model context window - max(output limit, 32000) - 20000 buffer
- (prune-tool-outputs messages keep-last-n) → messages with old tool outputs truncated
- (build-compaction-prompt messages) → user message asking for structured summary

### 2. Update logic/agent.clj
- Before each LLM call, check overflow?
- If overflow: first try prune-tool-outputs, recheck
- If still overflow: call LLM with compaction prompt to generate summary,
  replace conversation history with summary + recent messages
- Log compaction events to event bus

### 3. Tests
- conversation_test.clj: overflow detection, pruning, compaction prompt generation
```
