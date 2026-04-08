# opencode-rewrite

A data-oriented Clojure reimplementation of the [OpenCode](https://github.com/victorlga/opencode) AI coding agent. Functional, REPL-driven, and data-first.

## Overview

This project translates the TypeScript OpenCode agent into idiomatic Clojure, one module at a time. The architecture follows the Diplomat pattern — pure domain logic separated from side-effecting adapters, with Integrant managing the component lifecycle.

**Built so far:**
- **Session 1** — Config system: Aero-based EDN config loading, Malli schema validation, Integrant lifecycle, CLI entry point with `tools.cli`
- **Session 2** — Core data model: Message schemas and constructors, session manipulation functions, core.async pub/sub event bus
- **Session 3** — LLM provider abstraction: Protocol definition, model metadata registry, SSE stream parsing utilities
- **Session 4** — Anthropic provider: Full LLMProvider implementation with HTTP calls via hato, message format conversion, streaming via SSE/core.async, Integrant wiring

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
                         logic/streaming.clj
                         (SSE parsing → core.async channels)
```

- **Domain layer** (`opencode.domain.*`) — Pure functions and Malli schemas. No I/O, no side effects. Messages are plain maps with namespaced keywords (`:message/role`, `:session/id`). Constructors validate via Malli and return anomaly maps on invalid data.
- **Adapter layer** (`opencode.adapter.*`) — Side-effecting code at the edges. The `LLMProvider` protocol defines the interface for LLM completions and streaming. The Anthropic adapter implements the protocol with real HTTP calls via hato and SSE stream parsing via core.async. The model registry stores static model metadata (context windows, costs, capabilities).
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

## Recent Changes (Session 4)

- Added `opencode.adapter.llm.anthropic` — Full `LLMProvider` implementation for the Anthropic Messages API:
  - `messages->anthropic` / `anthropic-response->message` for bidirectional message format conversion
  - `complete` — synchronous POST to `/v1/messages`, parses JSON response to domain assistant message
  - `stream` — streaming POST with `{:stream true}`, SSE events parsed via `sse-events->channel!` and transformed into domain stream events (`:text-delta`, `:done`, `:error`)
  - Tool call data is accumulated across `content_block_start` / `input_json_delta` / `content_block_stop` events and included in the final `:done` message
  - HTTP error mapping to cognitect anomaly categories (401→forbidden, 429→busy, 500+→fault)
  - Integrant component `:opencode/llm-provider` wired with dependency on `:opencode/config`
- Updated `opencode.system` — Added `:opencode/llm-provider {:config (ig/ref :opencode/config)}` to system config
- 36 tests, 132 assertions, 0 failures. clj-kondo: 0 errors, 0 warnings.
