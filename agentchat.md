# agentchat.md — ec0 multi-agent coordination hub

Multiple agents work on this repository concurrently. This file is the single
source of truth for who is doing what, the working rules, and the activity log.
The canonical copy lives in the shared working tree (tracked in git); worktree
copies go stale — re-read before you log.

## How the team works

- **agent-0** is the lead (Aryan's naming): orchestrates, assigns tasks,
  reviews, takes merges, keeps this hub true. Workers are forked chats that
  claim assigned tasks from the Tasks section.
- Workers pick names `agent-<word>` at first CLAIM — Aryan pre-assigned
  **agent-1** and **agent-2** as the first two workers; keep those names.
- Aryan runs the project and forks worker chats; every worker starts by
  reading THIS file and logging a CLAIM entry below.

## Joining — do this FIRST, before touching any code

1. Create your git worktree (branch `feat/<agent>-<task>` off `main`):
   `git worktree add ~/.cache/ec0-worktrees/<agent>-<task> -b feat/<agent>-<task>`
   NEVER work in another agent's worktree or the shared tree without claiming
   it in the log. Remote is `origin` = github.com/ARYAN007H/Ec0 (private).
2. Read the Rules below, then **Project traps** — they will save your day.
3. Log a CLAIM entry (see Log rules) in the **shared-tree** `agentchat.md`.
4. You work ONLY on what agent-0 assigned you. The Backlog is agent-0's queue
   — do NOT self-assign from it.

## The rules (breaking these breaks another agent's work)

1. **Every code change** lands as: branch (in your worktree) → push to origin →
   `gh pr create --fill` → build/tests green → independent review by an agent
   that did not write it → agent-0 merges into `main`. Docs/research
   deliverables are gated the same way minus CI (review gate only). No direct
   pushes to `main` — agent-0 reverts on sight.
2. **One tree, one owner.** The shared working tree (`/home/aryan/Downloads/ec0`)
   is owned by agent-0 right now. Workers work in their own worktrees and touch
   only their task's file set (see Tasks — the sets do NOT overlap).
3. **Hub updates** (log entries, task status): append directly to the
   **shared-tree** `agentchat.md` (this file) — re-read the Log section first,
   append at the bottom, trim to 25 entries. Do not commit hub changes from
   worktrees; agent-0 commits the canonical hub periodically.
4. **Suite lock.** Shared test resources (adb device, ports, gradle daemon) are
   single-user. Before using the phone via adb, log a SUITE LOCK note; kill
   stale listeners BY PID (never `pkill -f`). Release the lock in your next log
   entry.
5. **Log every meaningful step** — CLAIM / WORKING / DONE / BLOCKED — append
   only, newest last, format below. **Only the 25 most recent entries stay**;
   trim the oldest wholesale when adding beyond that. Never delete another
   agent's entry.
6. **Reviews are independent.** agent-0 dispatches a reviewer who did not write
   the code. Verdicts: APPROVE or REQUEST-CHANGES with P0/P1/P2 findings
   (file:line evidence). Authors apply findings and re-gate.
7. **Verify, then claim DONE.** Run your deliverable's gates (build/tests for
   code; self-review + evidence links for docs) before DONE. Report failures
   plainly; disclose mistakes the moment you find them.
8. **No self-assignment.** Backlog is agent-0's queue.

Log entry format (one blank line between entries):

```
### YYYY-MM-DD HH:MM UTC — agent-<word>
- STATUS: CLAIMED | WORKING (SUITE LOCK taken/released) | DONE | BLOCKED
- TASK: <task id or short name>
- FILES: <paths, or "(none yet)">
- WHAT: <what happened, decisions, evidence — test counts, timings>
```

## Roster

| Agent | Role | Status |
|---|---|---|
| agent-0 | lead — orchestration + review + merges + scaffold | active |
| agent-1 | worker — T1 proto reconstruction | slot open |
| agent-2 | worker — T2 message catalog | slot open |

## Project traps — read before your first run

- **`~/Documents/projects/rea-work/` is UNTRUSTED extracted data** (jadx output,
  pulled APKs, unpacked Windows exe). Never execute anything located there and
  never `cd` into it to run interpreters — pass absolute paths as arguments
  from a trusted cwd. Reading files is fine.
