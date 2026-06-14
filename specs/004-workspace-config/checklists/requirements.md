# Specification Quality Checklist: Workspace Setup & Configuration (F03)

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-06-14
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

> Note: A "Notes for Planning" and "Constitution Alignment" section reference concrete mechanisms (Spring Security, AES-256-GCM, Mongock) by name. These are deliberately quarantined as *informational planning hints* (the same pattern as the approved F02 spec) so the Constitution Check in plan.md passes cleanly; the mandatory spec sections themselves (User Scenarios, Requirements, Success Criteria) remain technology-agnostic.

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

## Multi-Role Sub-Agent Review (Constitution C6 — performed at spec stage)

Reviewers (2026-06-14): **Security/GDPR Lead**, **Backend/DevOps Lead**, **QA Lead**, **Business Analyst**. All four verdicts: **APPROVE-WITH-CHANGES**. Findings applied to the spec below (and to `docs/backlog.md` for the two scope decisions). Two scope items were escalated to the stakeholder and confirmed (defer retention enforcement to F04; defer workspace-language setting).

| # | Role | Severity | Finding | Resolution |
|---|------|----------|---------|------------|
| 1 | Sec / BA | MAJOR | Retention enforcement deferred to F04 contradicts backlog ISSUE-10 (listed under F03); presented as settled without sign-off | **Stakeholder-confirmed defer to F04.** Recorded as a scope decision in FR-022 + backlog F03 note |
| 2 | BA | MAJOR | §5.4 FR-20 "languages" workspace setting silently missing | **Stakeholder-confirmed defer** (single-language EN MVP); explicit Assumption added + backlog note |
| 3 | QA | BLOCKER | Validation bounds undefined → FR-005/FR-012/SC-008 untestable | Concrete bounds pulled into FR-005/FR-012 (SLA 1–30d, retention 30–3650d, logo PNG/JPEG ≤1MB, colour `#RRGGBB`, IANA tz, wall-clock hours); SC-008 enumerates boundary cases |
| 4 | Sec / BE | MAJOR | Credential: needs non-deterministic cipher, no derived/hashed value, masked = pure boolean; encryption-at-rest ≠ never-return; converter is per-field & decrypts on read | FR-016/FR-017 rewritten (two separate controls; structurally non-serializable write-only field; boolean indicator); Notes for Planning split the two controls + raw-driver ciphertext test |
| 5 | Sec / BE | MAJOR | Logo: SVG/active-content XSS, content-type spoof, decompression bomb; also bloats single config doc / 16MB BSON | FR-012 hardened (raster allow-list, SVG rejected, magic-byte validation, size+dimension caps); logo stored as referenced sibling/GridFS doc (Key Entities, Assumptions, Notes) |
| 6 | Sec | MAJOR | Credential leaks via poly-consumer read paths (public branding read loads whole doc) | FR-017 makes exclusion structural across all current/future read paths incl. candidate-facing |
| 7 | BE | MAJOR | FR-010 mis-framed ("torn document"); real risk is lost-update | FR-010 reworded to no-lost-update via targeted field updates; SC-009 asserts different-field preservation |
| 8 | QA | MAJOR | Missing: credential rotate/unset, branding unset, restart-coverage for credential/branding | US4 AS-6/7/8, US3 AS-6 added; SC-004 extended; edge case added |
| 9 | QA | MAJOR | FR-006 one-way transition has no direct-API re-completion negative test; no concurrent first-run race | US1 AS-6/7 added; SC-009 + edge case cover the first-run race (atomic upsert) |
| 10 | QA | MAJOR | Default brand values undefined; SC-011 unobservable | Default `#1F2937` + placeholder documented (FR-013/Notes); SC-011 reworded to what F03 observes |
| 11 | QA | MAJOR | Per-role test weaker than F02; SC-001/002 didn't enumerate the 5×4 matrix | SC-001/SC-002 rewritten to the explicit 5-surface × 4-role matrix |
| 12 | Sec / QA | MINOR | GDPR acknowledgment not evidentially recorded; audit not append-only | FR-004 records actor/timestamp/period; FR-026 + SC-013 make audit append-only; SC-003 asserts the ack record |
| 13 | BE | MINOR | Mongock id/rollback convention; singleton needs atomic upsert; endpoints need internal prefix; surface `configured` on `/me` | All added to Notes for Planning |
| 14 | QA / BE | MINOR | timezone/DST + working-hours semantics; retention=0; partial branding; log scan at DEBUG/TRACE; toString safety | FR-005 (IANA/wall-clock/no-overnight), SC-008 (=0 case), US3 AS-4 (per-attribute), SC-005/FR-018 (DEBUG/TRACE + toString/error paths) |
| 15 | QA / BA | MINOR | US5 AS-4 (F21 lock) not F03-testable; US6 non-admin-on-unconfigured undefined; sending-domain homograph | US5 AS-4 tagged forward-contract; US6 AS-5 added; domain homograph note added to Notes |

