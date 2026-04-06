# OpenCode TypeScript: a complete architectural deep-dive

**OpenCode is a modular, event-driven AI coding agent built as a TypeScript monorepo on Bun, using a client/server architecture where a Hono-based backend orchestrates LLM interactions, tool execution, and session persistence via SQLite, while multiple thin clients (TUI, web, desktop, VS Code) connect over REST + Server-Sent Events.** The project sits at **137K+ stars** with v1.3.15 released April 4, 2026 — making it the most popular open-source coding agent. Originally forked from a Go-based CLI by Dax Raad and Adam Doty of the SST team (now Anomaly Co), the TypeScript rewrite leverages the Vercel AI SDK for provider-agnostic model access across **23+ providers and 75+ models**, with a sophisticated agentic loop, permission system, and plugin architecture. This report dissects every major subsystem.

---

## The monorepo: Bun workspaces with 20+ packages

OpenCode uses **Bun** as both package manager and runtime, with **Turbo** orchestrating builds across a workspace containing **20+ packages** in three tiers. The default branch is `dev`, the repo has accumulated **10,484 commits**, and CLI binaries are compiled via `bun compile` into self-contained executables for 8+ platforms.

The **core platform** consists of `packages/opencode/` (the server, CLI, tool system, and TUI — the heart of the project), `packages/sdk/js/` (the `@opencode-ai/sdk` TypeScript SDK auto-generated from an OpenAPI spec), and `packages/plugin/` (the `@opencode-ai/plugin` development kit defining hook interfaces). UI packages include `packages/ui/` (a shared **SolidJS** component library with Tailwind), `packages/app/` (shared web/desktop application shell), `packages/desktop/` (Tauri v2 native app), `packages/web/` (Astro documentation site), and `sdks/vscode/` (VS Code extension). A separate console tier (`packages/console/`) provides the SaaS management platform built on SolidStart, Hono on Cloudflare Workers, and Drizzle ORM.

Key dependencies paint a clear architectural picture. The **Vercel AI SDK v6** (`ai@6.0.138`) provides the LLM abstraction layer, with dedicated provider packages for Anthropic, OpenAI, Google, Azure, Bedrock, and a dozen more. **Hono** (`4.10.7`) serves as the HTTP framework. **Drizzle ORM** (`1.0.0-beta.19`) on Bun's native SQLite handles persistence. **Zod** provides runtime validation everywhere — from config schemas to tool parameters to event payloads. The TUI is powered by **@opentui/solid**, a custom SolidJS reconciler built on a **native Zig rendering core** created by the same team (open-sourced at `anomalyco/opentui`, 10K+ stars). Other notable dependencies include `web-tree-sitter` for Bash command analysis, `diff` for patch generation, `fuzzysort` for fuzzy matching, and `yargs` for CLI parsing.

Build and dev setup is straightforward: `bun install` at the root, `bun run build` triggers Turbo, and the compile step bakes `OPENCODE_VERSION`, `OPENCODE_MIGRATIONS`, and `OPENCODE_CHANNEL` into standalone binaries. Desktop builds use Tauri (and an Electron variant), with Windows binaries signed via Azure Trusted Signing. Distribution covers npm (`opencode-ai`), Homebrew, Scoop, Chocolatey, AUR, Docker (`ghcr.io/anomalyco/opencode`), and Nix.

---

## Client/server architecture with event-driven state management

OpenCode's architecture separates a **stateful backend server** from multiple **stateless thin clients**. The server — a Hono application created by `Server.App()` in `packages/opencode/src/server/server.ts` — owns all business logic: session management, LLM orchestration, tool execution, and SQLite persistence. Clients communicate exclusively through **REST endpoints** and a **Server-Sent Events stream** at `GET /event`.

The REST API is organized into route modules: `/session` (CRUD, prompting, sharing, commands), `/project` (project management, git init), `/provider` (provider discovery, model listing), `/config` (configuration management), `/file` (filesystem operations), `/mcp` (MCP server management), `/pty` (terminal sessions), `/question` and `/permission` (interactive user prompts), and `/global` (health checks, events). The server supports optional HTTP Basic Auth via `OPENCODE_SERVER_PASSWORD`, CORS for Tauri and web clients, and mDNS discovery.

