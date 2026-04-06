# Idiomatic Clojure for building an LLM agent: a comprehensive guide

**Clojure is arguably the best Lisp for building an LLM-powered coding assistant**, and your background in OCaml, Haskell, and Scala puts you in an excellent position to appreciate why. This guide covers everything: mental model translations from typed FP, JVM survival skills, Nubank-style idioms, core.async patterns for streaming, the complete library ecosystem, and battle-tested architectural blueprints drawn from real Clojure agent projects. The Clojure community has been building AI agents since 2024, with Nubank (the world's largest Clojure shop, **1,000+ microservices**) publishing concrete patterns, and open-source projects like `effective-agents-clj` implementing all of Anthropic's agent architectures in idiomatic Clojure.

---

## Part 1 — Mental model bridges from typed FP to Clojure

### What replaces your type system

The biggest shift: Clojure is dynamically typed but *strongly* typed — it won't silently coerce types. Confidence comes from a different stack than HM inference.

**Malli** (metosin) is the community's preferred schema library as of 2025. Schemas are **plain data** (vectors), making them serializable, composable, and inspectable:

```clojure
(require '[malli.core :as m])

(def User
  [:map
   [:name :string]
   [:email :string]
   [:age [:int {:min 0 :max 150}]]])

(m/validate User {:name "Alice" :email "a@b.com" :age 30}) ;; => true
(m/explain User {:name "Alice" :email "a@b.com" :age -1})  ;; detailed errors
```

Malli supports runtime validation, **JSON Schema generation** (critical for LLM tool calling), value coercion, generative testing via test.check, and clj-kondo integration that gives you **type-mismatch warnings in your editor**. Function schemas provide contract-style checking:

```clojure
(defn plus [x y] (+ x y))
(m/=> plus [:=> [:cat :int :int] :int])
;; With instrumentation, (plus "2" 3) throws at the boundary
```

**clojure.spec** is the built-in alternative (since Clojure 1.9) using predicate-based specs and a global registry. It's powerful for macro specs and Clojure internals, but Malli has won mindshare for application code due to its data-oriented design, **2× better performance**, built-in coercion, and active development (spec2 has stalled).

The Clojure confidence model works like this: **REPL-driven development** gives instant feedback on actual data. **Boundary validation** (Malli/spec at API endpoints, DB interfaces) catches shape errors. **Generative testing** (test.check) explores more input space than hand-written tests. Internal code stays unencumbered — you validate at the edges and trust the interior.

### Protocols, multimethods, and the expression problem

**Protocols** provide single-dispatch polymorphism on the first argument's type, and can be extended to existing types retroactively:

```clojure
(defprotocol Greetable
  (greet [this]))

(extend-protocol Greetable
  String  (greet [s] (str "Hello, " s))
  Long    (greet [n] (str "Hello, number " n)))
```

Compared to Haskell typeclasses: protocols dispatch at runtime (not compile time), cannot dispatch on return type, and don't support multi-parameter constraints. Compared to OCaml module signatures: protocols lack functors and module-level abstraction. Compared to Scala traits: protocols are open for extension without modification but lack hierarchical inheritance.

**Multimethods** handle everything else — dispatch on arbitrary criteria, multiple arguments, value-based dispatch:

```clojure
(defmulti area :shape)
(defmethod area :circle [{:keys [radius]}]
  (* Math/PI radius radius))
(defmethod area :rectangle [{:keys [width height]}]
  (* width height))
```

The dispatch function can be anything: a keyword lookup, a computed value, or a function of multiple arguments. Multimethods are **open** — new methods can be added from any namespace. This is the key difference from OCaml/Haskell pattern matching, which is closed (all cases in one place) but gains exhaustiveness checking.

**Guidance:** Use protocols for type-based dispatch (the 90% case) — they're ~5× faster than multimethods due to JVM interface dispatch. Use multimethods for value-based dispatch (like tool registry dispatch by tool name) or multi-argument dispatch.

### How to model algebraic data types

**Product types** become maps with keyword keys — open by design:

```clojure
{:x 3.0 :y 4.5}                              ;; a Point
{:name "Alice" :age 30 :email "a@b.com"}      ;; a Person
```

**Sum types** become tagged maps with a discriminator keyword:

```clojure
{:type :paid,         :transaction-id "eefa9112"}
{:type :card-expired, :card 8783, :date "03/19"}
{:type :auth-error}
```

Dispatch via `case` (closed) or multimethods (open). Rich Hickey's argument: adding a field to a map doesn't break existing consumers, whereas adding a constructor to a Haskell ADT forces changes at every pattern match site. The tradeoff is you lose compile-time exhaustiveness. Use Malli's `:multi` schema to formalize sum types with validation:

```clojure
(def PaymentResult
  [:multi {:dispatch :type}
   [:paid [:map [:transaction-id :string]]]
   [:card-expired [:map [:card :int] [:date :string]]]
   [:auth-error [:map]]])
```

### Immutability, concurrency, laziness, and error handling

**Immutability** works similarly to OCaml/Haskell but with purpose-built persistent data structures. Vectors use **wide bit-partitioned tries** (branching factor 32, ~4 levels deep for 1M elements). Hash maps use **HAMTs** (Hash Array Mapped Tries). When you `assoc` a new key into a 1M-entry map, only ~4-5 nodes are created; the rest is structurally shared. Operations are O(log₃₂ n) ≈ effectively O(1). **Transients** provide temporary mutability for batch operations.

**Concurrency** provides a taxonomy: **atoms** (uncoordinated, synchronous — CAS-based, like `IORef`), **refs** (coordinated, synchronous — STM with MVCC), **agents** (uncoordinated, asynchronous — fire-and-forget), and **core.async** (CSP channels, like Go). Clojure's STM is conceptually similar to Haskell's but without type-system enforcement of purity within transactions.

**Laziness** is opt-in and limited to sequences, unlike Haskell's pervasive laziness. Functions like `map`, `filter`, `take` return lazy sequences, but everything else is eager. **Critical gotcha:** Clojure realizes lazy sequences in chunks of 32 for performance. Side effects in lazy maps may not execute when expected. **Transducers** (see Part 3) are the modern alternative that avoids lazy-sequence pitfalls entirely.

**Error handling** eschews `Either`/`Result` monads. The standard approach is **`ex-info`/`ex-data`** — exceptions that carry structured data:

```clojure
(throw (ex-info "User not found" {:type :not-found :user-id 42}))

(try (find-user! 42)
  (catch clojure.lang.ExceptionInfo e
    (let [{:keys [type]} (ex-data e)]
      (case type :not-found (handle-missing) (throw e)))))
```

**Cognitect anomalies** provide a lightweight alternative: return error maps as data instead of throwing, with standardized categories (`:cognitect.anomalies/not-found`, `:unavailable`, `:forbidden`, etc.). Why not monadic error handling? Clojure lives on the JVM where Java interop requires dealing with exceptions anyway, and monads need type system support to be ergonomic.

### The ten anti-patterns typed FP developers fall into

The most common mistakes:

1. **Over-typing**: Trying to spec every function. Idiomatic Clojure validates at *boundaries* only.
2. **Too many records**: Reaching for `defrecord` when a plain map suffices. Records are for protocol implementation and performance-critical code.
3. **Monadic style**: Using `cats` or `failjure` for monadic composition everywhere. This fights Clojure's idioms.
4. **Direct recursion without `recur`**: JVM has no TCO. Always use `loop`/`recur` or `lazy-seq`.
5. **Fighting nil**: Haskell's `Maybe` instinct makes you want to eliminate nil. In Clojure, nil-punning is idiomatic: `(when-let [x (find-thing)] (process x))`.
6. **Complex pattern matching instead of destructuring**: Simple `let` destructuring with `case`/`cond` is usually clearer than `core.match`.
7. **Avoiding atoms**: Pure FP developers write convoluted code to avoid `swap!`. Clojure's reference types provide *controlled* mutation.
8. **Thinking in types instead of data shapes**: Model domains as maps flowing through transformation pipelines, not as type hierarchies.
9. **Wrapping values in custom types**: A user ID is just a string or UUID — no `UserId` newtype needed.
10. **Not embracing the REPL**: Continuing compile-run-test when the REPL provides instant, interactive feedback.

---

## Part 2 — JVM survival guide for non-JVM developers

### Everything you need to know about the JVM

The **classpath** is the JVM's equivalent of `$PATH` for code — a list of directories and JAR files where the JVM finds classes. Clojure's CLI (`clj`/`clojure`) manages this via `deps.edn`. Run `clj -Spath` to see the resolved classpath.

**Maven coordinates** (`groupId/artifactId {:mvn/version "version"}`) are the universal package addressing system. Libraries download from Maven Central and Clojars (Clojure's registry) to `~/.m2/repository/`.

**JVM startup takes ~2-5 seconds** (JVM boot + Clojure class loading). This is the biggest shock from OCaml (~1ms) or Haskell (~5-50ms). The primary mitigation: **keep a long-running REPL** (you'll start it once and use it for hours). For scripts, use Babashka (~6ms startup). For CLI distribution, use GraalVM native-image (~20ms).

**Garbage collection**: Use the default G1GC. No tuning needed for most apps. Unlike OCaml's stop-the-world GC, G1GC is concurrent and generational.

**JIT compilation**: The JVM interprets bytecode initially, then the C2 compiler optimizes hot paths to native code. Clojure gets *faster* after warmup — JIT can exceed statically-compiled performance in long-running processes through profile-guided optimization.

### deps.edn — your build file as pure data

Unlike sbt's Scala DSL, dune's S-expressions, or Cabal's custom format, `deps.edn` is just a Clojure map. Here's a realistic example:

```clojure
{:paths ["src" "resources"]
 :deps {org.clojure/clojure        {:mvn/version "1.12.0"}
        metosin/malli              {:mvn/version "0.19.1"}
        hato/hato                  {:mvn/version "1.0.0"}
        metosin/jsonista           {:mvn/version "0.3.13"}
        datascript/datascript      {:mvn/version "1.7.8"}
        babashka/process           {:mvn/version "0.5.22"}
        babashka/fs                {:mvn/version "0.5.30"}
        org.clojure/core.async     {:mvn/version "1.7.790"}}
 :aliases
 {:dev  {:extra-paths ["dev" "test"]
         :extra-deps  {djblue/portal      {:mvn/version "0.55.1"}
                       nrepl/nrepl        {:mvn/version "1.1.0"}
                       cider/cider-nrepl  {:mvn/version "0.47.0"}}}
  :test {:extra-paths ["test"]
         :extra-deps  {lambdaisland/kaocha {:mvn/version "1.91.1392"}}
         :main-opts   ["-m" "kaocha.runner"]}
  :build {:deps {io.github.clojure/tools.build
                   {:git/tag "v0.10.6" :git/sha "52cf7d6"}}
          :ns-default build}}}
```

Dependencies can be Maven coordinates, **git coordinates** (pin to SHA), or local paths. Aliases activate with flags: `clj -M:dev` (main), `clj -X:alias` (execute function), `clj -T:build uber` (tool mode).

### Java interop cheat sheet

You'll need Java interop for HTTP, I/O, date/time, and more:

```clojure
;; Instance method:  (.method obj args)
(.toUpperCase "hello")                    ;=> "HELLO"

;; Static method:    (Class/method args)
(Math/pow 2 10)                           ;=> 1024.0
(Integer/parseInt "42")                   ;=> 42

;; Constructor:      (ClassName. args)
(java.io.File. "/tmp/test.txt")

;; Static field:
Math/PI                                   ;=> 3.14159...

;; Reading Javadocs translation:
;; Java: Instant.now()     → (java.time.Instant/now)
;; Java: file.exists()     → (.exists file)
;; Java: new URI("...")    → (java.net.URI/new "...")  ;; Clojure 1.12+
```

### Babashka and GraalVM for fast startup

**Babashka** is a standalone native binary (~6ms startup) that interprets a large subset of Clojure. Use it for scripts, build tasks, git hooks, and AWS Lambda:

```bash
bb -e '(println (+ 1 2))'   # instant
```

**GraalVM native-image** compiles to a standalone native executable (~20ms startup, no JVM required). Add `com.github.clj-easy/graal-build-time` to deps, build an uberjar, then:

```bash
native-image -jar target/myapp-standalone.jar -o target/myapp \
  --features=clj_easy.graal_build_time.InitClojureClasses --no-fallback
./target/myapp   # ~20ms, no JVM
```

Limitations: no `eval`, no dynamic class loading, reflection must be configured. The `clj-easy/graalvm-clojure` repo tracks verified compatible libraries.

### Editor setup and REPL-driven development

**Calva (VS Code)**: Best for beginners. Install extension, Ctrl+Alt+C/J to jack-in, evaluate forms inline. Includes clojure-lsp and clj-kondo. **CIDER (Emacs)**: Most powerful — deepest REPL integration, inline eval, debugger, profiler. Ideal if you're already an Emacs user (common for OCaml/Haskell devs). **Cursive (IntelliJ)**: Best Java interop, structural editing, good for mixed Clojure/Java.

The REPL is not a toy — it's your primary development tool:

```clojure
(comment
  ;; Rich comment block — lives in source, not loaded in production
  ;; Evaluate each form individually to explore and test

  (def sample-messages [{:role "user" :content "Fix the bug"}])

  (call-llm sample-messages {:model "claude-sonnet-4-20250514"})
  ;; See response instantly, iterate on prompt, no restart
  )
```

Start the REPL once, keep it running for hours. Edit code, evaluate individual forms inline, see results immediately. **Portal** connects via `tap>` for visual data inspection. `clj-kondo` provides static analysis (wrong arity, unused vars, missing requires) and integrates with Malli for type-mismatch warnings.

---

## Part 3 — Idiomatic Clojure patterns, Nubank style

### Component and Integrant for lifecycle management

**Stuart Sierra's Component** manages stateful object lifecycle through a DAG of dependencies. Components are immutable records implementing `Lifecycle` (start/stop). `start` returns a *new* record with runtime state assoc'd — not mutation:

```clojure
(defrecord HttpClient [timeout connection]
  component/Lifecycle
  (start [this]
    (assoc this :connection (build-connection timeout)))
  (stop [this]
    (.close connection)
    (assoc this :connection nil)))

(defn new-system [config]
  (component/system-map
    :http-client (map->HttpClient {:timeout 30000})
    :llm-provider (component/using
                    (map->LLMProvider {})
                    [:http-client])))  ;; dependency declaration
```

Nubank uses Component across **~1,000 microservices**. Their "12 years of Component" blog post confirms it's one of Clojure's most influential patterns.

**Integrant** is the data-driven alternative — config is EDN, components are multimethods:

```clojure
;; system.edn
{:llm/provider {:api-key #env ANTHROPIC_API_KEY :model "claude-sonnet-4-20250514"}
 :agent/core {:provider #ig/ref :llm/provider}}

;; Implementation
(defmethod ig/init-key :llm/provider [_ {:keys [api-key model]}]
  (->AnthropicProvider api-key model))
```

Component when you want explicit code-level control; Integrant when you want config-as-data.

### Nubank's Diplomat Architecture

Nubank uses a variant of hexagonal architecture they call **Diplomat Architecture**:

```
src/my_service/
├── core/         ;; Pure business logic — pure functions, schemas
├── diplomat/     ;; Adapters — HTTP handlers, Kafka consumers
├── controller/   ;; Wires flow between core and ports
├── port/         ;; Entry points — HTTP server, Kafka
└── db/           ;; Database adapters
```

All pure functions declare schemas for parameters and returns. **Schema validation is ON in dev/test, OFF in production.** Every microservice follows the identical folder structure.

### Threading macros are your pipeline operators

Threading macros replace Haskell's `&` and OCaml's `|>`:

- **`->`** (thread-first): Inserts as first arg. For map/record transforms: `(-> person (assoc :name "Bob") (update :age inc))`
- **`->>`** (thread-last): Inserts as last arg. For sequences: `(->> data (filter active?) (map :name) (take 10))`
- **`some->`**: Short-circuits on nil — equivalent to Haskell's `Maybe` bind: `(some-> m :counter Long/parseLong inc)`
- **`cond->`**: Conditional threading without short-circuit: `(cond-> base-map premium? (assoc :discount 0.2))`
- **`as->`**: Binds to a name for arbitrary positioning

### Transducers replace lazy sequence chaining

Transducers are composable, context-independent transformations. A transducer has the conceptual type `(a → r → r) → (b → r → r)` — it transforms one reducing function into another:

```clojure
;; Compose with comp (applied LEFT to RIGHT, unlike normal comp)
(def xf (comp (map inc) (filter odd?) (take 5)))

;; Apply to any context:
(into [] xf (range 100))        ;; collection
(transduce xf + (range 100))    ;; reduce
(sequence xf (range 100))       ;; lazy sequence
(async/chan 10 xf)               ;; core.async channel!
```

**No intermediate collections** — a chained `(->> data (map f) (filter p) (take n))` creates 3 lazy sequences, while transducers do it in a single pass with ~2× speedup. Transducers compose with ordinary `comp` and work with core.async channels natively, making them ideal for streaming LLM tokens through processing pipelines.

### Namespaced keywords prevent collision at scale

```clojure
:user/name        ;; explicit namespace
::local-key       ;; auto-resolves to current namespace
#:message{:role "user" :content "Hello"}
;; => {:message/role "user", :message/content "Hello"}
```

Nubank uses namespaced keywords across all 1,000+ microservices to prevent key collisions in Kafka messages, Datomic attributes, and inter-service communication. Malli and spec specs are keyed by namespaced keywords.

### Testing with Nubank's open-source tools

**nubank/matcher-combinators** provides declarative assertions over nested data:

```clojure
(is (match? {:name "Alice"}
            {:name "Alice" :age 30 :extra "ignored"}))  ;; passes (partial match)
```

**nubank/state-flow** builds integration tests as composable monadic flows over a Component system, using matcher-combinators for assertions. **test.check** integrates with both Malli and spec for generative/property-based testing. **Aero** (JUXT) handles EDN configuration with `#env`, `#profile`, `#or`, and `#include` tag literals.

---

## Part 4 — core.async deep dive for agent systems

### The critical rule: never block in go blocks

Go blocks share a **fixed thread pool of 8 threads**. If you make HTTP calls inside `go` blocks, you will saturate all 8 threads, **starve every other go block in your application**, and potentially deadlock the entire system.

```clojure
;; WRONG — will deadlock under load:
(a/go (let [resp (http/post llm-url {...})]  ;; blocks a pool thread!
        (a/> out-ch resp)))

;; CORRECT — use thread for blocking I/O:
(a/thread
  (let [resp (http/post llm-url {:body (json/encode request)})]
    (a/>!! result-ch (parse-response resp))))

;; CORRECT — use pipeline-blocking for parallel I/O:
(a/pipeline-blocking 4 out-ch (map call-llm-api) in-ch)
```

Set `-Dclojure.core.async.go-checking=true` during development to catch blocking ops inside go blocks. On Java 21+, core.async 1.9's **`io-thread`** dispatches to virtual threads, largely mitigating this limitation.

### Channels, go blocks, and thread blocks

**Channels** are typed-agnostic, multi-writer, multi-reader queues. Buffer types: **fixed** (blocks when full), **sliding** (drops oldest), **dropping** (drops newest). Nil cannot be sent (it's the closed-channel sentinel). Max **1024 pending puts** per channel.

**`go` blocks** rewrite the body into a state machine at compile time. Each `<!`/`>!` becomes a parking point — the state machine suspends, freeing the thread. Use for: pure computation, channel coordination, lightweight event routing. `<!`/`>!` **cannot cross function boundaries** — `(go (mapv a/<! channels))` fails at runtime.

**`thread` blocks** create a new OS thread. Use for: HTTP calls, DB queries, file I/O — anything blocking. Return a channel with the result.

### Broadcasting LLM streams with mult/tap

```clojure
(def llm-events-ch (a/chan 32))
(def llm-mult (a/mult llm-events-ch))

;; UI consumer (sliding buffer — skip old tokens if slow)
(def ui-ch (a/chan (a/sliding-buffer 64)))
(a/tap llm-mult ui-ch)

;; Logger (dropping buffer — lose events rather than block)
(def log-ch (a/chan (a/dropping-buffer 256)))
(a/tap llm-mult log-ch)

;; State accumulator (bounded buffer — backpressure)
(def state-ch (a/chan 32))
(a/tap llm-mult state-ch)
```

**Always buffer tap channels** — a slow consumer in a mult blocks ALL distribution. Use `pub`/`sub` for topic-based routing (dispatching events by `:type` key to different processors).

### Timeout, cancellation, and error patterns

```clojure
;; Timeout with alt!
(a/go
  (a/alt!
    result-ch ([v] (handle-response v))
    (a/timeout 30000) ([_] {:error :timeout})
    cancel-ch ([_] {:error :cancelled})))

;; Error handling: wrap errors as data on channels
(a/thread
  (try
    (a/>!! result-ch (http/post llm-url {...}))
    (catch Exception e
      (a/>!! result-ch {:error (ex-message e) :data (ex-data e)}))
    (finally
      (a/close! result-ch))))

;; promise-chan for single-value results (like Scala Promise/Future)
(def result (a/promise-chan))
(a/thread (a/>!! result (expensive-computation)))
;; Multiple readers all get the same value, forever
```

### How core.async compares to Haskell and Scala

Core.async channels are conceptually similar to Haskell's `TChan` and Go's channels. `alts!` ≈ Go's `select` ≈ Haskell's STM `orElse`. What core.async lacks: **no STM for channel operations** (can't atomically take-from-one-put-to-another), **no structured concurrency** (go blocks are fire-and-forget, unlike ZIO fiber supervision), **no typed errors in channels** (unlike ZIO's `ZIO[R,E,A]`), **no supervision trees** (unlike Akka). What it gains: extreme simplicity (tiny API surface), ClojureScript portability, native transducer integration, and REPL interactivity.

---

## Part 5 — The library stack for an LLM coding agent

### HTTP clients and SSE streaming

**hato** (`hato/hato {:mvn/version "1.0.0"}`) wraps JDK 11+ `java.net.http.HttpClient`. Supports HTTP/2, async, minimal deps. **babashka.http-client** (`org.babashka/http-client {:mvn/version "0.4.22"}`) is the zero-dependency alternative on the same JDK foundation. Both return `InputStream` via `{:as :stream}` for SSE parsing:

```clojure
(defn stream-sse-to-channel [http-response]
  (let [events-ch (a/chan 64)]
    (a/thread
      (try
        (with-open [reader (io/reader (:body http-response))]
          (doseq [line (line-seq reader)]
            (when (str/starts-with? line "data: ")
              (let [data (subs line 6)]
                (when (not= data "[DONE]")
                  (a/>!! events-ch (json/read-value data keyword)))))))
        (finally (a/close! events-ch))))
    events-ch))
```

Avoid **clj-http** for LLM work — large dependency tree, no HTTP/2. For JSON: **jsonista** (`metosin/jsonista {:mvn/version "0.3.13"}`) is ~30-40% faster than Cheshire for large payloads.

### LLM API libraries and frameworks

**No dedicated Anthropic Clojure library exists** — the idiomatic approach is a thin wrapper using hato:

```clojure
(defn call-claude [{:keys [model messages system max-tokens stream]}]
  (hc/post "https://api.anthropic.com/v1/messages"
    {:headers {"x-api-key" (System/getenv "ANTHROPIC_API_KEY")
               "anthropic-version" "2023-06-01"
               "content-type" "application/json"}
     :body (json/write-value-as-string
             {:model model :messages messages :system system
              :max_tokens max-tokens :stream (boolean stream)})
     :as (if stream :stream :string)}))
```

**openai-clojure** (`net.clojars.wkok/openai-clojure {:mvn/version "0.23.0"}`) covers OpenAI/Azure with streaming via core.async channels. **litellm-clj** (unravel-team) provides unified access to OpenAI, Anthropic, Gemini, and Bedrock with streaming. **Bosquet** (`io.github.zmedelis/bosquet {:mvn/version "2025.03.28"}`) is the most complete LLM toolkit: prompt templating, chaining via Pathom graphs, agent/tool abstractions, multi-provider support. **llama.clj** runs models locally via llama.cpp.

### Shell execution and file system

**babashka.process** (`babashka/process {:mvn/version "0.5.22"}`) is strictly superior to `clojure.java.shell` for an agent: non-blocking execution, async I/O, pipeline support, environment control, `destroy-tree` for cleanup:

```clojure
(require '[babashka.process :refer [process check]])
(-> (process {:out :string} "git" "diff") check :out)
```

**babashka.fs** (`babashka/fs {:mvn/version "0.5.30"}`) covers comprehensive file operations: glob, match, temp dirs, permissions, zip/unzip, file watching.

### DataScript for in-session state, XTDB for persistence

**DataScript** (`datascript/datascript {:mvn/version "1.7.8"}`) is an immutable in-memory Datalog database by Nikita Prokopov. Perfect for conversation state:

```clojure
(def schema
  {:message/conversation {:db/valueType :db.type/ref}
   :message/tool-calls   {:db/cardinality :db.cardinality/many
                           :db/valueType :db.type/ref}})
(def conn (d/create-conn schema))

(d/transact! conn [{:message/role "user"
                    :message/content "Fix the bug in core.clj"
                    :message/conversation [:conversation/id "conv-1"]}])

(d/q '[:find ?content ?role :where
       [?m :message/content ?content]
       [?m :message/role ?role]]
     @conn)
```

Database-as-a-value means immutable snapshots for time-travel, rich Datalog queries across messages/tools/conversations, and serialization via `datascript.serialize`.

**XTDB** (`com.xtdb/xtdb-api`) provides bitemporal persistence. Every row has valid-time and system-time, enabling queries like "what was the conversation state at time T" — ideal for audit trails, undo/redo, and conversation history across sessions.

### Malli for LLM tool schemas — single source of truth

Malli's built-in JSON Schema transform makes it the natural choice for defining LLM tool schemas:

```clojure
(def ReadFile
  [:map
   [:path :string]
   [:encoding {:optional true} :string]])

(json-schema/transform ReadFile)
;; => {:type "object"
;;     :properties {:path {:type "string"} :encoding {:type "string"}}
;;     :required [:path]}

(def tools
  [{:name "read_file"
    :description "Read the contents of a file"
    :input_schema (json-schema/transform ReadFile)}])
```

One schema definition gives you: validation of tool call arguments, JSON Schema for the LLM API, test data generators, and human-readable error messages.

### Terminal UI options

**JLine** (`org.jline/jline {:mvn/version "3.26.3"}`) provides readline, history, tab completion — used by Clojure's own REPL. The simplest approach for a coding assistant: JLine for input + raw ANSI escape codes for colored output. **charm.clj** (TimoKramer) provides a Bubble Tea-inspired Elm Architecture TUI, built on JLine, compatible with GraalVM native-image. **Lanterna** for full curses-style screen apps.

---

## Part 6 — Agent architecture building blocks

### The agentic loop as pure data transformation

The canonical pattern uses **`loop/recur`** — stack-safe, state flow is explicit, and the entire conversation is an immutable data structure being threaded through:

```clojure
(defn agent-loop
  "Core agentic loop: LLM → parse → execute tools → repeat"
  [provider messages config tools]
  (loop [messages messages
         step 0]
    (when (< step (:max-steps config 20))
      (let [response (complete provider messages
                       {:model (:model config)
                        :tools (tools-for-api tools)})
            assistant-msg (extract-message response)
            messages' (conj messages assistant-msg)]
        (if-let [tool-calls (:tool_calls assistant-msg)]
          (let [results (execute-tools-parallel tool-calls)
                messages'' (into messages' results)]
            (recur messages'' (inc step)))
          messages')))))  ;; No tool calls — return final conversation
```

For multi-step workflows with branching, use an **FSM graph as pure data** (as demonstrated by serefayar's minimal agent engine):

```clojure
(def workflow
  {:nodes {:planner plan-fn :coder code-fn :reviewer review-fn}
   :edges {:planner :coder
           :coder :reviewer
           :reviewer (fn [state] (if (:approved? state) :END :coder))}
   :entry :planner})
```

### Tool registry with multimethods

Multimethods provide an open, extensible tool dispatch system:

```clojure
(defmulti execute-tool (fn [tool-name _params] tool-name))

(defmethod execute-tool "read_file" [_ {:keys [path]}]
  {:success true :content (slurp path)})

(defmethod execute-tool "run_shell" [_ {:keys [command]}]
  (let [{:keys [out err exit]} @(process {:out :string :err :string} "bash" "-c" command)]
    {:success (zero? exit) :stdout out :stderr err}))

(defmethod execute-tool :default [name _]
  {:success false :error (str "Unknown tool: " name)})
```

Any namespace can register new tools by adding `defmethod` implementations — no central registry file to modify.

### Provider abstraction with protocols

```clojure
(defprotocol LLMProvider
  (complete [this messages opts])
  (stream-complete [this messages opts]))  ;; returns core.async channel

(defrecord AnthropicProvider [api-key]
  LLMProvider
  (complete [this messages opts]
    (-> (call-claude {:model (:model opts)
                      :messages messages
                      :max-tokens (:max-tokens opts 4096)})
        :body json/read-value))
  (stream-complete [this messages opts]
    (stream-sse-to-channel
      (call-claude {:model (:model opts)
                    :messages messages
                    :stream true}))))
```

**litellm-clj** implements exactly this pattern — a unified interface dispatching by provider keyword with streaming returning core.async channels.

### Event system with tap> and core.async pub/sub

Three patterns at different complexity levels:

**`tap>`** (simplest — built into Clojure): Every `tap>` call distributes values to registered tap listeners. Connect Portal for visual inspection, or custom loggers:

```clojure
(tap> {:event :llm-request :messages messages :timestamp (System/currentTimeMillis)})
```

**Atom watches** (for state change events):

```clojure
(def agent-state (atom {:conversation [] :status :idle}))
(add-watch agent-state :logger
  (fn [_ _ old new]
    (when (not= (:status old) (:status new))
      (log/info "Status:" (:status old) "->" (:status new)))))
```

**core.async pub/sub** (for streaming events to multiple typed consumers):

```clojure
(def events-ch (a/chan (a/sliding-buffer 1000)))
(def event-pub (a/pub events-ch :event/type))

(defn subscribe [event-type buffer-size]
  (let [ch (a/chan (a/sliding-buffer buffer-size))]
    (a/sub event-pub event-type ch) ch))
```

---

## Part 7 — What Nubank and the community have built

### Nubank's AI agent work in Clojure

Marlon Silva's **"Building AI Agents in Practice with Clojure"** (Clojure South, Feb 2026) laid out Nubank's approach: infrastructure-first (use your cloud provider's AI endpoints), **LiteLLM as a proxy** for unified multi-provider access with centralized observability, and **Small Language Models over large LLMs** for task-oriented agents (7-10B params are more cost-efficient and feasible to fine-tune). The key insight: REPL-driven development aligns perfectly with AI development's experimental nature. Demo code at `github.com/marlonjsilva/clj-agents`.

Nubank also published on **"Empowering customer support agents with generative AI"** — their Agent Copilot uses LLMs for next-reply suggestions and chat summarization, handling **2M+ monthly chats** with GPT-4o (via their OpenAI partnership), achieving **70% reduction in response time**.

### Open-source Clojure agent projects worth studying

- **effective-agents-clj** (unravel-team): Implementations of all Anthropic "building effective agents" patterns — prompt chaining, routing, parallelization, orchestrator-workers, evaluator-optimizer, and autonomous agents. The best reference for idiomatic Clojure agent patterns.
- **litellm-clj** (unravel-team): Unified LLM provider interface with core.async streaming. Supports OpenAI, Anthropic, Gemini, Bedrock.
- **ClojureMCP** (Bruce Hauman): MCP server connecting Claude Code/Cursor to Clojure projects via nREPL. Includes structural editing, paren repair, and REPL evaluation tools. The `#ai-assisted-coding` channel on Clojurians Slack is very active.
- **Agent-o-rama** (Red Planet Labs, Nov 2025): End-to-end LLM agent platform with tracing, datasets, evaluation, and deployment. First-class Clojure API.
- **Grain** (ObneyAI): Event-sourced CQRS framework for AI-native Clojure systems with behavior tree engine.
- **Bosquet**: Most complete LLM toolkit — prompt templating, Pathom-graph chaining, tool abstractions, multi-provider.

### Community blog posts that demonstrate real patterns

The most practically useful: **serefayar's "De-mystifying Agentic AI"** builds a minimal agent engine from scratch with `loop/recur`, Malli-based self-healing structured output, FSM graph engine as pure data, and `tap>` observability. **sgopale's "Building a Coding Agent"** is a multi-part tutorial covering the chat loop, tool calling, and metadata-driven tool descriptions. **Ivan Willig's "One Year of LLM Usage with Clojure"** (Shortcut, ~300K LOC codebase) found that REPL integration prevents hallucinations and ClojureMCP dramatically improved LLM code quality.

---

## Part 8 — The recommended stack and development workflow

### Complete deps.edn for an LLM coding agent

```clojure
{:paths ["src" "resources"]
 :deps {org.clojure/clojure          {:mvn/version "1.12.0"}
        org.clojure/core.async       {:mvn/version "1.7.790"}
        ;; HTTP + JSON
        hato/hato                    {:mvn/version "1.0.0"}
        metosin/jsonista             {:mvn/version "0.3.13"}
        ;; Schema + validation
        metosin/malli                {:mvn/version "0.19.1"}
        ;; State + persistence
        datascript/datascript        {:mvn/version "1.7.8"}
        ;; Shell + filesystem
        babashka/process             {:mvn/version "0.5.22"}
        babashka/fs                  {:mvn/version "0.5.30"}
        ;; Terminal
        org.jline/jline              {:mvn/version "3.26.3"}
        ;; Lifecycle
        integrant/integrant          {:mvn/version "0.11.0"}
        ;; CLI
        org.clojure/tools.cli        {:mvn/version "1.1.230"}
        ;; Config
        aero/aero                    {:mvn/version "1.1.6"}}
 :aliases
 {:dev {:extra-paths ["dev" "test"]
        :extra-deps {djblue/portal      {:mvn/version "0.55.1"}
                     nrepl/nrepl        {:mvn/version "1.1.0"}
                     cider/cider-nrepl  {:mvn/version "0.47.0"}}}
  :test {:extra-paths ["test"]
         :extra-deps {lambdaisland/kaocha    {:mvn/version "1.91.1392"}
                      nubank/matcher-combinators {:mvn/version "4.0.0"}}
         :main-opts ["-m" "kaocha.runner"]}
  :build {:deps {io.github.clojure/tools.build
                   {:git/tag "v0.10.6" :git/sha "52cf7d6"}}
          :ns-default build}}}
```

### Daily workflow

1. `clj -M:dev` — start REPL once (keep it running)
2. Connect editor (Calva jack-in or CIDER connect)
3. Open Portal: `(portal.api/open)` + `(add-tap #'portal.api/submit)`
4. Write code in `.clj` files, evaluate forms inline
5. Use `(comment ...)` blocks to explore APIs and test interactively
6. Run `clj -M:test` or Kaocha from editor for test suite
7. `clj-kondo --lint src test` for static analysis (also runs in editor via clojure-lsp)
8. Build with `clj -T:build uber`, optionally `native-image` for distribution

### CI with GitHub Actions

```yaml
- uses: DeLaGuardo/setup-clojure@12.5
  with: {cli: 'latest', clj-kondo: 'latest'}
- run: clj-kondo --lint src test
- run: clojure -M:test
- run: clojure -T:build uber
```

Cache `~/.m2/repository` and `~/.gitlibs` between runs.

---

## Conclusion

Clojure's data-oriented design maps naturally to LLM agent architecture. Conversations are vectors of maps. Tool schemas are Malli data that converts to JSON Schema. The agent loop is a pure function transforming immutable state via `loop/recur`. Provider abstraction uses protocols. Tool dispatch uses multimethods. Streaming uses core.async channels. System wiring uses Integrant.

The three most important things to internalize: **never do blocking I/O in go blocks** (use `thread` or `pipeline-blocking` for all LLM API calls), **validate at boundaries only** (Malli at API edges, trust internal code), and **develop at the REPL** (start it once, keep it running, evaluate forms inline — this is how you iterate on prompts, inspect LLM responses, and debug tool execution in real time).

The ecosystem is ready. `effective-agents-clj` has all Anthropic's agent patterns implemented. `litellm-clj` gives you unified multi-provider streaming. `ClojureMCP` connects your REPL to LLM coding assistants. The community is actively building in the `#ai-assisted-coding` Clojurians Slack channel. Your OCaml/Haskell/Scala background in immutability, algebraic thinking, and composition translates directly — you just express it with maps, keywords, and threading macros instead of types, constructors, and typeclasses.
