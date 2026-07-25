# CLAUDE.md Architecture-Brief Rewrite — Design

Date: 2026-07-25
Status: approved (user, in-session)
Branch: docs/claude-md-dedupe

## Problem

CLAUDE.md has grown to ~132 KB / ~385 lines, all loaded into every agent session's context. The bulk is per-feature Implementation Notes and cross-cutting footgun lists — content that is either derivable from the code and `specs/NNN-*/`, or narrative war-story detail that does not belong in a per-session instruction file.

## Decision

Rewrite CLAUDE.md as a hand-maintained **architecture brief** (~80 lines): stack, trimmed structure, commands, and ~18 one-line system/architecture decisions. No per-feature content, no code snippets, no war stories.

The cut content is **deleted outright** (user decision — "Delete outright" over archiving to docs/): recoverable only via git history and `specs/NNN-*/`.

## New file contents

1. **Stack** — backend/frontend/db/logging/deploy/CI, one line each.
2. **Structure** — directory tree, directories only.
3. **Commands** — dev/test/deploy, plus the one machine-specific line needed to run backend tests locally (JAVA_HOME, cached Gradle, zero-download rule).
4. **Architecture decisions** — one-liners covering: single-instance no-broker topology; same-origin SPA+API; cookie-JWT session model with server-side registry and persisted-role authorization; deny-by-default method security with build-time inventory test; PII field encryption + HMAC lookup hashes + zero-PII logging; tokenized no-login candidate surfaces with no-oracle error envelopes; GDPR erasure/resurrection model; provider seams (no SDKs, parse discipline); CAS + unique-index invariants and outbox pattern; scheduled sweeps with checkpoint/replay; injected Clock; append-only Mongock; testing architecture (singleton Testcontainers, JDK HttpServer stubs, WireMock ban); frontend rules (standalone, $localize, no SSR); build-time env injection; reuse-first dependency policy; secrets policy; encoding rules.

## Deleted with no relocation

All 20 Implementation Notes blocks, the cross-cutting footguns list, the per-feature deltas list, Recent Changes, the commit-hygiene essay, and code-style prose.

## Mechanics

- The entire hand-written body sits inside the existing `MANUAL ADDITIONS START/END` markers, so an accidental spec-kit `update-agent-context` run cannot wipe it (it only replaces content outside the markers; worst case it prepends regenerated boilerplate — duplication, not loss).
- The "Auto-generated from all feature plans" header becomes "Manually maintained".
- Committed on `docs/claude-md-dedupe`.