The **event bus** (`packages/opencode/src/bus/`) is the backbone of state synchronization. All state mutations publish typed events through `Bus.publish()`, with each event defined via `BusEvent.define("event.type", zodSchema)` — giving both runtime validation and TypeScript type inference. **19+ event types** cover session lifecycle (`session.created`, `session.updated`, `session.deleted`, `session.status`), message streaming (`message.updated`, `message.part.updated` with `delta` fields for incremental text), interactive flows (`permission.asked`, `permission.replied`, `question.asked`), and system events (`server.connected`, `todo.updated`). The SSE endpoint subscribes to all bus events, serializes them as JSON, and forwards them to connected clients with **30-second heartbeats** preventing connection timeouts.

---

## Initialization: from CLI entry to worker threads

The CLI entry point at `packages/opencode/src/index.ts` uses **yargs** to register commands: `tui` (default), `serve`, `run`, `web`, `attach`, `spawn`, `acp`, and `github`. Global middleware initializes logging, sets `OPENCODE_CLIENT`, and configures log targets.

The default `opencode` command triggers the **worker thread model**. The main thread spawns a **Bun worker thread** (`tui/worker.ts`) that boots the OpenCode server internally, then communicates via an RPC protocol supporting `server()`, `fetch()`, `subscribe()`, `reload()`, and `shutdown()` messages. The main thread runs the TUI renderer via the `tui()` function in `app.tsx`, which connects to the server URL received from the worker. This architecture provides **process isolation** between rendering and business logic while keeping everything in a single OS process.

The `bootstrap()` function in `cli/bootstrap.ts` wraps every command's execution, initializing the project `Instance` context for the working directory and ensuring cleanup via `defer()`. The **`Instance.state()` pattern** is the foundational scoping mechanism — a lazily-initialized, per-project-directory cached state container used by the Config, LSP, MCP, Plugin, Tool, and Session systems. Resources load on-demand via a `lazy()` utility to minimize startup time.

---

## The 7-layer configuration cascade

OpenCode's configuration system in `packages/opencode/src/config/config.ts` implements a **7-layer merge** via `Config.state()`, where each successive layer overrides the previous through `mergeConfigConcatArrays()`:

1. **Remote well-known** (lowest priority) — fetched from `.well-known/opencode` endpoints for org defaults
2. **Global user config** — `~/.config/opencode/opencode.json(c)`
3. **Custom config file** — via `OPENCODE_CONFIG` environment variable
4. **Project config** — `opencode.json(c)` in the project root
5. **Convention directories** — `.opencode/` scanned for `opencode.json`, `agents/*.md`, `commands/*.md`, `plugins/*.{ts,js}`
6. **Inline config** — `OPENCODE_CONFIG_CONTENT` environment variable (JSON string)
7. **Managed config** (highest) — enterprise directory at `/Library/Application Support/opencode` (macOS), `%ProgramData%\opencode` (Windows), or `/etc/opencode` (Linux)

Arrays like `plugin` and `instructions` are concatenated across layers and deduplicated via `deduplicatePlugins()`. After merging, normalization migrates deprecated fields (`mode` → agent, `tools` → permission), applies environment variable overrides from **30+ `OPENCODE_*` flags** (feature toggles for experimental models, LSP, plan mode, auto-compaction, etc.), and defaults the username to the OS user.

The config schema is defined as a Zod schema (`Config.Info`) covering `model`, `provider` (per-provider options), `mcp` (server definitions as a `McpLocal | McpRemote` discriminated union), `permission` (rules), `agent` (named agent configurations), `plugin` (specifiers), `instructions` (additional system prompt files/URLs), `command`, `keybind`, `theme`, `share`, `compaction`, and `server` settings. JSON with Comments is supported via `jsonc-parser`.

---

## Provider system: wrapping 23+ LLM services through the AI SDK

The provider abstraction in `packages/opencode/src/provider/provider.ts` uses a **namespace-based functional pattern** rather than classes. The core type `Provider.Model` carries comprehensive metadata: `id` (format: `providerID/modelID`), `api` (npm package + endpoint URL + API-specific model ID), `capabilities` (temperature, tool calls, reasoning, modalities), `cost` (input/output/cache per 1M tokens), `limit` (context/input/output token limits), and `status` (alpha/beta/deprecated/active).

Provider state maintains three caches: a `providers` object mapping IDs to metadata, a `models` Map caching initialized `LanguageModelV2` instances by configuration hash, and an `sdk` Map caching SDK instances by `xxHash32` of package+options. **`Provider.getModel()`** is the primary factory — it parses the model string, looks up the provider, checks the cache, loads the SDK through a **three-tier system** (bundled direct imports → custom loaders with provider-specific logic → dynamic `import()` for unbundled packages), calls `sdk.languageModel(modelID)`, and wraps the result with `ProviderTransform.languageModel()` middleware for reasoning extraction.

