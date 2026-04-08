# opencode-rewrite

A data-oriented Clojure reimplementation of the [OpenCode](https://github.com/victorlga/opencode) AI coding agent. Functional, REPL-driven, and data-first.

## Overview

This project translates the TypeScript OpenCode agent into idiomatic Clojure, one module at a time. The architecture follows the Diplomat pattern — pure domain logic separated from side-effecting adapters, with Integrant managing the component lifecycle.

**Built so far:**
- **Session 1** — Config system: Aero-based EDN config loading, Malli schema validation, Integrant lifecycle, CLI entry point with `tools.cli`
- **Session 2** — Core data model: Message schemas and constructors, session manipulation functions, core.async pub/sub event bus
- **Session 3** — LLM provider abstraction: Protocol definition, model metadata registry, SSE stream parsing utilities
- **Session 4** — Anthropic provider: Full LLMProvider implementation with HTTP calls via hato, message format conversion, streaming via SSE/core.async, Integrant wiring
- **Session 5** — Tool system: Tool framework with registry, multimethod dispatch, JSON Schema conversion via Malli. Three tool implementations: `read_file`, `write_file`, `glob`
- **Session 6** — Complete tool suite: `edit_file` (search/replace with exact + whitespace-normalized matching), `bash` (shell execution with timeout + truncation), `grep` (ripgrep/grep with output parsing + truncation)

## How It Works

```
┌──────────────────────────────────────────────────────┐
│                     system.clj                        │
│  Integrant wires: :opencode/config                    │
│                   :opencode/event-bus                  │
│                   :opencode/llm-provider (→ config)    │
└────────┬──────────────────────┬───────────────────────┘
         │                      │
    config.clj           logic/event_bus.clj
    (Aero + Malli)       (core.async pub/sub)
                                │
                    publish! / subscribe! / close-bus!
                                │
         ┌──────────────────────┼──────────────────┐
         │                      │                   │
   domain/message.clj    domain/session.clj    adapter/llm/
   (Malli schemas,       (pure transforms,     ├── provider.clj (protocol)
    constructors)         token tracking)       ├── anthropic.clj (impl)
                                                └── model_registry.clj
   domain/tool.clj                             adapter/tool/
   (registry, schemas,                         ├── file_read.clj (read_file)
    multimethod dispatch,                      ├── file_write.clj (write_file)
    JSON Schema convert)                       ├── glob.clj (glob)
                                               ├── file_edit.clj (edit_file)
                                               ├── bash.clj (bash)
                                               └── grep.clj (grep)

                         logic/streaming.clj
                         (SSE parsing → core.async channels)
```

- **Domain layer** (`opencode.domain.*`) — Pure functions and Malli schemas. No I/O, no side effects. Messages are plain maps with namespaced keywords (`:message/role`, `:session/id`). Constructors validate via Malli and return anomaly maps on invalid data.
- **Adapter layer** (`opencode.adapter.*`) — Side-effecting code at the edges. The `LLMProvider` protocol defines the interface for LLM completions and streaming. The Anthropic adapter implements the protocol with real HTTP calls via hato and SSE stream parsing via core.async. The model registry stores static model metadata (context windows, costs, capabilities). Tool adapters (`adapter/tool/`) implement `execute-tool!` multimethods for file I/O, glob, edit, bash, and grep operations.
- **Logic layer** (`opencode.logic.*`) — Orchestration with managed side effects. The event bus uses core.async channels with sliding buffers for pub/sub. SSE streaming utilities parse Anthropic server-sent events into channel-based event streams.
- **Config** — EDN files read by Aero with `#env` tag literals. Malli validates at startup; invalid config returns cognitect anomaly maps.

## How to Run

```bash
# Start the application
clj -M -m opencode.main

# With flags
clj -M -m opencode.main --dangerously-skip-permissions

# Start a development REPL
clj -M:dev
```

## How to Test

```bash
# Run the full test suite (Kaocha)
clj -M:test

# Lint with clj-kondo (must be zero errors)
clj-kondo --lint src test
```

## Recent Changes (Session 6)

- Added `opencode.adapter.tool.file-edit` — `edit_file` tool:
  - Exact string match first, then whitespace-normalized match (trim each line), context snippet on failure
  - Replaces first occurrence only; reports when multiple matches exist
  - Gated behind `dangerous-mode?` context flag
  - Returns anomaly with nearest 5 lines when old_string not found
- Added `opencode.adapter.tool.bash` — `bash` tool:
  - Shell execution via `babashka.process` with configurable timeout (default 2 min)
  - Combined stdout+stderr, truncated at 50KB (UTF-8 safe boundary)
  - Non-zero exit is NOT an anomaly (returns as `:exit-code` in output)
  - Timeout returns `:busy` anomaly
- Added `opencode.adapter.tool.grep` — `grep` tool:
  - Prefers ripgrep (`rg`), falls back to `grep` if unavailable
  - Parses output to `file:line: content` format
  - Truncates at 100 matches with guidance message
  - Supports `--include`/`--glob` filtering
- Full tool suite now complete: `read_file`, `write_file`, `glob`, `edit_file`, `bash`, `grep`
- 82 tests, 205 assertions, 0 failures. clj-kondo: 0 errors, 0 warnings.
