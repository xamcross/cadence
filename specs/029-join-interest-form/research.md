# Phase 0 Research: Join / Express-Interest Request Form (F70)

**Feature**: 029-join-interest-form | **Date**: 2026-06-23

All decisions below resolve the open questions implied by the spec and the multi-role spec review. The feature is overwhelmingly a **reuse** of existing seams; no new runtime dependency is introduced.

## R1 — Open-request de-duplication index (avoid the partial-filter `$in` and present-as-null footguns)

**Decision**: Add a dedicated denormalized field `openEmailHash` to the `interestRequests` document that mirrors the keyed `emailHash` **only while the request is open** (status NEW or REVIEWED) and is **unset** when the request reaches a terminal status (INVITED/DISMISSED) or is erased. Enforce one open request per email with a **partial-unique index** `{workspaceId, openEmailHash}` over `{openEmailHash: {$exists: true}}`. Annotate the field `@Field(write = Field.Write.NON_NULL)`.

**Rationale**:
- MongoDB `partialFilterExpression` support for `$in` across versions is inconsistent; keying the partial index on a single `{$exists:true}` field is robust and is exactly the **F23 `confirmTokenHash` precedent** (partial-unique over a present value).
- `@Field(write=NON_NULL)` avoids the **F01 "partial-unique matches present-but-null" footgun** (a written null would collide).
- `interestRequests` is a NEW collection, so there is no **F42 "two indexes with identical key pattern"** collision to worry about; nonetheless using a distinct `openEmailHash` field (separate from the always-present `emailHash` used for admin lookup/erasure) keeps the dedup key and the lookup key independent.
- The create path does `insert`; a concurrent/duplicate open submission collides on the unique index → `DuplicateKeyException` → re-resolve the existing open request and update it (coalesce). This is the **F22 `emailDispatches` insert-catch-DuplicateKey** idempotency precedent.

**Alternatives considered**: partial-unique `{workspaceId, emailHash}` over `status:{$in:[NEW,REVIEWED]}` — rejected (partial-filter `$in` portability risk). A read-then-write dedup — rejected (lost-update / write-skew under concurrency).

## R2 — Admin notification channel (value-free, member-recipient, coalesced)

**Decision**: On a new open request being created (NOT on a coalesced resubmit, NOT on the response path), best-effort call **`RecruiterNotificationService.notify(workspaceId, null, RecruiterNotificationType.INTEREST_REQUEST)`** — the real 3-arg signature with a **null `candidateId`**, which is an established pattern (`AtsSyncService` already calls `notify(workspaceId, null, ATS_SYNC_FAILED)`). `RecruiterNotificationType.INTEREST_REQUEST` is a new append-only enum value. This **persists a value-free in-app `RecruiterNotification` row** (the service sends NO email — verified) that the workspace's admins see; the row carries the workspace id + type only (no submitter name/email/organization/message; the interest-request id is non-PII and may be referenced). The **candidate consent/contact gate and `EmailDispatchService` outbox are NOT used** (recipients are administrators, not data subjects).

**Correction from review**: the earlier 2-arg `notify(workspaceId, type)` call did NOT exist and the "new `SmtpEmailSender` dispatcher branch + `OperationalEmailTemplates` constant" narrative was moot — `RecruiterNotificationService.notify` does not touch `EmailSender`. The MVP notification is the **in-app row** (the `ATS_SYNC_FAILED` precedent). If admins must additionally be **emailed**, that is a SEPARATE, explicitly-scoped path against `EmailSender.sendEmail`'s closed dispatcher (the F13/F32 closed-dispatcher lesson genuinely applies there) and is **out of scope for this version** unless the owner requests it.

**Coalescing comes for free**: because R1 coalesces duplicate submissions into a single open request (no new insert), a resubmit does not fire a second notification. No separate throttle window is needed; the de-dup IS the window. (SC-011 is satisfied structurally — assert one `RecruiterNotification` row per same-email burst.)

**Rationale**: `RecruiterNotificationType` is a plain enum, not tied to the `EmailMessageType` `@PostConstruct` completeness check (that is candidate-template only), so adding a value is safe. A null `candidateId` is tolerated by the row + repository (the `ATS_SYNC_FAILED` precedent proves it).

