# Specification Quality Checklist: OAuth Token Store (Calendar Connections)

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

- **Spec validation (2026-06-15)**: all items pass. SC-005 was re-scoped during the plan review from a flaky wall-clock 5-second assertion to a structural one-call-plus-one-write assertion bounded by RestClient timeouts (QA #4).

---

# Plan Review Log (Constitution §VI — Mandatory Multi-Role Sub-Agent Review)

**Date**: 2026-06-15 | **Trigger**: user-requested "review with sub-agents" | **Reviewers (4 roles)**: Backend/DevOps, Application Security / OAuth, QA, Front-End. **All four verdicts: APPROVE-WITH-CHANGES. No BLOCKERs** (one Security item was raised as a candidate blocker and downgraded to MAJOR after confirming no member-erasure path exists yet).

Every finding below is either **APPLIED** (folded into the named artifact) or **NOTED** (confirmed correct / no change). No finding was silently discarded (§VI).

## Backend/DevOps
| # | Sev | Finding | Disposition |
|---|---|---|---|
| 1 | MAJOR | Callback on `/api/**` returns bare 401 if session expired during consent | **APPLIED** — scoped callback `AuthenticationEntryPoint` → `?error=session_expired` (research D8, contracts §3, plan SecurityConfig change) |
| 2 | MAJOR | TTL index API: `expireAfterSeconds:0` as an index key builds a plain field index, not TTL | **APPLIED** — `new IndexOptions().expireAfter(0L, TimeUnit.SECONDS)` (data-model §2/§5, research D8) |
| 3 | MAJOR | Single-use consume must be atomic `findAndRemove`, not find+delete (TOCTOU) | **APPLIED** — `mongoTemplate.findAndRemove`; repo has no plain finder (data-model §2, research D4, contracts §3) |
| 4 | MINOR | No circular bean dependency (verified); keep `disconnectAll` orchestrator-free; best-effort | **NOTED + APPLIED** — research D12 states the graph has no cycle + best-effort wrap |
| 5 | MINOR | `$unset` on a converter field → ClassCastException; use `.set(field,null)` | **APPLIED** — data-model §1 invariant (F03 lesson) |
| 6 | MINOR | `RestClient` has no default socket timeout (stalls free/busy; blows SC-005) | **APPLIED** — `CalendarOAuthProperties` connect/read timeouts (research D9, plan) |
| 7 | MINOR | Use singleton `@ServiceConnection` container; reset WireMock stubs between tests | **APPLIED** — plan test-isolation note |
| 8 | MINOR | `MongoPiiConfig` Javadoc drift | **NOTED** — cosmetic; update Javadoc when editing (tasks) |
| 9 | MINOR | Explicit endpoint URIs vs issuer-uri correct | **NOTED** — confirmed (research D9) |

## Application Security / OAuth
| # | Sev | Finding | Disposition |
|---|---|---|---|
| 1 | MAJOR | Bare-401 callback dead-end (= Backend #1) | **APPLIED** (as Backend #1) |
| 2 | MAJOR | Redirect target must be server-side `spaBaseUrl` constant, never request-derived (open-redirect) | **APPLIED** — research D8, contracts §3 |
| 3 | MAJOR | Mix-up: drive token exchange off the consumed `state.provider`, not the path | **APPLIED** — research D4, data-model §2, contracts §3 |
| 4 | MINOR | Add cross-member graft test (A's state in B's session) | **APPLIED** — plan CalendarConnectIntegrationTest (labelled FR-018/SC-007) |
| 5 | MINOR | State repo must expose single-consume only, no plain finder | **APPLIED** (= Backend #3) |
| 6 | MAJOR↓ | Member-**erasure** cleanup only deactivation-wired; `providerAccountId` is PII | **APPLIED** — `disconnectAll` canonical + wired into deactivation + directly tested + documented future-erasure must call it (research D12). Downgraded: no member-erasure path exists yet |
| 7 | MINOR | Null the worthless access token on `invalid_grant` (data-minimisation) | **APPLIED** — research D6, data-model §1 |
| 8 | MINOR | Rotation: preserve existing refresh token when response omits a new one | **APPLIED** — research D6, data-model §1, plan refresh test |
| 9 | MINOR | Record the MS `Calendars.Read` scope justification in an auditable location | **APPLIED** — research D7 (auditable) + asserted by the scope contract test |
| 10 | MINOR | `Cache-Control: no-store` on `GET /connections` (PII) | **APPLIED** — contracts §1, plan RBAC test |
| 11 | MINOR | Failed-revoke is the likeliest token-in-log path; cover it in the scan | **APPLIED** — research D11, plan log-scan test |
| — | INFO | State replay / code-injection / encryption / toString / CAS / structural isolation all sound | **NOTED** — confirmed |

## QA
| # | Sev | Finding | Disposition |
|---|---|---|---|
| 1 | MAJOR | FR-002 free/busy scope has no asserting test | **APPLIED** — authorize-URL scope assertion (plan, contracts §2) |
| 2 | MAJOR | Offline params (`access_type=offline`/`offline_access`) untested | **APPLIED** — folded into #1 |
| 3 | MAJOR | FR-010 provider-account-email sentinel missing from the log scan | **APPLIED** — research D11, plan log-scan test |
| 4 | MAJOR | SC-005 (5 s) not CI-verifiable as written | **APPLIED** — spec SC-005 re-scoped to structural one-call + timeout bound |
| 5 | MINOR | FR-013 "old token unused" needs a positive assertion (next refresh uses the new one) | **APPLIED** — plan refresh test (WireMock body match) |
| 6 | MINOR | Concurrency test can vacuously pass without gating the token response | **APPLIED** — plan refresh test gates the WireMock response until all N threads pass expiry |
| 7 | MINOR | Transient failure must leave the row byte-identical | **APPLIED** — research D6, plan reconnect test |
| 8 | MAJOR | Callback negative redirects (consent_denied / no_offline_grant / provider-mismatch) untested | **APPLIED** — plan CalendarConnectIntegrationTest enumerates all four |
| 9 | MAJOR | "Provider returns no refresh token" edge has no dedicated test | **APPLIED** — folded into #8 (`?error=no_offline_grant`) |
| 10 | MINOR | Assert revoke WAS attempted on happy-path disconnect | **APPLIED** — plan disconnect test (WireMock verify) |
| 11 | MINOR | Multi-provider deactivation cleanup untested | **APPLIED** — plan disconnect test seeds both providers |
| 12 | MINOR | Unsupported provider on callback + DELETE (not just start) | **APPLIED** — plan RBAC test |
| 13 | MINOR | FR-020 audit events have zero coverage | **APPLIED** — audit assertions added to connect/disconnect/reconnect tests; contracts §4b |
| 14 | MINOR | Label the `state.memberId != session` test as the FR-018 defense | **APPLIED** — plan test comment |
| 15 | — | Clock-skew edge adequately covered | **NOTED** — satisfied |

## Front-End
| # | Sev | Finding | Disposition |
|---|---|---|---|
| 1 | MINOR | Nav link must sit outside every role `@if` but inside `@if(member())` | **APPLIED** — research D14 |
| 2 | MAJOR | Connect must use `window.location.href`, not `Router`; `start` returns JSON 200 not 302 | **APPLIED** — research D14, contracts §2, plan component note |
| 3 | MAJOR | Must read `?connected`/`?error` return param and render a banner | **APPLIED** — research D14, plan component + spec test |
| 4 | MINOR | CSRF auto-attached if service uses `${environment.apiBaseUrl}` base | **APPLIED** — research D14, plan service note |
| 5 | MINOR | "Connected as {acct}" via `$localize` named placeholder, not concatenation | **APPLIED** — research D14 |
| 6 | MINOR | Reuse GDPR a11y pattern (confirm signal, role="alert", ≥44px) | **APPLIED** — research D14 |
| 7 | MINOR | Add Jasmine cases for each `?error=` render + Connect-disabled-in-flight | **APPLIED** — plan component spec |

## Constitution gate impact
No accepted finding added a dependency, service, queue, replica, or stack element, or exposed candidate PII. C1–C7 remain PASS. The single `SecurityConfig` change is a scoped entry-point addition that preserves the F00 actuator and F01 `/api/**`-401 contracts. **Post-design gate: PASS (unchanged).**

---

# Tasks Review Log (user-requested "run sub-agent review")

**Date**: 2026-06-15 | **Reviewers (2)**: Task completeness/traceability, Backend technical-accuracy. **Both verdicts: APPROVE-WITH-CHANGES, no BLOCKERs.** Full traceability confirmed: all 20 FRs, all 7 SCs, all 9 named test classes, and every plan.md source-tree file map to a task; TDD ordering, story labels, and the §VI close-out (T053) + `RbacEndpointInventoryTest`-green (T052) checks are present.

| # | Sev | Finding | Disposition |
|---|---|---|---|
| C-5 | MAJOR | US2 `CalendarTokenService` (T036) failure-path is deferred to US4 → US2 in isolation could clobber a row on a provider error | **APPLIED** — T036 now guarantees no partial write on any refresh failure (CAS `$set` only on success) |
| C-1 | MINOR | Non-grammar `[TDD]` tag on T016 | **APPLIED** — removed; "write first/FAIL" kept in body |
| C-6 | MINOR | Callback unsupported-provider redirect (not 500) unasserted | **APPLIED** — added to T023 |
| C-8 | MINOR | T033 marked `[P]` but depends on T030 | **APPLIED** — `[P]` removed, sequenced after T030 |
| C-10 | MINOR | Open-redirect negative (host can't be influenced) untested | **APPLIED** — added to T023 |
| B-1 | MINOR | Audit uses generic `record(type,ws,member,outcome,sourceIp)`; no calendar method | **APPLIED** — T026/T039/T044 specify `record(..., sourceIp=null)` (null-safe) |
| B-2 | MINOR | Interface renamed `CalendarProviderClient` / enum `CalendarProvider` vs data-model §6 | **APPLIED** — note added to T014 |
| B-3 | MINOR | T022 callback entry point must be registered BEFORE the `/api/**` 401 mapping (order-sensitive) | **APPLIED** — T022 made explicit + T024 asserts non-callback path still 401s |
| B-4 | MINOR | No task wires a provider→`OAuthGateway` resolver | **APPLIED** — T025 injects `List<OAuthGateway>` → `Map` by `id()` |
| C-3/C-4/C-7/C-9 | — | Cold-path test placement, quickstart consume-only, independence, no missing required tasks | **NOTED** — confirmed sound, no change |

No accepted finding changed scope, added a dependency/service/topology, or moved a constitution gate. Tasks ready for `/speckit.implement`.

---

# Implementation Review Log (Constitution §VI — Multi-Role Sub-Agent Review at task close)

**Date**: 2026-06-15 | **Branch**: `006-oauth-token-store`. Full backend suite green (incl. 45 calendar tests + `RbacEndpointInventoryTest`), 23 frontend tests green, `ng build` clean. Two review loops run (user requested up to 3).

## Loop 1 — Backend, Security, QA (parallel)
- **Security: APPROVE** (no blockers/majors) — all 9 properties verified in code: single-use `findAndRemove` state consume (no plain finder), PKCE S256, double-binding + mix-up defense, encryption-at-rest of all 4 secret fields, zero-secret logging, server-side-only redirects, free/busy scopes, structural cross-member isolation, rotation-preserve, `no-store`.
- **Backend: APPROVE-WITH-CHANGES** — **[MAJOR]** concurrent first-connect could throw an uncaught `DuplicateKeyException`→500 → **FIXED** (`upsertConnected` catches it; connect is idempotent). **[MINOR]** `markNeedsReconnection` version-guarded flip could no-op-but-audit → **FIXED** (flip on `{_id,status==CONNECTED}`, audit only on match). **[MINOR]** gateway `revoke` could surface a provider body on error → **FIXED** (swallows internally). Off-by-one retry note → kept "retries" semantics, pinned by test.
- **QA: APPROVE-WITH-CHANGES** — **[MAJOR]** concurrency test could pass vacuously → **FIXED** (`StubProvider.gate(n)` forces all N refreshes to contend; asserts `count==n` raced + `tokenVersion==start+1`). **[MAJOR]** missing expiry unit test → **ADDED** `CalendarTokenExpiryTest` (skew boundary). **[MAJOR]** FATAL branch untested → **ADDED** `fatalFailure_...` (1 POST, no flip). **[MINOR]** transient retry bound unasserted → **ADDED** `count==4`. **[MINOR]** MS scope unasserted → **ADDED**. **[MINOR]** frontend disconnect-confirm untested → **ADDED**.

## Loop 2 — Backend-fix + Test-strengthening verification (parallel)
- **Both: APPROVE**, no blockers/majors. Confirmed all three code fixes correct and complete (DuplicateKey catch scoped correctly; `markNeedsReconnection` concurrency-correct with no double-audit; revoke never throws and never logs a body) and that the strengthened tests genuinely prove their properties (gate is non-vacuous + deadlock-free; FATAL vs TRANSIENT distinguished by POST count; expiry skew boundary exercised). One **[MINOR, optional]** left: the frontend confirm test arms via the signal rather than a template click — accepted as a coverage nicety (the guard behavior is correct and tested at the state level).

**Result: §VI review satisfied — every finding applied or explicitly accepted; no constitution gate moved.**