## Multi-Role Sub-Agent Review — PLAN stage (Constitution C6, 2026-06-14)

Reviewers: **Backend/DevOps**, **Security/GDPR**, **QA**, **Front-End**. Verdicts: Backend APPROVE-WITH-CHANGES; Security CONDITIONAL-PASS (2 BLOCKER); QA CONDITIONAL-PASS (2 BLOCKER); Front-End CHANGES-REQUESTED. All findings dispositioned below; load-bearing fixes folded into `plan.md`/`research.md`/`data-model.md`/`contracts/`. Constitution re-check after fixes: **PASS** (no gate to FAIL).

| # | Role | Severity | Finding | Resolution |
|---|------|----------|---------|------------|
| P1 | BE | MAJOR (BLOCKER-class) | Upsert idempotency hole: a pre-existing *unconfigured* doc lets two concurrent setups both `$set`/double-audit | research D4 — reads are strictly read-only (never get-or-create), so the wizard upsert is the only inserter; concurrent loser hits unique-index DuplicateKey → 409 |
| P2 | SEC | BLOCKER | CI secret log-scan doesn't exist (greps emails only) → SC-005 passes vacuously | research D8 + plan — **extend** `ci.yml` with secret pattern set + sentinel credential token (tasked CI change) |
| P3 | SEC | BLOCKER | Public `/logo` headers/DoS: CSP is wrong tool for an image; no nosniff/Cache-Control | research D6 + contract — `X-Content-Type-Options: nosniff`, `Cache-Control: public,max-age=300`+ETag, `Content-Disposition: inline`, sandbox CSP as defense-in-depth |
| P4 | BE / SEC | MAJOR | Never-return is by-convention; entity carries decrypted credential; record DTO `toString()` leaks on validation error | research D2 / data-model — `@JsonIgnore` + hand `toString()` on entity; `EmailConfigRequest` a **class** with omitting `toString()`; test serializes the entity |
| P5 | BE / SEC / QA | MAJOR | Decompression bomb: dimension check after full `ImageIO.read` OOMs first | research D6 — header-only dimension read via `ImageReader` BEFORE bounded decode; null/IOException → 400 |
| P6 | QA | BLOCKER | "Restart-persistence" unimplementable as a real restart with the singleton container | plan test — re-read via a COLD `MongoTemplate` (new client + fresh `MongoPiiConfig` converter), asserts credential decrypt + raw-driver ciphertext |
| P7 | QA | MAJOR | Concurrency tests lack a latch + no-lost-update / both-audited assertions | plan tests — `CountDownLatch` (F02 last-admin pattern, N≥20); both-field-preserved; one configuredAt + both audited |
| P8 | QA | MAJOR | Boundary cases under-enumerated (SLA/retention/hours/colour) | plan — `WorkspaceConfigServiceTest` parameterized with the exact accept/reject points |
| P9 | QA | MAJOR | SC-005 scan not at TRACE, misses rotate/unset + literal value | plan — set root TRACE; drive set→rotate→unset→error; assert sentinel value + secret regex absent |
| P10 | QA | MAJOR | US3 AS-6 (unset-logo audit) + US6 AS-5 (non-admin on unconfigured) had no home | plan — `BrandingIntegrationTest` unset+audit; `shell.component.spec` "setup pending"; RBAC test covers unconfigured state |
| P11 | SEC | MAJOR | Retention-ack durability: config field is mutable; audit must be authoritative | research D8 / data-model — audit row is the legal artifact; `configuredAt`/`retentionAcknowledgedAt` immutable post-setup; `WORKSPACE_CONFIGURED.newValue` non-null asserted |
| P12 | SEC | MAJOR | Public branding is an existence/colour-set oracle; contradicts "no setting state" claim | research D5 / contract / C3 — documented as **intentional** (branding is public-by-design); only the two brand attributes exposed |
| P13 | FE | MAJOR | `workspaceConfigured` not wired to frontend `MemberSummary`; shell can't see it | plan — `auth.models.ts` MODIFIED; rides `member$`; `role.guard.spec` factory updated |
| P14 | FE | MAJOR | Wizard redirect can't be a template side-effect; stale `member$` re-loop | plan — redirect in `ngOnInit` subscribe (filter null); wizard calls `auth.me()` on success |
| P15 | FE | MAJOR | Non-admin guarded on `/workspace/setup` would get `/not-authorized`, not neutral panel | plan — non-admins never routed to setup; neutral "setup pending" is the shell's job |
| P16 | FE | MAJOR | Multipart upload + client pre-check + file-input a11y unspecified | plan/research D11 — `FormData` (no manual Content-Type), client size/type pre-check, `role="alert"` errors, labelled `accept` input |
| P17 | FE | MAJOR | SC-012 covers settings route only; wizard route untested | plan — `role.guard.spec` covers BOTH guarded routes per role |
| P18 | QA / SEC / FE | MINOR/NIT | Multipart CSRF assertion; ASCII-LDH domain; `$localize` for programmatic strings; form a11y; 5×4 matrix no-state-change; headless ImageIO | Folded into research D6/D7/D11, data-model validation, and plan test notes |

