# Devin Session Breakdown Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Break 6 large Devin sessions into 10 focused sessions (3-5 deliverables each) for the opencode-rewrite Clojure project, reducing Devin failure risk and retry cost.

**Architecture:** Each session produces one PR. Sessions are sequential — each builds on the prior. The split targets natural dependency boundaries: pure data before I/O, protocols before implementations, framework before tools, infrastructure before the loop.

**Tech Stack:** Clojure 1.12, Integrant, Malli, core.async, hato, babashka, JLine 3

---

## Session Dependency Graph

```
1 (Config) → 2 (Message+Bus) → 3 (Provider+SSE) → 4 (Anthropic) → 5 (Tool Framework) → 6 (More Tools)
                                                                          ↓
                                    7 (UI+Persist+Perms) ← ← ← ← ← ← ← ←
                                         ↓
                                    8 (Agent Loop) → 9 (Main/MVP) → 10 (Compaction)
```

## ACU Budget

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
| Buffer | ~1 retry | 5-7 | 49-71 |

**Total: 49-71 ACU** (tight at 60, comfortable with focused sessions)

---

### Task 1: Rewrite SESSIONS.md with 10 focused sessions

**Files:**
- Modify: `SESSIONS.md` (complete rewrite)
- Modify: `DEVIN-PROMPT-KIT.md:228-241` (update ACU budget table)

- [ ] **Step 1: Write new SESSIONS.md**

Replace entire file with 10 sessions. Each session has: pre-flight, task description, explicit deliverables with file paths, reference TS files, verification commands.

Key splits from original:
- Original Session 1 → Session 1 (unchanged)
- Original Session 2 → Session 2 (unchanged, already right-sized)
- Original Session 3 → Sessions 3 + 4 (protocol/SSE separate from Anthropic HTTP)
- Original Session 4 → Sessions 5 + 6 (framework+safe tools separate from complex tools)
- Original Session 5 → Sessions 7 + 8 + 9 (UI/persist/perms, then loop, then wiring)
- Original Session 6 → Session 10 (unchanged)

- [ ] **Step 2: Update DEVIN-PROMPT-KIT.md budget table**

Update Part 4 ACU table to match the new 10-session breakdown.

- [ ] **Step 3: Verify markdown renders correctly**

Scan for broken fences, mismatched backticks, orphaned headers.

- [ ] **Step 4: Commit**

```bash
git add SESSIONS.md DEVIN-PROMPT-KIT.md
git commit -m "Break 6 Devin sessions into 10 focused sessions with more detail"
```
