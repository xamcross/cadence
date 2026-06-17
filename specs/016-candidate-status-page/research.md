# Phase 0 Research: Candidate Status Page (F30)

All decisions verified against the real F01/F03/F04/F13/F21 source (Explore map, 2026-06-17). No `NEEDS CLARIFICATION` remained from the spec; the two product judgments the spec flagged (FR-017 stale-date framing; free-text at-rest posture) are resolved here.

## D1 — Where the candidate-visible status lives: additive fields on `candidates` (not a new collection)

**Decision**: Store `statusStage`, `statusNextStep`, `statusExpectedDate`, `statusOutcome`, `statusPublishedAt`, `statusPublishedByMemberId`, `statusToken`, `statusTokenHash` as additive fields on the existing `Candidate` document.

**Rationale**: The `Candidate` Javadoc already states "F04 owns the GDPR-critical subset and later features (F13/F40/F42) extend it." Status is strictly 1:1 with a candidate, needs no independent lifecycle, and must be wiped on erasure — putting it on `Candidate` means the existing `CandidateErasureService.wipe` clears it in the same update (no second collection to reconcile), keeps C2 clean (no new collection), and avoids a join on the candidate read.

**Alternatives considered**: A `candidateStatus` collection (rejected — extra collection, extra erasure-reconciliation surface, a join, for a strict 1:1 with no independent lifecycle). The minor cost is two more converter-decrypt fields per `Candidate` read; acceptable.

## D2 — Status access token: dual-stored (reversible-encrypted `statusToken` + deterministic `statusTokenHash`)

**Decision**: Mint a 256-bit token via `SecureTokens.newToken()`. Persist **two** representations: (a) `statusTokenHash = TokenHasher.hashToken(raw)` — deterministic HMAC, **partial-unique indexed** — used to resolve an inbound request and to compare; (b) `statusToken` — the raw token stored **reversibly encrypted** via the `PiiStringConverter` (AES-256-GCM at rest) — used to re-derive the `{{status_link}}` URL at any later email-render time.

**Rationale**: The status link is a **long-lived, repeatedly-delivered** bearer credential — `MergeToken.STATUS_LINK` already exists and is referenced by the `HOLD_UPDATE`/`REJECTION`/`SLA_HOLDING` built-in templates (sent later, by F31 and other paths), and the recruiter UI must be able to display/copy the current link at any time. The link therefore must be **re-derivable server-side after mint**, which a hash-only store (the F23 confirm-token model) cannot do. Ciphertext is non-deterministic (random IV) and so not queryable, so a deterministic hash is independently required for the indexed inbound lookup. This is exactly the F01.1 OAuth-refresh-token / PKCE-verifier precedent — a secret that must be re-presented is stored reversibly encrypted, never in plaintext, never logged.