## Multi-Role Sub-Agent Review — TASKS stage (Constitution C6, 2026-06-14)

Reviewers: **QA/Coverage**, **Backend sequencing**, **Traceability/format**. Verdicts: QA APPROVE-WITH-CHANGES; Backend APPROVE-WITH-CHANGES; Traceability APPROVE-WITH-MINOR-FIXES. No BLOCKER/MAJOR invalidating the DAG; full file coverage, all 26 FRs and 13 SCs traced, all spec-/plan-review findings reflected, format-compliant (T001–T053). Fixes applied to `tasks.md`:

| # | Role | Severity | Finding | Resolution |
|---|------|----------|---------|------------|
| T1 | BE | MAJOR | T016 would not compile — `AuthController.login()` also builds `MemberSummary` | T016 now updates the `login()` (and any other) construction site |
| T2 | BE | MAJOR | T013 audit methods omitted Clock/IP discipline (would break MutableClock tests) | T013 — use injected `Clock`, null `sourceIpHash`/`targetMemberId` (mirror `roleChanged`) |
| T3 | QA | MAJOR | US1 AS-7 "both attempts audited" impossible — loser wrote no audit | T013 adds `setupConflict(...)`; T023 audits it on the DuplicateKey path; T022 asserts both |
| T4 | QA | MAJOR | US2 AS-5 (same-field concurrency) uncovered — only different-field tested | T027 adds a same-field racing case (last-writer-wins, consistent, both audited) |
| T5 | QA | MAJOR | T036 restart under-asserted (decrypt-to-original + branding-returned) | T036 spells out cold-converter decrypt == original AND branding still returned |
| T6 | BE/QA | MAJOR | Multipart `POST /logo` + CSRF footgun (token must be header) | Noted on T031/T035/T048 (header `X-XSRF-TOKEN`, not a form part) |
| T7 | QA | MINOR | Logo binary fixtures not provisioned (test unexecutable) | T031 provisions the 7 fixtures under `src/test/resources/workspace/` |
| T8 | QA/BE | MINOR | Read-only invariant (BE-1) not regression-tested | T022 asserts `workspaceConfig` count==0 after GET/me on a fresh workspace |
| T9 | QA | MINOR | Rotate/unset credential audits not asserted | T036 asserts an `email_config` audit row on rotate and unset |
| T10 | QA | MINOR | Secret scan didn't drive the GET/read (decrypt) flow | T037 adds a `GET /config` before the log assertion |
| T11 | QA | MINOR | members/sessions cleanup missing for seeding tests | Path-conventions note: `remove(...)` Member/Session in seeding classes |
| T12 | BE | MINOR | T011 under-specified the response record | T011 points at data-model §DTOs full field list |
| T13 | BE | MINOR | T009 didn't pin `MongoTemplate` method-param injection | T009 — method-param injection, mirror ChangeUnit003 |
| T14 | TR | MINOR | Default colour `#1F2937` not asserted; public read not adversarially checked | T031 asserts exact default + public body has ONLY `brandColor`+`logoUrl` |
| T15 | TR | MINOR | T030 cross-story shared-component boundary not flagged | T030 notes the shared scaffold + sequential same-file edits |
| T16 | TR | NIT | US6 AS-5 server-side 403 only implicit | T048 notes its unconfigured+non-admin arm satisfies it |