The **23+ bundled providers** include Anthropic, OpenAI, Google, Google Vertex, Amazon Bedrock, Azure, Groq, Together AI, Cerebras, DeepSeek, Mistral, Perplexity, xAI, GitHub Copilot, GitLab Duo, OpenRouter, Cloudflare AI Gateway, Venice, SAP AI Core, and OpenCode's own Zen/Go services. Local models connect via OpenAI-compatible endpoints (Ollama, llama.cpp, LM Studio). Each provider has a **custom loader** handling quirks: Anthropic injects beta headers for interleaved thinking; OpenAI routes through the responses API; Amazon Bedrock chains through `fromNodeProviderChain()` with complex region prefix logic; Google Vertex injects access tokens via custom `fetch()`; GitLab Duo handles OAuth with `aiGatewayHeaders`.

Model metadata comes from **models.dev** (`packages/opencode/src/provider/models.ts`), a community-contributed TOML catalog refreshed every **60 minutes** with a 10-second timeout and bundled fallback snapshot. Authentication resolves through env vars → config file `apiKey` → OAuth tokens in `~/.local/share/opencode/auth.json` → well-known auth endpoints, with `OAUTH_DUMMY_KEY` ("opencode-oauth-dummy-key") as a sentinel indicating OAuth is configured.

---

## The agentic loop: a state machine in `SessionPrompt.loop()`

The core agent loop lives in `packages/opencode/src/session/prompt.ts` (lines 232–726). **`SessionPrompt.prompt()`** creates the user message, then calls **`SessionPrompt.loop()`** — a while-loop state machine that iterates until the assistant explicitly stops or an error occurs.

Each iteration scans message history to find the last user message, last assistant message, and pending tasks. The **decision logic** is:

- `finish === "stop"` → stop (AI explicitly ended)
- `finish === "length"` → stop (hit output token limit)
- `finish === "error"` → stop
- `finish === "tool-calls"` → **continue** (tools executed, need next turn)
- `finish === "unknown"` → continue (safe default)
- Context overflow detected → **compact** (trigger summarization)

Before calling the LLM, the loop processes a **task queue** (LIFO): `SubtaskPart` entries spawn child sessions via `TaskTool`, and `CompactionPart` entries trigger `SessionCompaction.process()`. A **max steps guard** (from `Config.Agent.steps`) injects a forced text-only prompt at the limit to prevent infinite loops. **Doom loop protection** in `SessionProcessor` detects repeated identical tool calls and throws `DoomLoopError`.

