# opencode-rewrite

A data-oriented Clojure reimplementation of the [OpenCode](https://github.com/victorlga/opencode) AI coding agent. Functional, REPL-driven, and data-first.

## Overview

This project translates the TypeScript OpenCode agent into idiomatic Clojure, one module at a time. The architecture follows the Diplomat pattern — pure domain logic separated from side-effecting adapters, with Integrant managing the component lifecycle.

**Built so far:**
- **Session 1** — Config system: Aero-based EDN config loading, Malli schema validation, Integrant lifecycle, CLI entry point with `tools.cli`
- **Session 2** — Core data model: Message schemas and constructors, session manipulation functions, core.async pub/sub event bus
- **Session 3** — LLM provider abstraction: Protocol definition, model metadata registry, SSE stream parsing utilities

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
   domain/message.clj    domain/session.clj    adapter/llm/
   (Malli schemas,       (pure transforms,     ├── provider.clj (protocol)
    constructors)         token tracking)       └── model_registry.clj
                                                    (model metadata)
                         logic/streaming.clj
                         (SSE parsing → core.async channels)
```

- **Domain layer** (`opencode.domain.*`) — Pure functions and Malli schemas. No I/O, no side effects. Messages are plain maps with namespaced keywords (`:message/role`, `:session/id`). Constructors validate via Malli and return anomaly maps on invalid data.
- **Adapter layer** (`opencode.adapter.*`) — Side-effecting code at the edges. The `LLMProvider` protocol defines the interface for LLM completions and streaming. The model registry stores static model metadata (context windows, costs, capabilities).
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

## Recent Changes (Session 3)

- Added `opencode.adapter.llm.provider` — `LLMProvider` protocol with `complete`, `stream`, and `list-models` methods. `CompletionOpts` Malli schema for request options (system prompt, tools, max-tokens, temperature).
- Added `opencode.adapter.llm.model-registry` — Static model metadata for Claude Sonnet 4 and Claude Haiku 3.5. `ModelInfo` Malli schema. Lookup functions: `get-model` (returns anomaly for unknown IDs), `models-for-provider`, `all-models`, `validate-model`.
- Added `opencode.logic.streaming` — SSE parsing for Anthropic Messages API. `parse-sse-event` parses individual events, `read-sse-events` reads a BufferedReader as a lazy seq, `sse-events->channel` wraps it in `async/thread` for channel-based consumption. Ping events are filtered out.
- Comprehensive tests for model registry and streaming using `matcher-combinators`.
