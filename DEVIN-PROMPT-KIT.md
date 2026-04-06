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
org.clojure/core.async       {:mvn/version "1.7.701"}
integrant/integrant          {:mvn/version "0.11.0"}
aero/aero                    {:mvn/version "1.1.6"}
metosin/malli                {:mvn/version "0.19.1"}
hato/hato                    {:mvn/version "1.0.0"}
metosin/jsonista             {:mvn/version "0.3.13"}
babashka/process             {:mvn/version "0.5.22"}
babashka/fs                  {:mvn/version "0.5.30"}
org.jline/jline              {:mvn/version "3.26.3"}
org.clojure/tools.cli        {:mvn/version "1.1.230"}
com.cognitect/anomalies      {:mvn/version "0.1.12"}

DEV ONLY:
djblue/portal                {:mvn/version "0.55.1"}
nrepl/nrepl                  {:mvn/version "1.1.0"}
lambdaisland/kaocha          {:mvn/version "1.91.1392"}
nubank/matcher-combinators   {:mvn/version "3.9.1"}

NEVER use: leiningen, clj-http (use hato), cheshire (use jsonista), clojure.java.shell (use babashka.process)
```

### Knowledge Item 5: "Machine Setup"

**Trigger:** When starting a new session on the opencode-rewrite repo  
**Pin to:** victorlga/opencode-rewrite  
**Content:**

```
Before writing any code, ensure your environment is ready.
Run the setup script at the repo root:

  bash script/setup.sh

This installs Java 21, Clojure CLI, and clj-kondo, then resolves all project dependencies.
After it completes, verify with:

  clj -M -e '(println "ready")'
  clj-kondo --version

If either command fails, STOP and report the error before proceeding.
Do NOT attempt to install dependencies manually — use the setup script.
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

0. PRE-FLIGHT: Verify tooling works
   - Run `clj -M -e '(println "ready")'` and `clj-kondo --version`
   - If either fails, run `bash script/setup.sh` first

1. READ the AGENTS.md in the opencode-rewrite repo root for architecture decisions

2. READ the TypeScript source file(s) being translated from victorlga/opencode
   - Start with the files listed in the session prompt's Reference section
   - Read additional TypeScript files as needed to understand the full picture

3. READ existing Clojure code in src/opencode/
   - Understand conventions, imports, and patterns already established
   - Your new code must be consistent with what exists

4. IDENTIFY all data structures, functions, imports, and side effects

5. CREATE the Clojure namespace file with:
   a. ns declaration with all requires
   b. Malli schemas for data structures (translating Zod schemas)
   c. Pure functions first, then side-effecting functions (marked with !)
   d. Rich comment block at the bottom for REPL exploration

6. RUN `clj-kondo --lint src test` and fix errors/warnings in files you created or modified
   - Do NOT fix pre-existing warnings in files you didn't touch

7. CREATE corresponding test file with clojure.test + matcher-combinators

8. RUN `clj-kondo --lint src test` again and fix errors/warnings in files you created or modified

9. RUN `clj -M:test` and verify ALL tests pass (not just new ones — no regressions)

10. COMMIT with descriptive message and create PR
    - Branch name: session-N-short-description (e.g. session-2-message-domain)
    - PR title: "Session N: Short Description"
    - PR base branch: main

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
- Do NOT overwrite scaffold files (deps.edn, tests.edn, resources/config.edn, dev/user.clj, .clj-kondo/config.edn) unless the session prompt explicitly says to
```

---

## PART 3: SESSION PROMPTS

Session prompts have been extracted to **[SESSIONS.md](SESSIONS.md)**.
Run them in order. One session per prompt. Each produces a PR.

---

## PART 4: ACU BUDGET ESTIMATE

| Session | Description | Est. ACU | Running Total |
|---------|-------------|----------|---------------|
| 1 | Config system | 3-5 | 3-5 |
| 2 | Message + Session + Event bus | 5-7 | 8-12 |
| 3 | Provider protocol + Model registry + SSE | 5-7 | 13-19 |
| 4 | Anthropic API implementation | 5-7 | 18-26 |
| 5 | Tool framework + Read/Write/Glob | 5-7 | 23-33 |
| 6 | Edit + Bash + Grep tools | 5-7 | 28-40 |
| 7 | UI adapter + Persistence + Permission | 5-7 | 33-47 |
| 8 | Agent loop + System prompt | 5-7 | 38-54 |
| 9 | Main entry + system wiring (MVP) | 3-5 | 41-59 |
| 10 | Context management + compaction | 3-5 | 44-64 |
| Buffer for retries | ~1 retry | 5-7 | 49-71 |

**Total estimate: 49-71 ACU for core MVP + context management**
10 focused sessions (was 6 large). Smaller sessions = cheaper retries, fewer failures.
Budget for 1 retry. Sessions 1-9 = MVP, Session 10 = post-MVP.

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
