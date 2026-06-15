# Specification Quality Checklist: Calendar Integration — Google Calendar

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

## Notes

- Items marked incomplete require spec updates before `/speckit.clarify` or `/speckit.plan`.
- **Validation outcome (2026-06-15)**: All items pass. Zero `[NEEDS CLARIFICATION]` markers — the F10 backlog entry plus the completed F01.1 token-store contract removed every material ambiguity (provider scope, token storage, retry policy, DST handling, and the F13/F20 boundary are all settled by the backlog and the existing `CalendarProvider`/`CalendarProviderClient` abstraction).
- **Scope boundary called out for plan review**: the spec deliberately scopes F10 to the Google adapter capability (free/busy read + event CRUD + compensating delete) and defers the atomic booking orchestration to F13. The Plan phase should confirm this split (and whether provider-event references persist here or in F13's booking records) before task generation.
- A few proper nouns appear (Google Calendar, `CalendarProvider`/`CalendarProviderClient`, `RestClient`, `TimeSlot`) — these are domain/integration boundary names carried over from the backlog and F01.1 contract, used to anchor the feature, not prescriptions of internal implementation. Acceptable per the project's house style (mirrors spec 006).

## Multi-role sub-agent review (2026-06-15)

Two reviewers ran against the spec + backlog (BA/QA fidelity; Security/GDPR baseline consistency). Findings and disposition:

- **BA/QA**: confirmed full coverage of all 7 F10 backlog acceptance criteria and all 3 user stories, with a correct, non-overlapping F10/F13 scope split. One actionable item — backlog says "max 3 retries" but the spec had generalised to "configured maximum." **Applied**: FR-013 now pins the default to 3.
- **Security/GDPR**: confirmed the headline free/busy-minimisation control (FR-002, SC-004) and token-via-F01.1-only (FR-016), workspace isolation (FR-018), and internal-ID-only audit (FR-020) are correctly and testably specified. Four requirement-completeness gaps raised and **all applied**:
  - GAP-1 (Cadence-created event's own title/location is candidate PII, must not be logged) → **FR-017a**, SC-003 extended.
  - GAP-2 (sanitise provider error/response bodies before logging) → **FR-017b**.
  - INC-1 (reaffirm F01.1's provider-account-email-is-PII guarantee for data F10 receives from Google) → **FR-018a**, SC-003 extended.
  - INC-2 (SC-007 "zero orphans" not achievable if the compensating delete itself fails) → **FR-016a** "cleanup-incomplete" outcome; SC-007 reworded.
  - GAP-4 (audit a calendar-op-triggered needs-reconnection occurrence) → **FR-020** extended.
- **Carried forward to /plan (not spec defects)**: the F01.1 `StructuredArguments.kv` enum footgun (log `provider.name()` Strings, never enums) and the FR-010 idempotency mechanism (provider-reference vs Cadence-side idempotency key) are implementation decisions for the plan/tasks phase.

## Multi-role plan review (2026-06-15)

Three reviewers ran against `plan.md` + `research.md` + `data-model.md` + `contracts/` and the existing F01.1 code (Backend/DevOps, Security/OAuth-GDPR, QA). **No reviewer raised an architecture-level blocker; all accepted findings were folded into the artifacts** (constitution §VI: findings applied, not discarded). Post-design Constitution Check re-ran **PASS, unchanged gate status**. Dispositions:

**Security/OAuth-GDPR**
- **B1 (BLOCKER) — APPLIED**: a stale freebusy-only connection returns `403 insufficientPermissions` on a write, which is **not** `invalid_grant` (a refresh succeeds but still lacks scope → silent write-loop-failure). D1/D8/D9 now detect insufficient-scope `403` distinctly and flip `NEEDS_RECONNECTION`; spec FR-015 widened; tested.
- **M1/M2 (MAJOR) — APPLIED**: `calendar.events` over-claimed least privilege (grants read of *all* events + larger token-compromise blast radius). Default corrected to **`calendar.events.owned`** + freebusy; §VIII justification rewritten; `calendar.events` demoted to a fallback with a re-recorded trade-off.
- **m3 (MAJOR-ish) — APPLIED**: Google returns `403` for rate-limiting too; the classifier is now **reason-aware** (`errors[].reason`), not status-only.
- **M3 (MAJOR) — APPLIED**: closed the `EventDetails.toString()` and RestClient **request-body** leak paths (redacting toString, no body-logging interceptor); FR-017b now explicitly covers request bodies.
- **M4 (MAJOR) — APPLIED**: `AvailabilityService.query` documented as a privileged internal primitive (no endpoint without an F13 role gate; guard note for `RbacEndpointInventoryTest`).
- **m1 (MINOR) — APPLIED**: log-scan sentinels expanded to five categories (added provider-account-email + dial-in/phone-number).
- **m5 (MINOR) — NOTED for F13/erasure owners**: `managedCalendarEvents` rows (no PII) should be cleaned on member erasure/`cancelBooking`; out of F10 scope.

**Backend/DevOps**
- **M1 (MAJOR) — APPLIED**: made the interface two-arg `validAccessToken` → three-arg `CalendarTokenService` delegation explicit + the token-layer transient re-wrap to `CalendarApiException`.
- **Executor (MAJOR) — APPLIED**: `DelegatingSecurityContextExecutorService` + MDC copy + `@Bean(destroyMethod="shutdown")`; preview bypasses the pool.
- **Stub (MAJOR) — APPLIED**: `StubGoogleCalendar` is a method-aware, per-op/per-eventId-sequenced **sibling** (not a `StubProvider` subclass), holding seeded content so SC-004 is non-circular.
- **MINOR — APPLIED**: `calendar.api` connect/read-timeout added; deterministic event-id input length-prefixed; new enums covered by the no-enum-to-`kv` rule; CI grep bans a `googleapis.com` literal in the client. **CONFIRMED**: Mongock-007 conventions, base32hex charset, base-url stub indirection, remove-not-drop isolation.

**QA**
- **APPLIED**: named **gated concurrent create** race test (one event + one row via the unique index); DST asserts the **recorded wire body** (offset + IANA zone, two straddling instants); zero-orphans asserted via the **stub's residual state** (not the self-reported outcome); FR-014 asserts the claim row is clean after an exhausted single create; SC-004 tied to a server-held sentinel; oversized/empty-window tests; five log-scan sentinels + positive vacuity guard; audit rows asserted content-free; SC-002 noted structural-not-timed; FR-020 reconnection-occurrence audit confirmed to reuse `CALENDAR_RECONNECT_REQUIRED` (no new enum). **CONFIRMED strong**: SC-001/006/008, REST contract, MICROSOFT-pre-F11 → NOT_CONNECTED.

## Multi-role tasks review (2026-06-15)

Two reviewers ran against `tasks.md` (coverage/traceability; sequencing/format/executability). **No blockers.** Both confirmed: all 8 SCs, the spot-checked load-bearing FRs, all 13 contract-§F obligations, every NEW/MODIFIED plan file, and all 13 plan-review corrections map to tasks; checklist format clean (T001–T050 contiguous, correct `[P]`/`[US]` labels); `GoogleCalendarClient.java` (T018/T025/T035/T039/T043) and `CalendarEventService.java` (T036/T040/T041/T043) edits correctly serialized (not `[P]`); TDD order correct; US1 is a genuine standalone MVP. Findings **APPLIED**:
- Added the `Participant(memberId, ZoneId)` input record to **T009** (was used by T036/T040 with no creation task).
- Assigned **SC-002** an owning assertion in **T031** (structural: one claim + one provider call; the ≤10 s target is the RestClient-bounded consequence).
- Noted in **T018** that `CalendarReconnectRequiredException`/`CalendarNotConnectedException`/`CalendarProviderTransientException` are **reused from F01.1** (they exist — no duplicate-creation task needed).
- Split the oversized stub task into **T020** (HttpServer + method/path matching + per-key status sequences + recording + gate) and **T020b** (the stateful event store + freeBusy projection) — same file, sequential.
- Noted (no change): T042's pure-unit classifier half can fail-first post-Foundational while its write-path assertions need US2 (documented US4→US2 dependency); FR-016 "no raw-token caching" is structurally enforced (T018 delegates) rather than separately tested.

## Implementation multi-role review (2026-06-15) — C6 / T050

Implementation complete and verified: **104 calendar tests + full backend suite green; 26 frontend tests + production build clean** (CLAUDE.md run flags). Two review loops on the actual code (user requested "multiple loops, max 3").

**Loop 1** — Security/GDPR, Backend, QA reviewers. No PII/secret/scope blockers (confirmed: free/busy-only read endpoint, least-privilege scope, redacting `EventDetails.toString`, no enum→`kv`, no provider body logged, self-scoped preview). Real findings, **all APPLIED**:
- **Concurrency (Backend BLOCKER ×2)** — the claim-then-create design let a concurrent claim-loser report `CREATED` for an event a failing winner then deleted, and `removeClaim` wasn't id-scoped. **Redesigned to provider-FIRST**: call the idempotent provider create (deterministic id → 409 == success) BEFORE recording; `recordCreated` upserts on the unique index and audits only the inserter; a provider failure writes no row (FR-014). Claim/removeClaim removed entirely.
- **token-inside-retry (Backend MAJOR)** — moved the bearer fetch inside `retry.execute` so each attempt gets a fresh/refreshed token.
- **cancelBooking partial failure (Backend MAJOR)** — now continues-and-marks `CLEANUP_INCOMPLETE` + audits per participant instead of aborting and leaving silent orphans.
- **Classifier 403 quota reasons (m1)** — added `dailyLimitExceeded`/`quotaExceeded`/`rateLimitExceededUnreg` to transient.
- **QA coverage** — concurrent-create test now uses the stub `gate(2)` (genuine overlap); added `CalendarApiResilienceIntegrationTest` driving the LIVE wiring end-to-end (429-recover; `403 insufficientPermissions`→NEEDS_RECONNECTION no-retry+audit+no-row; `401` freebusy→NEEDS_RECONNECTION; persistent-503 single create → no partial row). Classifier truth table extended.

**Loop 2** — focused re-review of the redesign: **VERDICT "fixed cleanly"**, no residual concurrency/correctness/security defect. One **low-severity** note accepted-and-documented (not fixed): a re-create-after-delete (an F20 reschedule path that does not exist yet) would not emit a fresh `CALENDAR_EVENT_CREATED` audit — deferred to F20 (§I YAGNI; no FR/security impact). Second note (fast-path doesn't re-verify out-of-band provider deletion) is by design.