**Alternatives considered**: candidate `EmailDispatchService` outbox — rejected (consent-gated, erasure-aware, wrong for member recipients). A new standalone notifier — rejected (reuse `RecruiterNotificationService` with null candidateId).

## R3 — Workspace association for an anonymous submitter (the BLOCKER the spec resolved as FR-019)

**Decision**: Add a configuration property `cadence.interest.default-workspace-id` (a Spring `@ConfigurationProperties` value), defaulting to the deployment bootstrap workspace id (`cadence`, matching `AdminBootstrapRunner`'s `CADENCE_BOOTSTRAP_WORKSPACE_ID:cadence`). The public submit service resolves the owning `workspaceId` **exclusively from this server config**, NEVER from submitter input. Multi-workspace public routing is out of scope (FR-019).

**Rationale**: the login form requires the user to type a Workspace ID; the interest form must not (the submitter does not know it, and accepting it would be a cross-workspace injection/enumeration vector). The running system has no server-side "current workspace" concept for an unauthenticated request, so it must be configured. Sourcing from config keeps RBAC scoping (FR-012) and the admin queue coherent: all anonymous requests land in the configured workspace and are visible only to that workspace's admins.

**Alternatives considered**: read the bootstrap env var at request time — rejected (it is only injected into the bootstrap runner, not general config). Hardcode `"cadence"` — rejected (not configurable; brittle for a renamed workspace).

## R4 — SEO posture for the public `/request-access` page

**Decision (revised after review)**: The page is **`noindex`** (deny-by-default; `data: { seo: PRIVATE }`). The F60 `route-seo-inventory.spec` "exactly one indexable route" assertion is **unchanged**; `robots.txt`, `sitemap.xml`, the `_headers` X-Robots-Tag set, and the `ci.yml` robots-`Allow` allow-set (`ci.yml:526`) are **NOT touched**. It is a POST form, so no token/PII would appear in any artifact regardless.

**Rationale**: a POST-only form has negligible organic-search value (a crawler indexes only the empty shell; the action is behind submit). The marketing on-ramp value is served by the public home `/` linking to it, not by the form being a search landing page. Keeping it `noindex` preserves the deny-by-default guard's signal intact (the guard's whole purpose is to fail the build if a second route is marked indexable) and removes a class of SEO-leak surface from review scope. F61's indexable `/resources` is a statically-generated page, not a clean SPA-route precedent, so "the app already has two indexable things" does not justify relaxing the SPA-route guard.