**Alternatives considered**: (1) Hash-only, raw delivered once at mint (the F23 model) — rejected: cannot re-embed the link in `HOLD_UPDATE`/`SLA_HOLDING`/`REJECTION` emails or the recruiter "copy link" action; (2) Rotate-to-reveal (mint a fresh token whenever the recruiter wants the link) — rejected: destructive (invalidates the candidate's existing bookmarked link on every view), terrible UX; (3) Deterministic token derived from candidate id — rejected: violates FR-020 (must be unguessable/random).

**Security note for review**: reversible storage of a bearer token is the OAuth-refresh-token posture, justified by the re-presentation requirement; the compensating controls are the partial-unique hash index (no enumeration), rotation/revocation (D8), transport controls (D10), rate-limit (D6), and the indistinguishable not-found (D6).

## D3 — Status publish: one atomic `findAndModify`/`$set`, last-valid-write-wins

**Decision**: Publish via a single `mongoTemplate.updateFirst(query{_id, workspaceId, erasureState:ACTIVE}, update.$set(statusStage, statusNextStep, statusExpectedDate, statusOutcome, statusPublishedAt, statusPublishedByMemberId), Candidate.class)`. No `@Version`. `matchedCount==0` → the candidate is missing or erased → scoped 404 (recruiter) — never publishes onto an erased subject.

**Rationale**: A single atomic `$set` of all status fields together is the FR-016 "last valid write wins, no partial state" guarantee with the least machinery. Spring Data applies the registered `PiiStringConverter` to `$set` values (the F03 `WorkspaceConfigService.updateFirst($set emailProviderCredential)` precedent — confirmed by the cold-converter-reload test), so the encrypted free-text fields encrypt correctly via `$set` (do NOT pre-`crypto.encrypt`). Guarding on `erasureState:ACTIVE` makes "publish onto an erased candidate" structurally impossible.

**Alternatives considered**: whole-document `save()` with `@Version` (the F21 template model) — rejected: a lost-update window on concurrent edits and a heavier load→mutate→save; the targeted `$set` is simpler and atomic.

## D4 — Publish validation: shape depends on outcome (FR-011/FR-012)

**Decision**: Validate BEFORE the `$set`:
- `statusOutcome == IN_PROGRESS` (the default published state): `statusStage` non-blank (trimmed), `statusNextStep` non-blank (trimmed), `statusExpectedDate` non-null — all three required (the dateless/contentless "we'll be in touch" ban).
- `statusOutcome == COMPLETE_OFFER | COMPLETE_REJECTED` (terminal): `statusOutcome` + a non-blank `statusNextStep` (the honest closing message) required; `statusExpectedDate` optional (a concluded process has no "next" date).
- Validation messages are **value-free** (field + rule, never echo the submitted text — the F12 precedent), → 400 `invalid_status`.

**Rationale**: FR-009/FR-010 mandate "no dateless/contentless holding message" for an in-progress status; a terminal outcome is a legitimately different shape (offer/rejection has no future date). Pinning the two shapes prevents both the ghosting artifact and a spurious "expected date required" on a rejection.

## D5 — `displayState` precedence computed server-side (FR-008, FR-017)

**Decision**: The candidate view resolves exactly one `displayState` ∈ `{TERMINAL, PAST_DATE, PUBLISHED, UNDER_REVIEW}`, in that precedence:
1. `TERMINAL` — `statusOutcome` is terminal → render the honest concluded message.
2. `PAST_DATE` — in-progress with `statusExpectedDate` strictly before "today" in the **workspace** time zone → render the "we're past the expected date, still your stage is X" framing (FR-017), preserving `statusStage`.
3. `PUBLISHED` — in-progress, `statusExpectedDate` today-or-future → render stage + next step + date normally.
4. `UNDER_REVIEW` — no status ever published (`statusPublishedAt == null`) → render the neutral "your application is being reviewed" default (FR-006).

The candidate payload carries the resolved `displayState` + only the fields that state needs (escaped). "Past" is computed against the workspace zone (`WorkspaceConfig` zone, the F03 source) using `LocalDate` comparison, so it is stable regardless of the candidate's device clock; the candidate page still **renders** the expected date in the candidate's local presentation (FR-004). `CandidateStatusService` injects `java.time.Clock` (the F01 `MutableClock`/`AuthTestConfig` pattern) — `today = LocalDate.ofInstant(Instant.now(clock), workspaceZone)`, NEVER `LocalDate.now()` — so SC-013/SC-016 are deterministic under a controlled clock (the F13/F23 lesson).

**Rationale**: Computing precedence on the server keeps the candidate payload minimal (text + one enum, no PII beyond the escaped status text), removes client-side branching divergence (SC-016), and pins the FR-017 comparison instant/zone the QA review flagged as untestable.

**Alternatives considered**: client-side state resolution (rejected — duplicated logic, drift risk, larger payload); comparing past-date in the candidate's zone (rejected — device-clock-dependent, non-deterministic test, and the recruiter set the date in the workspace zone).

## D6 — Candidate view endpoint + indistinguishable not-found (FR-031, SC-007, FR-022/FR-030)

**Decision**: `GET /api/candidate/status/{token}` on the existing `@Order(2)` permitAll/STATELESS chain (token IS the auth; allow-listed in `RbacEndpointInventoryTest`). Flow: `rateLimit(ip)` via `CandidateRateLimiter.tryAcquire(http.getRemoteAddr())` (429 on breach) → `hashToken(token)` → `findByStatusTokenHash` → if empty **or** candidate `erasureState != ACTIVE` → throw a single `StatusNotFoundException` → **404 with a byte-identical body** → else compute `displayState` and return `Cache-Control: no-store`. The lookup is by the indexed deterministic hash (the F13/F23 resolution precedent); timing is dominated by the indexed read, and the same exception path serves unknown/malformed/erased so status code + body are identical (no oracle).

**Rationale**: Mirrors the F13/F23 candidate-token contract exactly. Folding "erased" into the same 404 closes the FR-018/FR-031 oracle. Per-IP rate-limit + no-store are the F13 candidate-controller defaults.

**FR-027 constant-time, satisfied structurally**: the raw token is never compared in application code — it is HMAC-hashed and resolved by the indexed `statusTokenHash` equality (the F13/F23 model), so there is no secret-dependent byte comparison to leak timing; the DB compares a hash, not the secret. Do NOT add a `MessageDigest.isEqual` (there is nothing to compare).

**Indistinguishable response needs a controller-bound exception handler (load-bearing)**: the existing `SchedulingExceptionHandler` is `@RestControllerAdvice(assignableTypes={SchedulingController, CandidateSchedulingController, CandidateBookingController})` — it is **not** inherited by the new F30 controllers. Without a matching advice, `StatusNotFoundException`/`InvalidStatusPublish` fall through to the default `BasicErrorController` `/error` body (timestamp/path varies by case → reintroduces the oracle, or a 500). F30 MUST add a dedicated `@RestControllerAdvice` (a new `CandidateStatusExceptionHandler`, or widen `assignableTypes` to include the F30 controllers) mapping: `StatusNotFoundException` → byte-identical `{"error":"not_found"}` 404; erasure-submit → identical `202 {"status":"received"}` across {valid, unknown, malformed, erased}; `InvalidStatusPublish` → 400 `invalid_status`; rate-limit → 429. This handler is the piece that actually delivers SC-007/SC-010 — it is an explicit task.

## D7 — Candidate erasure submit: reuse `ErasureRequestService.requestErasure`, add idempotency (FR-019..023, SC-008)

**Decision**: `POST /api/candidate/status/{token}/erasure-request` (affirmative POST; a `GET` → 405 so a prefetch/scanner can't trigger it) on the `@Order(2)` chain, rate-limited. Flow: resolve token → candidate (active) → call `ErasureRequestService.requestErasure(workspaceId, candidateId, ErasureReasonCode.CANDIDATE_REQUEST)` which is hardened to be **idempotent** (no second `PENDING` for the same candidate). The endpoint **always returns the same `202` acknowledgement** regardless of {newly created, already-open, unknown token, erased} — no existence oracle (FR-023/SC-010); a request is only actually recorded when the token resolves to an active candidate.

**Idempotency mechanism**: a unique partial index `{workspaceId, candidateId}` over `status:PENDING` on `erasureRequests` (ChangeUnit015); `requestErasure` does an insert and catches `DuplicateKeyException` → returns the existing open request (the F22 outbox-key precedent). This hardens the operator path too (FR-022 — no Admin-queue flooding). The recorded `ErasureRequest` is already **id + enum-reason only, never free text** (confirmed in source) so FR-021 holds by construction.

**Rationale**: `ErasureRequestService.requestErasure` is already documented as "the F30 forward contract." The only gap is idempotency (the current impl saves unconditionally) — F30 closes it with a unique index, which also benefits F04. Routing to the Admin `confirm` path (existing) keeps "never immediate" (FR-019) intact.

**`ErasureReasonCode` value**: reuse the **existing** `ErasureReasonCode.CANDIDATE_REQUEST` (confirmed present in source) — no enum change. (Note: `CandidateAuditOutcome.CANDIDATE_REQUEST` is a distinct same-named enum already passed to `wipe` by `ErasureRequestService.confirm` — don't conflate the two.) FR-022 is "no second **PENDING**" — a fresh PENDING after a prior RESOLVED is permitted by design; the rate-limit bounds churn.

## D8 — Token rotation / revocation (FR-029, SC-011)

**Decision**: `POST /api/internal/candidates/{id}/status/rotate-link` (`@PreAuthorize("hasAnyRole('ADMIN','RECRUITER')")`, workspace-scoped). Mints a fresh `SecureTokens.newToken()`, atomic `$set` of the new `statusToken` (encrypted) + `statusTokenHash`, audits `STATUS_LINK_ROTATED`, returns the new link. The old hash no longer resolves → the old link returns the indistinguishable 404 (SC-011).

**Rationale**: The only revocation primitive for a leaked-but-not-erased long-lived link. Reuses the same mint/hash/encrypt path as provisioning.

## D9 — Link delivery / merge into `{{status_link}}` (Assumptions, §II leg)

**Decision**: `CandidateStatusService.statusLinkFor(candidate)` lazily ensures a token is provisioned (mint on first need, atomic `$set` if absent) and returns `{spaBaseUrl}{spaStatusBasePath}?token={decrypt(statusToken)}`. The F21 merge-context builder supplies this string for `MergeToken.STATUS_LINK`. F30 adds `STATUS_LINK` to the `CONFIRMATION` built-in body + `MergeTokenCatalogue` permission so the candidate receives a working status link in the post-booking email they already get (the demonstrable end-to-end leg without depending on F31). The `MergeRenderer` already renders a URL-typed token as a scheme-checked `href==text` anchor (F21), so no renderer change.

**Rationale**: Makes F30 demonstrable browser-to-DB on its own (F23 set the precedent of editing a built-in body — `REMINDER_24H`). The `STATUS_LINK` token, catalogue mechanism, and anchor-rendering all already exist; F30 only wires the value source + one template permission. F31's later `HOLD_UPDATE`/`SLA_HOLDING` reuse `statusLinkFor` unchanged.

**ATOMIC change (must not be split)**: `BuiltInTemplateCompletenessTest` asserts the catalogue permits every token used by BOTH the built-in default AND every `(type,tone)` **tone preset** (presets are derived from `builtins.forType(type)` in `TonePresetCatalogue.build()`). So adding `{{status_link}}` to the `CONFIRMATION` body propagates into all 3 CONFIRMATION presets — the `@PostConstruct` completeness check crashes startup and the test reds unless the `MergeTokenCatalogue` CONFIRMATION permission is changed in the **same** task. tasks.md treats "edit CONFIRMATION built-in body" + "permit STATUS_LINK for CONFIRMATION" as one atomic change.

**Decrypted-link leak path (D15 cross-ref)**: the only place the raw token materializes is `statusLinkFor` → `decrypt(statusToken)` → the URL fed into the F21 merge context. The `MergeRenderer` renders it as a scheme-checked `href==text` anchor (safe). But the decrypted link / merge-context map MUST never reach a logger, a `CandidateAuditService.append`, or `DeadLetterService.recordFailure` (which sanitizes emails, not URL-tokens). The `SENTINELF30*` scan adds a **token-in-link** sentinel driven through the publish → email-render → dispatch path, asserting absence in logs + dead-letter.

## D10 — Long-lived-token transport controls (FR-032, SC-012)

**Decision**: API responses on both candidate endpoints set `Cache-Control: no-store`. The served SPA page inherits the existing `frontend/src/_headers` (`Referrer-Policy: no-referrer`, `X-Content-Type-Options: nosniff`, the fully-specified CSP) + the `index.html` `<meta name="referrer" content="no-referrer">` — already shipped for F14, so the token never leaks via `Referer`, cache, or bfcache. The status route is added to the same SPA, so it is covered by construction; a Jasmine test asserts the token is held in a memory-only field (never `localStorage`/`sessionStorage`, never `console`) and re-resolved on `ngOnInit` (bfcache-safe), the F14 pattern.

**Rationale**: The security review's top blocker — amplified by the lifecycle-long token. The controls already exist for the F13/F14/F20/F23 candidate pages; F30's page rides them.

## D11 — Frontend status page (§IX, FR-001..007, SC-001/002/003)

**Decision**: New standalone `candidate-status.component` under `features/status/`, public lazy route `/status?token=` in `app.routes.ts`. No login. Token read from the URL query, held in a memory-only field (F14). Renders the server `displayState` (one block), branding via `GET /api/public/workspace/branding` + `/logo` (F03), the expected date in the candidate's local presentation, a contact route (from branding/workspace config — never candidate PII), and a "Request data deletion" affirmative action → ack. Recruiter free-text rendered via Angular interpolation only (auto-escaped — FR-009), never `[innerHTML]`. All strings `$localize`. Per-state axe-core 0-violations (the F14 `axe.ts` harness, body-attached), 44 px targets, long/RTL `overflow-wrap:anywhere` overflow test. `lighthouserc.json` + `serve-with-stub.mjs` gain `/status?token=lighthouse-demo` (open + terminal + under-review states) so the blocking gate measures the real route (the F14 lesson — not the vacuous invalid state).

**Rationale**: F30 owns the blocking accessibility/performance gate on its new candidate surface (the F14/F20/F23 precedent — no successor polish feature). Reuses the entire F14 harness.

## D12 — RBAC for recruiter writes (FR-010/FR-014)

**Decision**: Publish + rotate + recruiter-read are `@PreAuthorize("hasAnyRole('ADMIN','RECRUITER')")` (HM **not** granted status write — spec FR-010, matching backlog US-F30-2 which names only Recruiter; F02 gives HM status *view* of own candidates, not authoring). Resolution is workspace-scoped (`findByWorkspaceIdAndId` → `ScopedNotFoundException` → 404, oracle-free — the F02 precedent). The new internal endpoints are declared with method security so `RbacEndpointInventoryTest` stays green.

## D13 — Mongock `ChangeUnit015` (order "015")

**Decision**: `ChangeUnit015_CandidateStatusIndexes` (order **"015"** off the highest applied **"014"**): (a) partial-unique `{statusTokenHash:1}` on `candidates` with `partialFilterExpression {statusTokenHash:{$exists:true}}` (the F23 `confirmTokenHash` precedent — partial, NOT sparse, to dodge the F01 present-as-null collision); (b) unique partial `{workspaceId:1, candidateId:1}` over `partialFilterExpression {status:"PENDING"}` on `erasureRequests` (the D7 idempotency guard). Native `createIndex` + `IndexOptions`; targeted `dropIndex` rollback (never `dropIndexes()`).

**Rationale**: Standard Cadence migration discipline. Both new fields are `@Field(write=NON_NULL)` so a candidate with no token/no pending-request is omitted from the partial index (no null collision).

## D14 — Audit (FR-013/FR-015, FR-034)

**Decision**: `CandidateAuditService.append(ws, candidateId, STATUS_PUBLISHED, RECORDED, actorMemberId)` on every publish; `STATUS_LINK_ROTATED` on rotation. The candidate **view** is NOT audited per-request (would flood + is a read); token issuance/rotation are audited by candidate id only (FR-034). Two new append-only `CandidateEventType` values; reuse `CandidateAuditOutcome.RECORDED`.

## D15 — PII / logging discipline (FR-033, SC-006)

**Decision**: `statusStage`/`statusNextStep` encrypted at rest (D1/D2) + escaped on render (D11) + **never logged** (only `kv("candidateId"/"workspaceId", id)` Strings; `.name()` for enums — never the F01.1 logstash enum-`kv` crash). `statusToken`/`statusTokenHash` never logged. `Candidate.toString()` extended to omit all new PII/token fields. `ci.yml` PII scan extended with `SENTINELF30STAGE_*`/`SENTINELF30NEXT_*` + a status-token sentinel; a failing-render PII test drives sentinels through view/publish/erasure paths and asserts absence across logs + the raw doc (the F21/F23 precedent).
