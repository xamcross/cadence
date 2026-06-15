# Specification Quality Checklist: Calendar Integration — Microsoft 365 / Outlook

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-06-15
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Success criteria are technology-agnostic (no implementation details)
- [x] All acceptance scenarios are defined
- [x] Edge cases are identified
- [x] Scope is clearly bounded
- [x] Dependencies and assumptions identified

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria
- [x] User scenarios cover primary flows
- [x] Feature meets measurable outcomes defined in Success Criteria
- [x] No implementation details leak into specification

## Notes (spec phase)

- **Naming nuance**: The spec names Microsoft Graph, `getSchedule`, and the `Calendars.Read`/`Calendars.ReadWrite` scopes — these are *external provider contract* terms (the feature's subject and the load-bearing privacy/security control per backlog F11 + Security ISSUE-2), retained deliberately as in the approved F10 spec.
- Spec multi-role review (4 roles: BA, QA, Security/GDPR, Backend/DevOps) completed before planning; findings folded into the spec (FR-002a status mapping, FR-003 exact boundaries, FR-003a Windows/IANA, getSchedule structural preference, idempotency divergence note, `Calendars.ReadWrite` breadth, `Retry-After` formats, edge cases, SC-010/SC-011).

---

## Plan Quality & Multi-Role Plan Review (constitution §VI / C6 gate)

**Reviewed**: 2026-06-15 (user-requested "review with appropriate sub-agents"). Roles: **Backend/DevOps Lead**, **Security/OAuth/GDPR Lead**, **QA Lead**. Each reviewer verified the plan's claims against the **actual F10 source** (`CalendarProviderClient`, `GoogleCalendarClient`, `CalendarEventService`, `AvailabilityService`, `CalendarTokenService`, `CalendarConnection`, `AbstractOAuthGateway`, `CalendarApiRetry`, `StubGoogleCalendar`).

**Outcome**: 1 BLOCKER + ~12 SHOULD-FIX/NICE-TO-HAVE — **all applied** to `research.md`/`spec.md`/`contracts/`/`plan.md`. No finding added a dependency, service, or topology; gate status unchanged (**PASS**).

### Applied findings

| # | Sev | Source | Finding | Disposition |
|---|-----|--------|---------|-------------|
| B1 | **BLOCKER** | QA | "Retry-After waited ≥ interval" only assertable by flaky wall-clock; loop discards the exception's `retryAfter`, runs with `PT0S` | Extracted pure `nextWaitMillis(attempt,retryAfter)`=`max(backoff+jitter,retryAfter)`, unit-tested with no sleep; both header forms parsed to `Duration`; no wall-clock test (research D7, plan, contract §F) |
| S1 | SHOULD | Backend+Security | `accountFromIdToken` can return opaque `sub` (non-SMTP); naive null-check misses it → silent wrong availability | Adapter treats non-`@` `providerAccountId` as `NEEDS_RECONNECTION`; dedicated `sub`-only test (research D2a, contract §F, plan file-list) |
| S2 | SHOULD | Backend | `CalendarEventService` body derives id at line 145 & discards `createEvent`'s return; `updatePanelEvents` doesn't load the row | research D5 now names: delete line-145 derivation, persist returned id, keep fast-path stored-id return, add row lookup in `updatePanelEvents` |
| S3 | SHOULD | Security | Spec FR-002/SC-004 still assert a *structural* guarantee Graph getSchedule doesn't provide for a self-mailbox | Spec FR-002/SC-004 reworded: control is parse-discipline (explicit path reads) verified by non-circular SC-004 |
| S4 | SHOULD | Security | `transactionId` over-promises "one event" (bounded-window dedup, not durable) | research D5 "honest bound": durable guarantee = unique-index claim; out-of-window double → one row + reconciled orphan; stub models the window |
| S5 | SHOULD | Security | SC-004 could regress if a later field binds content | Mapper uses explicit `path("start"/"end"/"status")` reads, never full-object deserialization (research D2, contract §F) |
| Q1 | SHOULD | QA | Status mapping needs per-status assertions, not a bulk check | Contract §F: six **distinct** per-status assertions (free schedulable; busy/tentative/oof/workingElsewhere/unknown busy) |
| Q2 | SHOULD | QA | getSchedule 20-mailbox cap / chunking undocumented | research D2: one getSchedule per member (single mailbox) → cap never applies; chunking explicitly deferred |
| Q3 | SHOULD | QA | All-day / recurring expansion untested | Contract §F: seed all-day + two-occurrence items, assert exact spans survive |
| Q4 | SHOULD | QA | "No retry" self-reported, not proven | Contract §F: assert `StubGraphCalendar.count(method,path)==1` on the 401/403 path; dedicated SC-011 audit assertion |
| N1 | NICE | QA | Mixed test must reset both stubs | Plan test-isolation note: `reset()` both stubs in `@BeforeEach` |
| N2 | NICE | QA | Gated create must gate on the provider POST to be non-vacuous | Contract §F: `gate(n)` fires on `POST …/events` |
| N3 | NICE | QA | Empty `scheduleItems`/`value` array parse foot-gun | Contract §F: empty array → `DATA`+[] (no NPE on `value[0]`) |
| N4 | NICE | Backend+Security | `MicrosoftOAuthGateway` Javadoc stale (old scope) | Added to plan modified-files list (comment-only) |
| N5 | NICE | Security | Verify the F01.1 consent UI shows the expanded scope (informed consent for ReadWrite) | Carried into tasks as an open verification (plan Post-Design Re-Check) |

### Reviewer-confirmed sound (no change needed)

- The **interface refactor (D5)** is genuinely necessary (Graph forbids client-supplied ids — verified against `GoogleCalendarClient.updateEvent/deleteEvent` re-derivation) and behaviour-preserving for Google.
- **Index reuse (D13)** is index-safe — `provider` is the always-non-null 4th key of `ChangeUnit007`'s unique index; no new changeset.
- **Cross-provider rollback (D9)** works by construction — `rollback`/`cancelBooking` already dispatch per-entry `clients.get(provider).deleteEvent(...)`.
- **Classifier (D6)** correctly Graph-aware (`403`→reconnect; Graph throttles on `429`, not `403`; parse `error.code` only).
- **Constitution gates** C2/C4/C7 hold; **§VIII** scope expansion honestly justified (broader than F10; no narrower Graph scope exists; identity scopes benign; stale-grant → tested reconnect).
- **enum→`kv` Jackson-3 crash foot-gun** carried forward correctly (no new enum; log `.name()`/ids only).

**Status**: plan, research, data-model, contracts, and quickstart complete.

---

## Tasks Quality & Multi-Role Tasks Review (constitution §VI / C6 gate)

**Reviewed**: 2026-06-15 (user-requested "review with appropriate sub-agents"). Roles: **Backend/Implementation Lead**, **QA Lead**, **Delivery/Project Lead**. Each verified `tasks.md` against the design AND the **actual F10 source** (file paths, signatures, call sites, the existing `CalendarApiRetryTest`).

**Outcome**: 1 BLOCKER + several SHOULD-FIX — **all applied** to `tasks.md` (+ a backlog erratum). No finding changed scope or gates; all three reviewers approved with fixes.

| # | Sev | Source | Finding | Disposition |
|---|-----|--------|---------|-------------|
| B1 | **BLOCKER** | Backend | T010 changing `CalendarApiClassifier.classify(status,reason)` signature would break the existing F10 `CalendarApiRetryTest` (compile error) | T010 reworded: **add** `classifyGraph(status,code)`, leave the 2-arg `classify` untouched; Google keeps calling `classify`, MS calls `classifyGraph` |
| S1 | SHOULD | Backend | T007 over-claimed F10 test edits — no test/caller invokes the client update/delete directly (only the service does) | T007 reframed: run F10 suite green; no test edits expected unless a compile error surfaces |
| S4 | SHOULD | Backend+QA | The shared F01.1 `CalendarItBase.idToken(...)` helper always embeds `email`; a `sub`-only seed (for the D2a reconnect test) needs a new variant there | T015 now calls out extending the shared helper with a `sub`-only id_token variant |
| SF-1 | SHOULD | QA | SC-001 (5-member panel) + US1-2 had no test | T016 now asserts a 5-member Microsoft panel via `AvailabilityService.query` with exactly 5 recorded getSchedule calls (bounded-parallel proxy) |
| SF-2 | SHOULD | QA | FR-003a Windows-vs-IANA read-side untested | T016 now asserts every getSchedule sends `Prefer: outlook.timezone="UTC"` and UTC parses correctly across DST; SC-010's Windows clause satisfied write-side (T023) |
| SF-3 | SHOULD | QA | The "no-chunking" deferral (one getSchedule/member) wasn't locked by a test | T016 asserts each getSchedule request has exactly one `schedules[]` entry |
| P1 | SHOULD | Delivery | "US1 ∥ US2" parallelism is false — both edit `MicrosoftCalendarClient.java` | Removed the claim in both prose spots; sequence US1→US2 on the shared client |
| P2 | SHOULD | Delivery | `application.yml` comment block (lines ~72–74) still has the stale "free/busy / field projection" framing | Folded into T001 (also update the comment block) |
| P3 | SHOULD | Delivery | Backlog F11 "Graph permission scope" (lines 230/238) is stale and contradicts the approved plan | **Backlog erratum added** (`docs/backlog.md` F11) pointing at the spec/plan's `Calendars.ReadWrite` + parse-discipline decision |
| — | NICE | Backend+Delivery | `transactionId` reuse of `GoogleEventId.of` sound (add a comment); SC-002 is a structural call-count proxy; T019/T029 are confirmation tasks | Accepted as-is (T024 comment + SC-002 wording already say "structurally"); no change needed |

### Reviewer-confirmed sound (no change)

- **Coverage**: every plan modified-file maps to a task with a correct path; every SC-001..SC-011 and US1..US5 has a test task (SC-001/FR-003a were the two gaps, now closed); no stray migration/dependency/`.ps1` task (constitution honored).
- **Sequencing**: putting the F10-touching interface refactor (T004–T007) in Phase 2 before any story, gated by a green-F10 check (re-run at T036), correctly isolates the largest regression risk.
- **MVP/§II**: US1 (preview) is a genuine standalone end-to-end increment; event-write/mixed-panel delivered as tested-against-stub capability (not dead code, no F13 scope creep).
- **Non-vacuity**: SC-004 (seed-then-absent), rollback (stub residual store), concurrency (gate on `POST …/events`), DST (recorded body), no-retry (stub `count==1`), Retry-After (pure `nextWaitMillis`, no wall-clock) all assert against stub/DB state.
- **§VI**: the multi-role review-at-close is task T038.

**Status**: spec → plan → tasks complete and reviewed.

---

## Implementation & Multi-Role Implementation Review (constitution §VI / C6 gate)

**Implemented**: 2026-06-15 (`/speckit.implement`, all 38 tasks). Backend full suite GREEN (incl. ~50 new `com.cadence.calendar.Microsoft*` + `MixedProviderPanelTest` + the `classifyGraph`/`nextWaitMillis`/`parseRetryAfter` unit tests, and all F10/F01.1/F02/F03/F04 + `RbacEndpointInventoryTest` still green after the interface refactor). Frontend `ng test` 26/26 + `ng build` clean. CI base-URL guards (`graph.microsoft.com` / `googleapis.com`) pass.

**Two F10/F01.1 tests updated for F11's intentional behaviour changes** (not weakened): `CalendarConnectIntegrationTest` MS-scope assertion (now `Calendars.ReadWrite` + `openid`, additive — the Google no-write-scope test is untouched); `CalendarPanelAvailabilityTest` (a Microsoft member is now readable, was NOT_CONNECTED pre-F11).

**Review loops (user-requested "review with appropriate sub-agents, max 3"):**

- **Loop 1** — Backend/correctness, Security/PII, QA/test-rigor (each verified against the real diff). **No BLOCKERs.** Applied findings:
  - [QA Q1] `classifyGraph` had no pure-unit truth table (the existing one tested the *Google* classifier) → added `classifyGraph_truthTable` (Graph: any `403`→RECONNECT, `429`→TRANSIENT, contrasted with Google's `403 rateLimitExceeded`→TRANSIENT).
  - [QA Q2] Retry-After loop wiring only proven with value `0` → changed the carry test to a **non-zero** `Retry-After: 2` asserting `getRetryAfter()==Duration.ofSeconds(2)` (still no wall-clock assertion).
  - [QA Q3] Audit "no content/payload" was commented, not asserted → added raw-`authAuditLog` assertions (no `SENTINEL_TITLE/LOC` on create; no `@` account/member email on the reconnect audit).
  - [Security] CI event-content scan greps only F10 sentinels → extended the `ci.yml` alternation with the five F11 `SENTINELMS*` / `sentinel-ms-*` sentinels (defense-in-depth; the in-JVM `MicrosoftEventLogPiiScanTest` already covered them).
  - Backend NICE-TO-HAVEs (redundant connection read in `queryFreeBusy`; `Instant.now()` vs injected Clock in `parseRetryAfter`; unconditional `CALENDAR_EVENT_UPDATED` audit on a no-op) — **dispositioned as immaterial / F10-inherited**, not changed.
- **Loop 2** — final verification + adversarial sweep (correctness + security/test). Both reviewers returned **"LGTM — no material findings"**: the three fixes are correct and meaningful; the `updatePanelEvents` row-lookup `continue` is the only correct Graph behaviour (server id is unknowable without a row, no production caller yet, no audit-on-missing expectation); concurrent create cannot diverge on the stored id (both threads get the same `transactionId`-deduped server id); the DST negative guard (`06:00` = naive-UTC bug value) is real; exception retry/no-retry routing is correct; CI/test sentinels match exactly; the audit no-`@` invariant is sound (`AuthAuditEvent` has no free-text field); StubGraphCalendar genuinely emits the PII the tests scrub (non-circular).
- **Loop 3** — not needed (loop 2 clean).

**Reduced-scope decision surfaced**: no Microsoft-specific **Playwright** E2E was added (T034) — the frontend is provider-identical and the §II end-to-end leg is covered by the real-controller contract test (`provider: MICROSOFT`) + the existing provider-agnostic Playwright; a browser MS variant would need Graph-stub wiring in the ng-serve backend for no new correctness coverage.

**Status**: F11 implemented, reviewed (2 loops), and green. Not committed (per user's standing preference).
