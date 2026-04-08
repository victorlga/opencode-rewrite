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
- **Session 7** — Infrastructure for the agentic loop: UI adapter protocol + JLine REPL implementation, atom-backed session persistence, permission system (allow/ask rules with dangerous-mode override)

## How It Works

```
┌──────────────────────────────────────────────────────┐
│                     system.clj                        │
│  Integrant wires: :opencode/config                    │
│                   :opencode/event-bus                  │
│                   :opencode/llm-provider (→ config)    │
│                   :opencode/ui                         │
│                   :opencode/session-store              │
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

   adapter/ui/                                 adapter/persistence.clj
   ├── protocol.clj (UIAdapter)                (atom-backed SessionStore)
   └── repl.clj (JLine ReplUI)

                         logic/streaming.clj
                         (SSE parsing → core.async channels)

                         logic/permission.clj
                         (allow/ask rule table)
```

- **Domain layer** (`opencode.domain.*`) — Pure functions and Malli schemas. No I/O, no side effects. Messages are plain maps with namespaced keywords (`:message/role`, `:session/id`). Constructors validate via Malli and return anomaly maps on invalid data.
- **Adapter layer** (`opencode.adapter.*`) — Side-effecting code at the edges. The `LLMProvider` protocol defines the interface for LLM completions and streaming. The Anthropic adapter implements the protocol with real HTTP calls via hato and SSE stream parsing via core.async. The model registry stores static model metadata (context windows, costs, capabilities). Tool adapters (`adapter/tool/`) implement `execute-tool!` multimethods for file I/O, glob, edit, bash, and grep operations. The `UIAdapter` protocol abstracts all user-facing I/O (display, permission prompts, input); `ReplUI` implements it with JLine 3 and ANSI colors. `SessionStore` provides atom-backed persistence for session data.
- **Logic layer** (`opencode.logic.*`) — Orchestration with managed side effects. The event bus uses core.async channels with sliding buffers for pub/sub. SSE streaming utilities parse Anthropic server-sent events into channel-based event streams. The permission module checks tool rules (allow/ask) with a dangerous-mode override.
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

## Recent Changes (Session 7)

- Added `opencode.adapter.ui.protocol` — `UIAdapter` protocol:
  - Six methods: `display-text!`, `display-tool-call!`, `display-tool-result!`, `display-error!`, `ask-permission!`, `get-input!`
  - All user-facing I/O goes through this protocol (AGENTS.md compliance)
- Added `opencode.adapter.ui.repl` — `ReplUI` JLine implementation:
  - ANSI color-coded output (white text, cyan tool calls, dim results, red errors, yellow permission prompts)
  - JLine 3 `LineReader` for interactive input and permission prompts
  - Wired as Integrant component `:opencode/ui`
- Added `opencode.adapter.persistence` — Atom-backed `SessionStore`:
  - `save-session!`, `load-session`, `list-sessions` (sorted newest first), `delete-session!`
  - Designed for easy migration to DataScript or SQLite later
  - Wired as Integrant component `:opencode/session-store`
- Added `opencode.logic.permission` — Permission checking:
  - Default rules: read-only tools (read_file, glob, grep) → `:allow`; write/shell tools → `:ask`
  - `--dangerously-skip-permissions` overrides all to `:allow`
  - `request-permission!` delegates to UI adapter for interactive approval
- Updated `system.clj` with `:opencode/ui` and `:opencode/session-store` components
- 98 tests, 233 assertions, 0 failures. clj-kondo: 0 errors, 0 warnings.
