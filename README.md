# opencode-rewrite

A data-oriented Clojure reimplementation of the [OpenCode](https://github.com/victorlga/opencode) AI coding agent, inspired by how Nubank builds software: functional, REPL-driven, and data-first.

## Overview

This project translates the TypeScript OpenCode agent into idiomatic Clojure, one module at a time. The architecture follows the Diplomat pattern — pure domain logic separated from side-effecting adapters, with Integrant managing the component lifecycle.

**Built so far:**
- **Session 1** — Config system: Aero-based EDN config loading, Malli schema validation, Integrant lifecycle, CLI entry point with `tools.cli`
- **Session 2** — Core data model: Message schemas and constructors, session manipulation functions, core.async pub/sub event bus

## How It Works

```
┌─────────────────────────────────────────────────┐
│                  system.clj                      │
│  Integrant wires: :opencode/config               │
│                   :opencode/event-bus             │
└────────┬──────────────────────┬──────────────────┘
         │                      │
    config.clj           logic/event_bus.clj
    (Aero + Malli)       (core.async pub/sub)
                                │
                    publish! / subscribe! / close-bus!
                                │
         ┌──────────────────────┼──────────────────┐
         │                      │                   │
   domain/message.clj    domain/session.clj    (future: adapters)
   (Malli schemas,       (pure transforms,
    constructors)         token tracking)
```

- **Domain layer** (`opencode.domain.*`) — Pure functions and Malli schemas. No I/O, no side effects. Messages are plain maps with namespaced keywords (`:message/role`, `:session/id`). Constructors validate via Malli and return anomaly maps on invalid data.
- **Logic layer** (`opencode.logic.*`) — Orchestration with managed side effects. The event bus uses core.async channels with sliding buffers for pub/sub.
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

## Recent Changes (Session 2)

- Added `opencode.domain.message` — Malli schemas for Role, ToolCall, UserMessage, AssistantMessage, ToolResultMessage, SystemMessage, and a Message union. Constructor functions (`system-message`, `user-message`, `assistant-message`, `tool-result`) validate with Malli and return cognitect anomaly maps on invalid data.
- Added `opencode.domain.session` — Session schema and pure functions: `create-session`, `append-message`, `get-messages`, `session-token-count`, `update-tokens`.
- Added `opencode.logic.event-bus` — core.async pub/sub event bus with `create-bus`, `publish!`, `subscribe!`, `unsubscribe!`, `close-bus!`. Wired as Integrant component `:opencode/event-bus` with `init-key`/`halt-key!`.
- Updated `system.clj` to include `:opencode/event-bus {}` in the default system config.
- Comprehensive tests for all three namespaces using `matcher-combinators`.
