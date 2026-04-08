# AGENTS.md — opencode-rewrite (Clojure)

## Project Overview

This is a Clojure reimplementation of [OpenCode](https://github.com/victorlga/opencode) (TypeScript).
We are NOT doing a line-by-line translation. We are building the most idiomatic Clojure possible,
inspired by how Nubank builds software: data-oriented, functional, REPL-driven.

Source repo (read-only reference): https://github.com/victorlga/opencode (TypeScript, `dev` branch)
Target repo (this repo): https://github.com/victorlga/opencode-rewrite

## Architecture Decisions

### System Lifecycle
- Use **Integrant** for component lifecycle (config-as-data, multimethod-based)
- Configuration via **Aero** (EDN with #env tag literals)
- All stateful resources (HTTP client, DB, event bus) are Integrant components

### Data & Validation
- **Malli** for all schemas (messages, tool params, config, API responses)
- Malli JSON Schema transform generates LLM tool schemas — single source of truth
- Data-oriented design: conversations are vectors of maps, sessions are maps
- Use namespaced keywords (e.g., :message/role, :session/id)

### LLM Providers
- **Clojure protocols** for provider abstraction (LLMProvider: complete, stream, models)
- **hato** for HTTP (wraps JDK 11+ HttpClient, supports HTTP/2 and streaming)
- **jsonista** for JSON (faster than Cheshire)
- SSE streaming parsed line-by-line, events pushed to core.async channels

### Concurrency & Events
- **core.async** for all async work
- NEVER do blocking I/O in go blocks (use async/thread or pipeline-blocking)
- Event bus via core.async pub/sub (mult/tap for broadcasting)
- Channels with explicit buffer strategies (sliding for UI, bounded for state)

### Tool System
- **Multimethods** for tool dispatch (dispatch on tool name string)
- Tool definitions are plain maps with Malli schemas for parameters
- **babashka.process** for shell execution
- **babashka.fs** for file operations

### Persistence
- **In-memory atoms** for MVP (session state, conversation history)
- Data model designed for easy migration to DataScript or SQLite later
- Session/message shapes must be serializable EDN from day one

### User Interface
- **JLine 3** for readline-style REPL interface (MVP)
- Architecture supports future TUI via clean event bus decoupling
- All user-facing I/O goes through a thin adapter layer, never direct println in business logic

### Error Handling
- **cognitect.anomalies** for expected errors (API failures, validation, tool errors)
- Return anomaly maps from functions, never throw for expected conditions
- **ex-info** only for programming errors / bugs
- Anomalies propagate cleanly through core.async channels

### Testing
- **clojure.test** + **nubank/matcher-combinators** for assertions
- Generative testing with Malli generators for data schemas
- Test tool implementations against known inputs/outputs

## Coding Standards — MANDATORY

### DO
- Prefer `map`, `reduce`, `transduce`, `into` over imperative iteration
- Use threading macros (`->`, `->>`, `some->`, `cond->`) for pipelines
- Use `let` destructuring extensively
- Keep functions pure; push side effects to the edges (adapters)
- Use `loop/recur` only when `reduce` doesn't fit (e.g., stateful iteration with early exit)
- Use `defmethod` for extending tool registry from any namespace
- Return data from functions, not nil-for-success
- Use rich comment blocks `(comment ...)` for development exploration

### DO NOT
- NEVER use `doseq` + `atom` for building collections (use `map`/`reduce`)
- NEVER use `def` inside functions for mutable state
- NEVER create Java-style class hierarchies
- NEVER use `leiningen` — this project uses `deps.edn` exclusively
- NEVER use `clojure.java.shell` — use `babashka.process` instead
- NEVER do blocking I/O inside `go` blocks
- NEVER skip Malli validation at adapter boundaries
- NEVER use println directly for user output — go through the UI adapter

### Naming Conventions
- Namespaces: `opencode.domain.*` (pure logic), `opencode.adapter.*` (side effects), `opencode.system` (wiring)
- kebab-case for all names (functions, keywords, namespaces)
- Predicate functions end with `?` (e.g., `tool-allowed?`)
- Side-effecting functions end with `!` (e.g., `execute-tool!`)
- Private functions use `defn-` or `^:private` metadata

## Project Structure

```
opencode-rewrite/
├── deps.edn
├── resources/config.edn
├── AGENTS.md
├── src/opencode/
│   ├── main.clj                 ;; Entry point, -main
│   ├── system.clj               ;; Integrant system map
│   ├── config.clj               ;; Aero config loading + Malli schema
│   ├── domain/
│   │   ├── message.clj          ;; Message schemas and constructors
│   │   ├── session.clj          ;; Session data manipulation (pure)
│   │   ├── conversation.clj     ;; Conversation building, truncation, compaction
│   │   └── tool.clj             ;; Tool definition schema, registry protocol
│   ├── adapter/
│   │   ├── llm/
│   │   │   ├── provider.clj     ;; LLMProvider protocol definition
│   │   │   ├── anthropic.clj    ;; Anthropic API implementation
│   │   │   └── model_registry.clj ;; Model metadata (context window, cost)
│   │   ├── tool/
│   │   │   ├── bash.clj         ;; Shell execution tool
│   │   │   ├── file_read.clj    ;; File reading tool
│   │   │   ├── file_write.clj   ;; File writing tool
│   │   │   ├── file_edit.clj    ;; File editing tool (search/replace)
│   │   │   ├── glob.clj         ;; File pattern matching
│   │   │   └── grep.clj         ;; Content search
│   │   ├── persistence.clj      ;; Session/message storage (atom-backed MVP)
│   │   └── ui/
│   │       ├── protocol.clj     ;; UI adapter protocol
│   │       └── repl.clj         ;; JLine readline interface
│   └── logic/
│       ├── agent.clj            ;; Agentic loop (the core)
│       ├── permission.clj       ;; Permission checking and user approval
│       ├── prompt.clj           ;; System prompt construction
│       ├── streaming.clj        ;; SSE parsing, core.async stream infra
│       └── event_bus.clj        ;; core.async pub/sub event system
└── test/opencode/
    ├── domain/
    │   ├── message_test.clj
    │   ├── session_test.clj
    │   └── conversation_test.clj
    ├── adapter/
    │   └── tool/
    │       ├── bash_test.clj
    │       └── file_read_test.clj
    └── logic/
        └── agent_test.clj
```

## Build & Run Commands

```bash
# Start REPL (development)
clj -M:dev

# Run tests
clj -M:test

# Lint
clj-kondo --lint src test

# Run the application (suppresses JLine native-access warnings)
clj -M:run

# Build uberjar
clj -T:build uber
```

## Reference Material

- Source TypeScript codebase: https://github.com/victorlga/opencode (dev branch)
- DeepWiki analysis: https://deepwiki.com/anomalyco/opencode
- Anthropic API docs: https://docs.anthropic.com/en/api
- Malli docs: https://github.com/metosin/malli
- core.async guide: https://clojure.org/guides/async_walkthrough
- Integrant docs: https://github.com/weavejester/integrant
- hato docs: https://github.com/gnarroway/hato
- babashka.process: https://github.com/babashka/process
- babashka.fs: https://github.com/babashka/fs