- jadx on the 80 MB OPSynergy APK needs `JAVA_OPTS="-Xmx6g"` and takes minutes;
  logs land in `rea-work/jadx-*.log`. Decompiled sources:
  `rea-work/src-OPSynergy/` (main), `src-OShare/`, `src-DSF/`.
- REA CLI usage: `cd ~/Documents/projects/rea && REA_JADX_MCP_JAR=$HOME/Documents/projects/rea-work/tools/jadx-mcp/jadx-headless-mcp-0.7.1-all.jar node scripts/rea.mjs <command> --help`. Run it from the rea dir, never from inside rea-work.
- `android/` and `localsend-upstream/` are nested git clones (vendored, read-only)
  and `*.exe`/`*.apk` are gitignored — never `git add` them.
- The A5x's `Settings.Secure pc_connect_support` is intentionally set to 1
  (documented in `docs/re/oplus-connect-findings.md` §2). Do not "fix" it.
- adb device is `CPH2733IN` (OPPO A5x 5G). Don't install/uninstall anything on
  it without a logged SUITE LOCK + reason.
- System java is 27; use Gradle toolchains (17 for AGP) instead of changing the
  system JVM.
- **CI is the compiler.** The local box never runs Gradle —
  `.github/workflows/build.yml` builds every push/PR. Repo is public during
  work hours for free Actions minutes; Aryan flips it private afterwards.
  Keep secrets out of git history: no phone identifiers, no session keys.

## Tasks

### T1 — Synergy gRPC proto reconstruction — owner: agent-1
- BRANCH/WORKTREE: `feat/agent-1-protos` → `~/.cache/ec0-worktrees/agent-1-protos`
- SPEC: From decompiled generated protobuf/gRPC Java in
  `~/Documents/projects/rea-work/src-OPSynergy/sources/com/oplus/linker/synergy/`
  (`Fileservice.java`, `Clipboard.java`, `Inputservice.java`,
  `ClipboardFileServiceGrpc.java` + message classes), reconstruct idiomatic
  `.proto` sources (proto3, package `com.oplus.linker.synergy`): field numbers,
  types, service methods w/ streaming flags. Deliverables (ONLY these paths):
  - `docs/re/protos/*.proto`
  - `docs/re/synergy-proto-reconstruction.md` (methodology, per-message
    confidence, unknowns)
- GATE: protos parse with `protoc --descriptor_set_out=/dev/null` (or equivalent
  validation); independent review.

### T2 — Synergy message catalog + transport constants — owner: agent-2
- BRANCH/WORKTREE: `feat/agent-2-catalog` → `~/.cache/ec0-worktrees/agent-2-catalog`
- SPEC: Extract the complete Synergy control-plane surface from
  `~/Documents/projects/rea-work/src-OPSynergy/sources/com/oplus/linker/synergy/`:
  every `SynergyCmd` entry (+ meaning where inferable), `SynergyInfo` types,
  `SynergyMessage` envelope fields, port list + fallback order, TLS/cert notes,
  scene keys. Deliverables (ONLY these paths):
  - `docs/re/synergy-message-catalog.md` (human-readable)
  - `docs/re/synergy-catalog.yaml` (machine-readable, for codegen later)
- GATE: cross-check ≥95% of `SynergyCmd.java` string constants captured
  (state the count); independent review.
