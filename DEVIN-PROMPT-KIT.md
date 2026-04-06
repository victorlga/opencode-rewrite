# Devin Prompt Kit: OpenCode TypeScript → Clojure Rewrite

## How to use this document

1. **AGENTS.md** → Commit to repo root of `victorlga/opencode-rewrite` (separate file provided)
2. **Knowledge Items** → Create each one at app.devin.ai/settings → Knowledge
3. **Playbook** → Create at app.devin.ai/settings/playbooks/create
4. **Session Prompts** → Run one at a time, in order. Each session = 1 PR.

---

## PART 1: DEVIN KNOWLEDGE ITEMS

Create these at app.devin.ai/settings → Knowledge. Pin each to the `opencode-rewrite` repo.

### Knowledge Item 1: "Clojure Idiom Requirements"

**Trigger:** When writing any Clojure code  
**Pin to:** victorlga/opencode-rewrite  
**Content:**

```
MANDATORY Clojure idioms for this project:

ITERATION:
- Use (map f coll), (reduce f init coll), (into [] xf coll) to build collections
- NEVER use (doseq ... (swap! atom conj ...)) to build collections
- Use (for [...] ...) for list comprehensions
- Use loop/recur ONLY when reduce doesn't fit (early exit, multiple accumulators)

COMPOSITION:
- Use -> for object-like transforms: (-> config :llm :model)
- Use ->> for sequence pipelines: (->> messages (filter user?) (map :content))
- Use some-> for nil-safe chains: (some-> response :body parse-json)
- Use cond-> for conditional transforms

STATE:
- atoms for uncoordinated mutable state (session store, config cache)
- core.async channels for event streams and concurrency
- NEVER use def inside a function for mutable state
- NEVER do blocking I/O in go blocks — use async/thread

STRUCTURE:
- Prefer plain maps over records (use records only for protocol dispatch)
- Namespaced keywords everywhere: :message/role, :session/id, :tool/name
- Destructure in function args and let bindings
- Functions return data, not nil-for-success

ERROR HANDLING:
- Return anomaly maps for expected errors: {:cognitect.anomalies/category :unavailable :message "API timeout"}
- Use ex-info only for bugs
- Never throw for expected conditions (API errors, validation failures, tool errors)
```

### Knowledge Item 2: "TypeScript-to-Clojure Mapping"

**Trigger:** When translating from the OpenCode TypeScript source  
**Pin to:** victorlga/opencode-rewrite  
**Content:**

```
TypeScript → Clojure translation patterns:

TypeScript interface/type    → Malli schema ([:map [:field type]])
TypeScript class             → Plain map + protocol (if polymorphism needed)
TypeScript enum              → Keyword set or Malli [:enum ...]
TypeScript namespace         → Clojure namespace (one per file)
Zod schema                   → Malli schema
async/await                  → core.async (thread for blocking, go for coordination)
EventEmitter / Bus           → core.async pub/sub
Promise<T>                   → core.async channel (promise-chan for single value)
ReadableStream / SSE         → core.async channel fed by async/thread
try/catch                    → Anomaly maps for expected errors, try/catch for I/O boundaries
Discriminated union          → Maps with :type key + multimethod or case dispatch
Array.map/filter/reduce      → map/filter/reduce (identical semantics)
Optional chaining ?.         → some->
Nullish coalescing ??        → (or x default)
Template literals            → (str ...) or (format ...)
import/export                → (require) / (ns ... (:require ...))
```

### Knowledge Item 3: "Project Libraries and Versions"

**Trigger:** When adding dependencies or imports  
**Pin to:** victorlga/opencode-rewrite  
**Content:**