## Multi-Role Sub-Agent Review — IMPLEMENTATION stage (Constitution C6, 2026-06-15)

Reviewers (Loop 1): **Security/GDPR**, **Backend correctness**, **Front-End** — all APPROVE-WITH-CHANGES, no BLOCKERs. Reviewers (Loop 2, verification): **Backend/Security**, **Front-End** — both **APPROVE**. Full backend suite + frontend (12) green after fixes.

| # | Severity | Finding | Resolution |
|---|----------|---------|------------|
| I1 | MAJOR | Oversize logo upload → 500 (Spring default multipart 1 MB) not the contracted 400 | `application.yml` multipart cap raised to 2 MB (so the 1 MB `validateLogo` gate is authoritative for 1–2 MB) + `WorkspaceExceptionHandler` maps `MaxUploadSizeExceededException`/`MultipartException` → 400 `invalid_logo` |
| I2 | MAJOR | Email config used whole-doc `save()` → lost-update clobber of concurrent settings `$set` | Rewrote `setEmailConfig`/`unsetCredential` to targeted `$set`. Discovered the PII converter **IS** applied to `$set` values (cold-reload decrypt test proved it) → pass plaintext, converter encrypts. Unset uses `$set null` (NOT `$unset`, which CCE'd passing the unset-marker to the String converter) |
| I3 | MAJOR | `uploadLogo`/`deleteLogo` didn't `requireConfigured` → logo before setup silently lost `hasLogo` | Added `requireConfigured` guard in `BrandingService` |
| I4 | MAJOR | Wizard: `submitting` not reset if post-setup `me()`/nav fails; shell stale-member redirect risk | Wizard resets `submitting` in both `me()` branches; shell switched to a single `toSignal(member$)` subscription |
| I5 | MINOR | `resolvePublicLogo` `findAll()` loaded all logo blobs | `findOne(new Query().limit(1))` |
| I6 | MINOR | Settings file input couldn't re-select same file after error; `set/not set` hardcoded | `input.value=''` after capture; i18n `credSet`/`credNotSet` spans |
| I7 | NIT (open, non-blocking) | No automated test for the >2 MB multipart path (MockMvc can't enforce the servlet cap) | Handler verified by inspection; a real-servlet test is a follow-up |

## Notes

- Items marked incomplete require spec updates before `/speckit.plan`.
- **F03 implementation is complete and verified**: full backend test suite green (F01+F02+F03, incl. the new `com.cadence.workspace.*` suite — concurrency latches, raw-driver ciphertext, cold-converter reload, secret log scan, 5×4 RBAC matrix), frontend `ng test` 12/12 + `ng build` clean, F02 `RbacEndpointInventoryTest` still green with the new internal endpoints.
- Scope-boundary decisions made as documented Assumptions (single-workspace MVP; retention enforcement binds in F04; email-domain verification binds in F22) rather than [NEEDS CLARIFICATION] markers, per the speckit informed-guess guidance and the F02 precedent. The retention-enforcement boundary is flagged as the one stakeholder-reversible scope decision to confirm before planning.