**Alternatives considered**: make it indexable — rejected (weakens the deny-by-default guard; requires coordinated edits to `route-seo-inventory.spec`, `robots.txt`, `sitemap.xml`, and `ci.yml:526`'s hardcoded allow-set, for near-zero SEO gain). If the owner later wants it indexed, that is the full edit set required.

## R5 — Retention & purge of a new PII category

**Decision**: A new scheduled task `InterestRetentionScheduler` (`@Scheduled`, own `SchedulerCheckpointService` checkpoint `"interest-retention-scan"`, `@PostConstruct registerReplayAction` — the F00.2 / `RetentionScanTask` shape) iterates `workspaceConfigRepository.findAll()`, computes a per-workspace cutoff = `now(clock) - retentionPeriod`, and **hard-deletes** `interestRequests` with `submittedAt < cutoff`. `WorkspaceConfig.retentionPeriodDays` is a **primitive `int`** (default `0`, never null), so the fallback trigger MUST be `retentionPeriodDays <= 0 ? fallbackDays : retentionPeriodDays` — NOT a null check (`0` would otherwise mean "delete everything immediately"). The scan MUST also mirror `RetentionScanTask.runScan`, which only processes workspaces where `cfg.isConfigured()` is true (skip unconfigured workspaces). `fallbackDays` = a documented **180** (`cadence.interest.retention-fallback-days`). Backed by index `{workspaceId, submittedAt}`. Time is via the injected `java.time.Clock` (deterministic under `MutableClock` in tests).

**Rationale**: §IV mandates `@Scheduled` + Mongo job state (no broker). Interest data is lead data, not an audited candidate subject, so a **hard delete** is appropriate (no tombstone needed); if any summary audit is kept it MUST be PII-free. Reusing the workspace retention value keeps one knob; the 180-day fallback makes SC-008 measurable.

**Alternatives considered**: reuse the candidate `RetentionService` scan — rejected (it is candidate-collection-specific and keyed on `lastContactAt`). Soft-delete/tombstone — rejected (unnecessary for non-audited lead data; a hard delete is simpler and stronger for erasure).

## R6 — Rate limiting / flood ceiling (token-less public surface)

**Decision**: Three layers. The **durable guard is layer 2**; the per-source layer is best-effort because of the proxy reality below.
1. **Per-source (best-effort)**: hash the **real client IP** via `TokenHasher.hashIp(...)`. Two codebase facts force care: (a) `CandidateRateLimiter` is a **fixed one-minute window** (`epochSecond/60`) with a single cap from `SchedulingProperties.rateLimitPerMinute` shared with candidate scheduling — it CANNOT express a 10-minute window or an independent cap; (b) the app runs `forward-headers-strategy: framework`, which does NOT rewrite `getRemoteAddr()` from `X-Forwarded-For`, so behind the Cloudflare proxy every caller collapses to the edge IP (an `getRemoteAddr()`-fed cap is effectively global — ineffective AND a self-DoS). Therefore: use a **dedicated interest limiter** with its own `cadence.interest.*` cap/window (default 5 per 10-minute window), and resolve the real client IP from **`CF-Connecting-IP`** (or the validated leftmost `X-Forwarded-For`) before hashing. If the real IP cannot be established, this layer is explicitly best-effort.
2. **Per-workspace ceiling (durable, the real guard)**: before insert, a **database count** `count({workspaceId, submittedAt > now - window})` gated against a cap (default **100/workspace/hour**, `cadence.interest.*`), backed by index #3 `{workspaceId, submittedAt}`. Durable on single-instance topology (survives restart; cannot be bypassed by rotating IPs/emails — unlike an in-memory counter). The count is on the **throttle path, not the success path**, so it does not affect the no-oracle response (R8). Beyond the cap → the same neutral throttling response.
3. **Bot heuristic**: a hidden honeypot field that must stay empty + a minimum form-fill time (`cadence.interest.min-fill-millis`); failing either → silent neutral acceptance (no row written), never a CAPTCHA for ordinary users.

**Rationale**: unlike candidate pages (256-bit token + DB-claim primary guard) this form has no token, and the per-IP layer is proxy-weakened, so the **DB per-workspace ceiling + per-field bounds (FR-002)** are the real bound on store growth. All knobs live in `cadence.interest.*`.

**Alternatives considered**: reuse `CandidateRateLimiter` as-is — rejected (wrong window, shared cap, proxy-collapsed IP). CAPTCHA for ordinary use — rejected (candidate-page philosophy); a future escalation above the ceiling. Relying on the IP limiter alone — rejected (botnet- and proxy-bypassable).

## R7 — C1 governance (feature outside Constitution §11 MVP scope)

**Decision**: Proceed under the **F60/F61 precedent** — both SEO features shipped outside the §11 enumerated MVP list as supporting capabilities with owner acceptance. This plan records C1 as **FLAGGED — requires project-owner ratification** and proceeds on that basis. If the owner declines, the feature is deferred per the C1 fail action ("Defer or amend constitution"). No code is written until the owner confirms.

**Rationale**: §I is strict ("only §11 capabilities"), but the project has an established practice of accepting small, well-scoped supporting capabilities (SEO) outside §11. A join-interest form directly serves the MVP's onboarding funnel. This is a governance decision for the owner, surfaced explicitly rather than silently passed.

## R8 — No-oracle response: structural constant-time, not a wall-clock assertion

**Decision**: The public submit endpoint returns a **byte-identical** `202 {"status":"received"}` (same body, status, and headers) across {active member, pending invitation, existing open request, unknown email}. Achieve indistinguishability **structurally by doing LESS**: the submit path performs **NO member- or invitation-existence check** (there is no email-keyed invitation finder anyway — `InvitationRepository` exposes only `findByTokenHash` — and none is needed), so the member/pending-invite cases are indistinguishable from unknown **by construction**. The only internal branch is the dedup **insert-attempt vs. coalesce-update**, both returning the identical response; the `RecruiterNotificationService.notify` side effect is **deferred to after the response decision** (best-effort, off the response path, new-insert only). The 4-case equivalence is verified by an **automated MockMvc contract test** asserting identical body+status+headers, plus a structural assertion (ArgumentCaptor/spy — the F12 multiplicity precedent) that the dedup insert-attempt runs on every branch and notify fires only on a genuine new insert. **Timing is documented as a structural guarantee, NOT asserted by a wall-clock test** (the project's no-flaky-timing QA rule; no existing no-oracle handler — `FeedbackExceptionHandler` etc. — asserts timing).

**Rationale**: the existing app ships byte-identical no-oracle responses (`FeedbackExceptionHandler`, `SlaNudgeExceptionHandler`) but never asserts constant wall-clock time, because a DB-op-count/branch difference makes literal timing-invariance untestable without flakiness. Making the code path identical + moving the variable-cost side effect (notify) off the response path is the honest, testable form of "no timing oracle" (the F23/F31 honest-bound precedent). The earlier spec/contract wording ("not distinguishable by timing, verified by automated test") over-promised and is corrected in spec FR-005/SC-005.

**Alternatives considered**: a literal constant-time/`nanoTime` test — rejected (flaky; banned by the QA rule). Returning 202 before any DB work — rejected (the dedup/ceiling checks must run first; instead they run identically on all branches and only the notify is deferred).

## Cross-cutting reuse inventory (no new dependency — Dependency Policy / C4 PASS)

| Concern | Reused seam |
|---|---|
| PII at rest | inside `MongoPiiConfig`: `registrar.registerConverter(InterestRequest.class, "name"/"email"/"organization"/"message", converter)` (the per-field pattern; `emailHash`/`openEmailHash` NOT registered) |
| Email-hash lookup/dedup | `PiiCrypto.emailHash(email)` (keyed HMAC; NOT registered for encryption) |
| Invitation conversion | `InvitationService.create(workspaceId, actorMemberId, email, role, ip)` (Admin-only; throws `AlreadyMemberException`) |
| Public security chain | `SecurityConfig` `@Order(2)` `securityMatcher("/api/public/**", ...)` permitAll/STATELESS/CSRF-exempt; `/api/public/` already allow-listed in `RbacEndpointInventoryTest` |
| No-oracle responses | scoped `@Order(HIGHEST_PRECEDENCE) @RestControllerAdvice` (the `FeedbackExceptionHandler` precedent) |
| Rate limiting | `TokenHasher.hashIp` keying + a **dedicated** interest limiter (NOT the per-minute `CandidateRateLimiter`) + a DB per-workspace count gate (R6) |
| CSV-injection safety | `CsvInjectionEscaper` (export boundary; store verbatim) |
| Scheduled purge | `@Scheduled` + `SchedulerCheckpointService` (the `RetentionScanTask` shape) |
| Admin notification | `RecruiterNotificationService.notify(workspaceId, null, INTEREST_REQUEST)` — 3-arg, null candidateId (the `ATS_SYNC_FAILED` precedent); persists a value-free in-app row, sends no email (R2) |
| Erasure | `$set "[ERASED]"` on encrypted fields + `$unset emailHash`/`openEmailHash` (the `CandidateErasureService.wipe` CAS precedent) |
| Guarded status CAS | `findAndModify({_id, status:<from>} -> <to>)` (the `ErasureRequestService.transition` precedent) |
| Mongock ordering | `ChangeUnit023_InterestRequestIndexes` (order **"023"** off the highest applied **"022"** — confirm at implementation) |

**Enum→`kv` footgun carried forward**: the new `InterestRequestStatus` / `RecruiterNotificationType.INTEREST_REQUEST` are NEVER passed to `StructuredArguments.kv(...)`; log `.name()`/ids only (the F01.1 logstash Jackson-3 crash).