```
EXACT library coordinates for this project. Use these versions ONLY:

org.clojure/clojure          {:mvn/version "1.12.0"}
org.clojure/core.async       {:mvn/version "1.7.790"}
integrant/integrant          {:mvn/version "0.11.0"}
aero/aero                    {:mvn/version "1.1.6"}
metosin/malli                {:mvn/version "0.19.1"}
hato/hato                    {:mvn/version "1.0.0"}
metosin/jsonista             {:mvn/version "0.3.13"}
babashka/process             {:mvn/version "0.5.22"}
babashka/fs                  {:mvn/version "0.5.30"}
org.jline/jline              {:mvn/version "3.26.3"}
org.clojure/tools.cli        {:mvn/version "1.1.230"}
cognitect-labs/anomalies     {:mvn/version "0.2.97"}

DEV ONLY:
djblue/portal                {:mvn/version "0.55.1"}
nrepl/nrepl                  {:mvn/version "1.1.0"}
lambdaisland/kaocha          {:mvn/version "1.91.1392"}
nubank/matcher-combinators   {:mvn/version "4.0.0"}

NEVER use: leiningen, clj-http (use hato), cheshire (use jsonista), clojure.java.shell (use babashka.process)
```

### Knowledge Item 4: "Anti-Patterns to Reject"

**Trigger:** When reviewing or writing Clojure code  
**Pin to:** victorlga/opencode-rewrite  
**Content:**

```
REJECT these patterns in code review. If you catch yourself writing them, rewrite.

BAD: (let [result (atom [])] (doseq [x coll] (swap! result conj (f x))) @result)
GOOD: (mapv f coll)

BAD: (def state (atom {}))  ;; at top of namespace for non-component state
GOOD: Pass state as a parameter or use Integrant component

BAD: (go (let [resp (http/post url body)] ...))  ;; blocking I/O in go block
GOOD: (async/thread (let [resp (http/post url body)] ...))

BAD: (defrecord Message [role content])  ;; unnecessary record
GOOD: {:message/role :user :message/content "hello"}  ;; plain map

BAD: (try ... (catch Exception e nil))  ;; swallowing errors
GOOD: (try ... (catch Exception e {::anom/category ::anom/fault :message (ex-message e)}))

BAD: (println "Result:" result)  ;; direct console output in business logic
GOOD: (ui/display! ui-adapter {:type :result :content result})

BAD: (require '[clojure.java.shell :as shell]) (shell/sh "ls")
GOOD: (require '[babashka.process :as p]) (-> (p/process ["ls"] {:out :string}) p/check :out)
```

---

## PART 2: DEVIN PLAYBOOK

Create at app.devin.ai/settings/playbooks/create  
**Name:** `!clj-migrate`  
**Macro:** `!clj-migrate`

### Procedure

```
For each module migration, follow this exact sequence:

1. READ the AGENTS.md in the opencode-rewrite repo root for architecture decisions
2. READ the TypeScript source file(s) being translated from victorlga/opencode
3. IDENTIFY all data structures, functions, imports, and side effects
4. CREATE the Clojure namespace file with:
   a. ns declaration with all requires
   b. Malli schemas for data structures (translating Zod schemas)
   c. Pure functions first, then side-effecting functions (marked with !)
   d. Rich comment block at the bottom for REPL exploration
5. CREATE corresponding test file with clojure.test + matcher-combinators
6. RUN `clj-kondo --lint src test` and fix all warnings
7. RUN `clj -M:test` and fix all failures
8. COMMIT with descriptive message and create PR

If you encounter an architectural decision not covered by AGENTS.md — STOP and ask me.
If you encounter a library choice not in Knowledge Item 3 — STOP and ask me.
If tests fail after 2 attempts — STOP and show me the error.
```

### Specifications

```
- All code goes in src/opencode/ or test/opencode/
- One namespace per file, matching directory structure
- Every public function has a docstring
- Every data boundary (API calls, file I/O, user input) validates with Malli
- Tests use (is (match? expected actual)) from matcher-combinators
- No Java class imports unless absolutely necessary (prefer Clojure wrappers)
```

### Forbidden Actions

```
- Do NOT modify deps.edn unless the session prompt explicitly says to
- Do NOT install new dependencies without asking
- Do NOT create leiningen project files
- Do NOT write imperative Clojure (see Anti-Patterns knowledge item)
- Do NOT skip tests — every namespace gets a test file
- Do NOT use def for mutable state inside functions
```

---

## PART 3: SESSION PROMPTS

Run these in order. One session per prompt. Each produces a PR.
After merging each PR, start the next session.

---

### SESSION 1: Project Skeleton + deps.edn + Config System (~5-8 ACU)