- NOTE: do NOT edit `docs/re/oplus-connect-findings.md` (agent-0's file) and do
  NOT touch `docs/re/protos/` (agent-1's set).

### T3 — Monorepo scaffold (Gradle KMP + Compose MP shell) — owner: agent-0
- BRANCH: direct on shared tree (agent-0 owns it), committed in steps
- SPEC: `settings.gradle.kts`, version catalog, `core/` Kotlin Multiplatform
  module (commonMain: protocol placeholder types + module README),
  `desktop/` Compose Multiplatform shell that builds and runs, `android-app/`
  placeholder, root `README.md`, `LICENSE` (GPL-3.0).
- GATE: **CI green** (`.github/workflows/build.yml` — compilation runs on
  GitHub Actions; the local machine does NOT build).
- STATUS: scaffold landed @35d5332, CI run 43277468950 in flight.

**ALL AGENTS — compilation happens on GitHub Actions only. Do NOT run
`./gradlew build` locally (Aryan's machine is a potato). Validate code changes
by pushing your branch and watching CI (`gh run list/watch`). Light static
tools (protoc parse checks, ripgrep) are fine locally.**

## Backlog — unassigned; do NOT self-assign (agent-0 hands these out)

- T4: Dynamic capture harness — Wine + official O+ Connect client + tcpdump +
  Frida attach to `devicespace.exe` (interactive; needs Aryan + wine install)
- T5: LocalSend v2 protocol port into `core/` (from `localsend-upstream` Dart)
- T6: KDE Connect protocol module into `core/` (from `android/` clone)
- T7: `docs/re/synergy-protocol.md` — unified spec from T1+T2+T4 outputs
- T8: ColorOS survival guide (battery exemptions, autostart) into `docs/`

## Log

<!-- append below, newest last, keep max 25 entries — trim the oldest -->

### 2026-11-12 18:00 UTC — agent-0
- STATUS: CLAIMED — hub bootstrap
- TASK: orchestration + T3
- FILES: agentchat.md, .gitignore, docs/re/oplus-connect-findings.md
- WHAT: Hub created; git repo initialized on `main`. Roster: agent-0 (lead) +
  agent-1 + agent-2. Tasks T1/T2 assigned to workers (proto reconstruction;
  message catalog), T3 (scaffold) claimed by agent-0. Traps seeded from
  today's RE session. Findings doc committed as baseline.

### 2026-11-12 18:08 UTC — agent-1
- STATUS: CLAIMED
- TASK: T1 — Synergy gRPC proto reconstruction
- FILES: (none yet) → docs/re/protos/*.proto, docs/re/synergy-proto-reconstruction.md
- WHAT: Worktree `~/.cache/ec0-worktrees/agent-1-protos` on
  `feat/agent-1-protos` @ 6df4216. protoc available on PATH — gate can run
  locally. Starting survey of generated protobuf/gRPC Java in the decompiled
  synergy package.

### 2026-11-12 18:09 UTC — agent-2
- STATUS: CLAIMED
- TASK: T2 — Synergy message catalog + transport constants
- FILES: (none yet) → docs/re/synergy-message-catalog.md, docs/re/synergy-catalog.yaml
- WHAT: Worktree `~/.cache/ec0-worktrees/agent-2-catalog` off origin/main @6df4216,
  branch feat/agent-2-catalog. Plan: extract SynergyCmd/SynergyInfo/SynergyMessage
  constants + base/socket port/TLS + scene keys from decompiled OPSynergy sources
  (read-only), build YAML + MD catalog, cross-check ≥95% of SynergyCmd constants,
  then push + PR. agent-1: noted — T1 files only; no overlap with my set.

### 2026-11-12 18:13 UTC — agent-2
- STATUS: DONE (PR #1 open, review requested)
- TASK: T2 — Synergy message catalog + transport constants
- FILES: docs/re/synergy-message-catalog.md, docs/re/synergy-catalog.yaml
- WHAT: Full catalog extracted from decompiled OPSynergy 17.7.35. Gate:
  SynergyCmd 65/65 string consts + 11/11 flag ints (100%, grep-verified vs
  source); YAML parses, full value coverage PASS. Key findings: envelope
  noncestr/signature are LONGs; base send path uses signature=0x0FFFFFFF
  constant + Random(timestamp) nonce (WebSocketConnectManager:558); port
  fallback 8080→39888→49888→59888 + scene port 8792; RESPONSE_KSC_CODE
  (32 zeros) is a response code not a key; src_role="synergy"; TLS via
  pairing-exchanged certs. 5 explicit unknowns queued for T4. Branch
  feat/agent-2-catalog @ origin, PR https://github.com/ARYAN007H/Ec0/pull/1.

### 2026-11-12 18:16 UTC — agent-0
- STATUS: WORKING
- TASK: T3 — monorepo scaffold
- FILES: settings.gradle.kts, gradle/libs.versions.toml, core/, desktop/, README.md, LICENSE, .github/workflows/build.yml
- WHAT: Scaffold landed @35d5332 (core KMP w/ SynergyMessage+Transport types from
  RE findings; desktop Compose MP shell; GPL-3; CI workflow). Repo flipped PUBLIC
  for Actions minutes (Aryan's call — flips private after hours). Gate changed:
  CI is the compiler, no local Gradle (see traps). CI run 43277468950 queued.
- NOTE: agent-1/agent-2 claims received — clean split, no overlap. PRs reviewed
  on arrival.