Streaming happens through **`SessionProcessor.process()`** in `session/processor.ts`, which calls `LLM.stream()` (using Vercel AI SDK's `streamText()`) and iterates over the async stream. Chunks are mapped to **message parts**: `text-delta` → `TextPart`, `reasoning-delta` → `ReasoningPart`, `tool-call-delta` → `ToolPart` (pending), `tool-call` → tool execution, `step-finish` → token recording, `finish` → message completion. Each part creation or update publishes `MessageV2.Event.PartUpdated` with both the full part and a `delta` field, enabling **real-time streaming to all connected clients** via SSE.

Tool execution follows a clear flow: update `ToolPart` to `running`, fire `tool.execute.before` plugin hook, call `tool.execute(args, context)`, fire `tool.execute.after` hook, update to `completed` or `error`. The **system prompt** is assembled in layers: agent-specific prompt (or provider-specific default) + environment context + user overrides, with `ProviderTransform.applyCaching()` marking the last 2 system and 2 conversation messages with cache control markers for providers that support prompt caching (Anthropic, Bedrock, OpenRouter).

Error handling uses `SessionRetry.retryable()` with **exponential backoff** respecting `retry-after` headers. Context overflow errors matching patterns like "maximum context length" trigger compaction rather than retry. Default request timeout is **5 minutes**.

---

## Tool system: `Tool.define()` with Zod validation and per-file locking

Tools are created through `Tool.define()` in `packages/opencode/src/tool/tool.ts`, which accepts either a plain object (eager) or an async factory function (lazy — used by BashTool for deferred `web-tree-sitter` loading). Every tool provides `id`, `description`, a **Zod schema** for parameters, and an async `execute` function receiving `Tool.Context<M>` — a generic context carrying `sessionID`, `abort` (AbortSignal), `metadata()` (callback for live progress streaming), `ask()` (permission request method), and `extra` (internal context flags).

The wrapper automatically validates parameters against the Zod schema, then applies **output truncation** (2,000 lines / 50KB limit) via `Truncate.output`. Truncated results write full output to a temp file and instruct the model to use Grep/Read for the complete content.

**18+ built-in tools** cover the full coding workflow:

- **File operations**: `ReadTool` (with line ranges, binary/image/PDF detection, 50KB cap), `EditTool` (old_string/new_string replacement with fuzzy matching fallbacks for whitespace tolerance), `WriteTool` (full file overwrite), `PatchTool`/`ApplyPatchTool` (unified diff application), `BatchTool` (multi-file edits)
- **Shell**: `BashTool` (PTY execution with **web-tree-sitter** static analysis for command/path resolution, 2-minute default timeout, streaming output via `ctx.metadata()`)
- **Search**: `GrepTool` (ripgrep-powered), `GlobTool` (pattern matching), `ListTool` (directory listing), `CodeSearchTool` (public repo search), `WebSearchTool`, `WebFetchTool`
- **Agent**: `TaskTool` (subagent delegation creating child sessions), `SkillTool` (SKILL.md content loading)
- **Tracking**: `TodoWriteTool`/`TodoReadTool` (session-scoped task lists)
- **Special**: `QuestionTool` (interactive user prompts), `InvalidTool` (graceful handling of bad tool calls)

The **EditTool** deserves special attention. It uses `FileTime.withLock()` to acquire a **per-file semaphore** ensuring atomic read-validate-write sequences. `FileTime.read()` records file state (mtime, ctime, size) per session, and `FileTime.assert()` validates the file hasn't changed since last read — preventing stale edits from concurrent modifications. The edit strategy tries exact string matching first, then falls back to indentation-normalized and whitespace-tolerant matching. After editing, it triggers code formatting via `Format.file()` and returns LSP diagnostics via `LSP.touchFile()`.

The **ToolRegistry** (`tool/registry.ts`) aggregates tools from four sources: built-in (statically imported), custom (`.opencode/tools/` directory scan), plugin-registered, and MCP-provided. It dynamically filters tools based on model capabilities — GPT models get `apply_patch` instead of `edit/write`, web search requires the OpenCode provider or Exa flag, and experimental tools gate behind feature flags.

---

## Permission system: multi-layered rules with wildcard matching

Permissions are evaluated through a **multi-layered merge** system. Rules combine from hardcoded defaults (basic safety like `*.env` requiring `ask`), user config (`opencode.json` `permission` key), agent-specific rules (per-agent `.md` frontmatter), and session-level overrides — with each layer taking higher priority. Each permission rule specifies an **action**: `allow` (auto-approve), `ask` (prompt user), or `deny` (block entirely).

At runtime, when a tool calls `ctx.ask()`, the system evaluates patterns against the merged ruleset using `Wildcard.match()`. If not auto-approved, a `permission.asked` event fires via SSE, the TUI displays a confirmation dialog, and the tool suspends until the user responds. Users can approve `once` (this invocation only) or `always` (stores broader wildcard patterns in a session-scoped approved map for future auto-approval — e.g., approving `ls -la` stores `ls *`). The `--dangerously-skip-permissions` flag ("YOLO mode") bypasses all prompts with a prominent warning.

External directory access triggers a special `external_directory` permission via `assertExternalDirectory()` whenever any tool targets paths outside `Instance.directory`.

---

## Sessions and messages: SQLite with Drizzle ORM

Session persistence uses **SQLite** (via Bun's native SQLite with WAL mode) at `~/.local/share/opencode/opencode.db`, managed through **Drizzle ORM**. Three primary tables store the data: `SessionTable` (id, slug, project_id, directory, title, version, timestamps, permission rules), `MessageTable` (id, session_id, timestamps, full JSON `data` column), and `PartTable` (id, session_id, message_id, timestamps, JSON `data`). Foreign keys enforce cascade deletes.

Session IDs use **descending ULIDs** (prefix `ses`) so newer sessions sort first. Human-readable slugs are generated via `Slug.create()`. Sessions support **forking** (copying up to a message cutoff with `(fork #N)` suffix), **reverting** to earlier states, and **sharing** via URL. `Session.listGlobal()` provides cursor-based pagination across all projects.

Messages follow a **two-tier discriminated union**: `MessageV2.User` (carrying agent, model, variant, system prompt override, and parts like `TextPart`, `FilePart`, `AgentPart`, `SubtaskPart`, `CompactionPart`) and `MessageV2.Assistant` (carrying finish reason, token counts, cost, error, and parts like `TextPart`, `ReasoningPart`, `ToolPart`, `StepFinishPart`, `SnapshotPart`, `PatchPart`). The `ToolPart` itself has a **4-state machine** (`pending` → `running` → `completed`/`error`), each state carrying progressively more metadata.

`MessageV2.toModelMessage()` transforms internal parts into AI SDK content types, with provider-specific adjustments: Anthropic gets empty text parts removed and tool call IDs sanitized; Mistral normalizes tool IDs to 9 alphanumeric characters; interleaved thinking is extracted to provider-specific fields.

---

## Context management: overflow detection, pruning, and compaction

Context window management operates through `session/overflow.ts` and `session/compaction.ts`. The **usable context** is calculated as `model.limit.context - max(model.limit.output, 32000) - 20000` (the 20K buffer prevents edge-case overflows). After each assistant message, `SessionCompaction.isOverflow()` checks whether `input + output + cache.read` exceeds this limit.

When overflow triggers, a `CompactionPart` is queued. Processing happens in two phases: first, **tool output pruning** scans backward through messages, skipping the last 2 user turns, and replaces old tool outputs exceeding **40K tokens** with `[TOOL OUTPUT PRUNED]` (minimum **20K tokens** saved to justify pruning; `skill` tools are never pruned). Second, a **compaction agent** generates a structured summary covering Goal, Instructions, Discoveries, and Accomplished tasks. For overflow scenarios, media attachments (images, PDFs) are stripped and replaced with text placeholders like `[Attached image/jpeg: filename.jpg]`.

If compaction itself triggers another overflow (the conversation is too large even after summarization), a `ContextOverflowError` terminates the session — an **unrecoverable state**.

Token tracking stores `input`, `output`, `reasoning`, `cache.read`, and `cache.write` counts on each `MessageV2.Assistant`, with cost calculated from model pricing data via `Session.getUsage()` and displayed in the TUI sidebar.

---

## TUI: SolidJS rendering to the terminal via a Zig native core

The TUI is a **SolidJS application** rendered to the terminal through **@opentui/solid** — a custom SolidJS reconciler that maps reactive components to native renderables in OpenTUI's Zig core (using the **Yoga layout engine** for flexbox-style positioning). The renderer targets **60fps** with Kitty keyboard protocol support.

The `tui()` function initializes by detecting terminal background color via OSC 11, then wraps the root `App` component in **~16 nested context providers**: `ArgsProvider` (CLI args), `ExitProvider` (cleanup), `KVProvider` (UI preferences persisted to `~/.opencode/state/kv.json`), `RouteProvider` (navigation), `SDKProvider` (HTTP client + SSE), `SyncProvider` (reactive server state mirror), `ThemeProvider`, `LocalProvider` (agent/model selection persisted to `~/.opencode/state/model.json`), `KeybindProvider` (leader key = Ctrl+X), `CommandProvider` (60+ registered commands), `DialogProvider` (modal stack), and `ToastProvider`.

The **SyncProvider** is architecturally critical — it subscribes to the SSE event stream and maintains a **reactive SolidJS store** mirroring all server state: sessions, messages, parts, permissions, questions, statuses, diffs, todos, providers, agents, config, MCP status, and LSP status. The sync lifecycle progresses from `loading` → `partial` → `complete`. This reactive store means components automatically re-render when server state changes, with no manual refresh logic.

The TUI has two primary routes: **Home** (centered prompt) and **Session** (messages + sidebar + header + footer + prompt). The **prompt component** supports two modes: normal text input and shell mode (activated by `!` prefix), with an **extmark system** displaying compact virtual text for file references (`@filename`), agent mentions (`@agent-name`), pasted content (`[Pasted Text: 50 words]`), and images (`[Image 1]`). **Autocomplete** is context-aware: `@` triggers file/agent/MCP resource completion (with **frecency scoring**), `/` triggers slash commands and MCP prompts.

The **desktop app** (`packages/desktop/`) uses Tauri v2 with a sidecar pattern — the Rust backend spawns and manages a local `opencode serve` process, health-checks it via HTTP, then renders the SolidJS frontend in a webview. Both desktop and web apps share the `@opencode-ai/ui` component library (distinct from the TUI's custom terminal components). An Electron variant also exists at `packages/desktop-electron/`.

---

## Plugins, MCP, skills, and LSP: the extensibility surface

The **plugin system** (`packages/plugin/`) exposes lifecycle hooks: `tool.execute.before`/`after` (modify tool args/output), `shell.env` (inject environment variables), `experimental.chat.messages.transform` (modify messages before API calls), `experimental.chat.system.transform` (modify system prompt), and `AuthHook` (provider-specific OAuth flows). Plugins load from `.opencode/plugins/` directories or npm packages, with automatic `bun install` for dependency management. Built-in auth plugins handle Codex, Copilot, and GitLab authentication.

**MCP integration** supports **stdio** (child processes via `StdioClientTransport`) and **HTTP/SSE** (with `StreamableHTTPClientTransport` + SSE fallback) transports. `convertMcpTool()` transforms MCP tool definitions into AI SDK `dynamicTool` instances with schema normalization, 30-second timeout, and OpenCode's permission system. OAuth support includes PKCE, dynamic client registration (RFC 7591), and state parameter validation, with tokens persisted to `~/.local/share/opencode/mcp-auth.json`. MCP status uses a **5-variant discriminated union**: `connected`, `disabled`, `failed`, `needs_auth`, `needs_client_registration`.

**Skills** are markdown files (`SKILL.md`) with YAML frontmatter providing domain-specific knowledge. They're discovered from `.claude/skills/`, `.opencode/skill/`, and custom config paths, then integrated as both tools (`SkillTool`) and slash commands. Skills are **protected from compaction** — their content is never pruned during context management.

**LSP integration** provides code intelligence for **20+ languages** via auto-discovered language servers (TypeScript, Go, Python/Pyright, Rust-Analyzer, Clangd, and more). `LSPClient.create()` wraps servers using `vscode-jsonrpc`, with queries for diagnostics, hover, definition, references, and symbols. **12+ code formatters** (prettier, gofmt, biome, ruff, etc.) trigger automatically on file edits via `File.Event.Edited` bus events.

---

## TypeScript patterns that define the codebase

OpenCode's TypeScript style is distinctive. The primary module pattern is **namespace-based** — `Config`, `Tool`, `Session`, `Bus`, `Provider`, `MCP`, `LSP`, `Plugin` are all namespaces with static exported functions rather than class hierarchies. This gives a clean `Config.get()`, `Session.create()`, `Bus.publish()` API without instantiation ceremony.

**Zod schemas are co-located with types** throughout — `Config.Info`, `Provider.Model`, `Skill.Info`, `MessageV2.User`, and all event definitions are Zod schemas serving as both runtime validators and TypeScript types. The **discriminated union pattern** appears everywhere: message parts (10+ variants), tool states (4 variants), MCP status (5 variants), permission actions (3 variants), and error types (5 variants on assistant messages: `ProviderAuthError`, `APIError`, `AbortedError`, `ContextOverflowError`, `UnknownError`).

Generics are used strategically: `Tool.Context<M>` parameterizes the metadata type per tool, `Tool.InferParameters<T>` and `Tool.InferMetadata<T>` extract types from tool definitions, and the `fn(schema, cb)` utility wraps async callbacks with synchronous Zod validation. **Branded types** appear via `Identifier.schema("session")` creating `z.string().startsWith("ses")`.

Error handling is primarily **try/catch** rather than Result types — errors propagate through standard throw/catch with custom error classes for specific domains (`Permission.RejectedError`, `Session.BusyError`, `Provider.ModelNotFoundError` with fuzzy search suggestions, `DoomLoopError`). Async patterns are **async/await throughout**, with `AbortSignal` for cancellation, `streamText()` for LLM streaming, and the event bus for decoupled communication.

## Conclusion

OpenCode's architecture reflects deliberate, opinionated design choices. The **client/server split** with SSE-based state synchronization enables the same backend to serve a terminal TUI, a web app, a desktop app, and a VS Code extension simultaneously — each a thin reactive client. The **namespace-based module pattern** combined with **Zod-as-types** eliminates the runtime/type boundary that plagues many TypeScript projects. The **@opentui Zig native core** with a SolidJS reconciler is an unconventional and ambitious choice for terminal rendering, trading ecosystem maturity for raw performance and layout flexibility. The **7-layer config cascade** and **multi-layered permission merge** show a system designed for enterprise adoption (managed config directories, per-agent permission overrides) while remaining developer-friendly. The `Instance.state()` scoping pattern with `lazy()` initialization ensures that the cost of supporting 20+ LSP servers, 23+ providers, and dozens of MCP connections is deferred until actually needed. The most architecturally significant pattern is the **event bus as the single source of truth** — every state mutation publishes a typed event, making the system inherently observable, testable, and extensible through plugins that hook into this event flow.