```
Working repo: https://github.com/victorlga/opencode-rewrite
Reference repo: https://github.com/victorlga/opencode (dev branch, TypeScript)
Use playbook: !clj-migrate

## Task
Create the project skeleton with deps.edn, Integrant system, and configuration loading.
This is the foundation — every future session builds on it.

## Deliverables

### 1. deps.edn
Create deps.edn at the repo root with ALL dependencies from Knowledge Item 3.
Include :dev, :test, and :build aliases.
The :test alias should use kaocha.
The :dev alias should include nrepl, portal, and test deps.

### 2. resources/config.edn
Create an Aero config file:
```edn
{:opencode
 {:llm {:provider #or [#env OPENCODE_PROVIDER "anthropic"]
        :api-key #env ANTHROPIC_API_KEY
        :model #or [#env OPENCODE_MODEL "claude-sonnet-4-20250514"]}
  :tools {:allowed #{:bash :read-file :write-file :edit-file :glob :grep}}
  :ui {:type :repl}
  :project {:directory #or [#env OPENCODE_PROJECT_DIR "."]}}}
```

### 3. src/opencode/config.clj
- Load config with Aero using (aero.core/read-config resource)
- Define Malli schema for the config shape
- Validate config on load, throw with clear error on invalid config
- Integrant init-key for :opencode/config

### 4. src/opencode/system.clj
- Integrant system map that currently only has :opencode/config
- (defn start! []) and (defn stop! [system]) helper functions
- Will be extended in future sessions

### 5. src/opencode/main.clj
- -main function that calls (system/start!) and prints "opencode-rewrite started"
- Parse --help and --version with tools.cli
- Verify it runs: `clj -M -m opencode.main`

### 6. test/opencode/config_test.clj
- Test config loading with a test EDN fixture
- Test Malli validation catches bad config
- Test env var override works

### 7. tests.edn (kaocha config)
Standard kaocha config at repo root.

## Verification
- `clj -M -m opencode.main` prints startup message and exits
- `clj -M:test` passes all tests
- `clj-kondo --lint src test` has zero errors
```

---

### SESSION 2: Message Domain + Event Bus (~5-8 ACU)

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

### SESSION 3: Anthropic Provider + SSE Streaming (~8-10 ACU)

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

### SESSION 4: Tool System — File Tools + Bash (~8-10 ACU)

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

### SESSION 5: The Agentic Loop — Bring It All Together (~8-10 ACU)

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

### SESSION 6 (POST-MVP): Context Management + Compaction (~5-8 ACU)

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

---

## PART 4: ACU BUDGET ESTIMATE

| Session | Description | Est. ACU | Running Total |
|---------|-------------|----------|---------------|
| 1 | Skeleton + Config | 5-8 | 5-8 |
| 2 | Messages + Event Bus | 5-8 | 10-16 |
| 3 | Anthropic + Streaming | 8-10 | 18-26 |
| 4 | Tool System | 8-10 | 26-36 |
| 5 | Agentic Loop (MVP) | 8-10 | 34-46 |
| 6 | Context Management | 5-8 | 39-54 |
| Buffer for retries | ~1 failed session | 8-10 | 47-64 |

**Total estimate: 47-64 ACU for core MVP + context management**
Your 60 ACU budget fits if sessions stay focused. Budget for 1 retry.

## PART 5: WORKFLOW CHECKLIST

Before first session:
- [ ] Commit AGENTS.md to opencode-rewrite repo root
- [ ] Create all 4 Knowledge Items in Devin settings
- [ ] Create the !clj-migrate Playbook in Devin settings
- [ ] Configure Devin machine: install Java 21+, Clojure CLI, clj-kondo
- [ ] Connect both repos in Devin (opencode as read-only reference, opencode-rewrite as target)
- [ ] Use Ask Devin to explore the TypeScript source architecture first (saves ACU)

Between sessions:
- [ ] Review PR thoroughly for imperative anti-patterns
- [ ] Merge PR before starting next session
- [ ] Check Session Insights for misleading Knowledge items
- [ ] Update Knowledge items if needed based on session results

After Session 5 (MVP):
- [ ] Manual testing: have a real conversation with the agent
- [ ] Test tool execution: file read, file write, bash commands
- [ ] Test error handling: bad API key, network failure, tool errors
