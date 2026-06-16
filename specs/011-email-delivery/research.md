# Research: Email Delivery Channel (F22)

Phase 0 decisions. Each resolves a `plan.md` flag carried from the spec's multi-role review.

## D1 — Transport: Spring Mail (`JavaMailSender`/SMTP), no provider SDK

**Decision**: Send via `spring-boot-starter-mail`'s `JavaMailSender` over SMTP. Wrap the actual transmission behind a thin internal `MailTransport` SPI; `EmailSender` (the constitution-named integration interface) stays the swappable boundary.

**Rationale**: Constitution §III names "Spring Mail + provider SDK (e.g. SendGrid, SES)" as the email-delivery technology; `spring-boot-starter-mail` is a Spring Boot starter (Dependency Policy explicitly permits starters with a one-line justification) and is C4-clean. SendGrid/SES/Mailgun all expose SMTP relay endpoints (host + API key as the SMTP password), so SMTP covers every candidate provider with **zero** provider SDK — consistent with how F10/F11 used raw `RestClient` instead of the Google/Graph SDKs. Provider swap = replace the `MailTransport`/`EmailSender` bean (FR-003/SC-007).

**Alternatives considered**: (a) Provider HTTP API + SDK (SendGrid Java SDK) — rejected: a new non-starter dependency, broader surface, against the F10/F11 no-SDK precedent. (b) Provider HTTP API via `RestClient` (no SDK) — viable but SMTP via the §III-named Spring Mail is simpler and directly satisfies the constitution; the only capability SMTP lacks is async bounce feedback, which D4 solves with the provider's event webhook regardless of send transport.

**Dependency record (Dependency Policy)**: `org.springframework.boot:spring-boot-starter-mail` — *provides `JavaMailSender` for transactional email transmission (constitution §III Spring Mail); no provider SDK added; transport wrapped behind `EmailSender`.*

**Test-coverage honest bound**: tests exercise the dispatch→transport path via `RecordingMailTransport` (zero new dep, F10/F11 stub precedent). The `SmtpMailTransport` → real-SMTP wire is **manual-verification-only** (the quickstart MailHog/Mailpit step), NOT covered in CI — exactly as F10/F11 never hit real Google/Graph. SC-001's "within 60 s" is therefore an inline-latency assertion against the recording sink, not a real-provider SLA.

**Bean cutover**: `SmtpEmailSender` becomes the sole `@Primary EmailSender`; **`@Primary` MUST be removed from `NoOpEmailSender`** (or it is demoted to a non-primary/`@Profile`-scoped test bean) or Spring fails startup with `NoUniqueBeanDefinitionException`. All existing injectors (`DeadLetterService`, `InvitationService`, `PasswordResetService`) inject the `EmailSender` interface, so no call-site edits.

## D2 — Transport config: per-workspace from F03, with an app-level default

**Decision**: Build/select a `JavaMailSender` from the workspace's F03 `WorkspaceConfig` (sending domain → `From`; decrypted `emailProviderCredential` → SMTP password; SMTP host/port/username from app config) — bound from **Fly secrets named in `UPPER_SNAKE_CASE`** (Fly secret names map to env vars; dotted/lowercase property paths are NOT valid secret names): `CADENCE_EMAIL_SMTP_HOST`, `CADENCE_EMAIL_SMTP_PORT`, `CADENCE_EMAIL_SMTP_USERNAME`, and the app-level default password `CADENCE_EMAIL_SMTP_PASSWORD`, surfaced into `application.yml` as `cadence.email.smtp.*: ${CADENCE_EMAIL_SMTP_*}`. Cache one sender per (workspace, credential-version). **Member/operational mail (invites/resets/system-alert) uses the app-level default sender** (`CADENCE_EMAIL_SMTP_PASSWORD`) since those have no workspace F03 candidate credential — this default MUST be set or the F01 member-email path breaks on first deploy. A workspace candidate send falls back to the same default when the workspace has no F03 email config.

**Rationale**: US-F03-4 puts per-workspace sending domain + provider API key in workspace settings; honouring it keeps multi-workspace sending correct. The F03 secret stays write-only/encrypted (read only at send, never logged). Single-instance friendly (an in-memory cache, no broker).

**Alternatives**: single global SMTP for all workspaces — simpler but ignores F03's per-workspace domain/key and would send candidate mail from the wrong domain. Rejected.

## D3 — `EmailSender` widening + member vs candidate paths

**Decision**: Keep the existing `EmailSender` methods (`sendEmail(toInternalId, templateId, mergeFields)`, `sendSystemAlert(taskName, summary)`) working — `SmtpEmailSender` (new `@Primary`, replacing `NoOpEmailSender`) resolves the **member** address (via `MemberService`, decrypt) and renders simple **operational** templates (member invitation, password-reset, system alert) then transmits. Add one method `send(OutboundEmail)` for pre-rendered messages. Candidate sends do **not** use the legacy 3-arg method: they go through the new `EmailDispatchService`, which resolves the candidate, evaluates the consent gate, renders via F21, and calls `EmailSender.send(OutboundEmail)`.

**Rationale**: F01's `InvitationService`/`PasswordResetService` and F00.2's `DeadLetterService` already call the legacy methods; preserving them means F22 turns those into real emails with **no F01 edits** (lower risk, immediate §II value). Member/operational mail is correctly **not** consent-gated (members aren't candidates). The consent gate, outbox, and idempotency are candidate-path concerns, isolated in `EmailDispatchService`. "Reuse the interface" = same abstraction, widened by one method — not the same signature.

**Operational templates**: small built-in constants (`OperationalEmailTemplates`: `member-invitation`, `password-reset`, `system-alert`) — distinct from F21's candidate-facing `EmailMessageType` library. Plain, link-bearing, no candidate PII.

## D4 — Bounce/delivery feedback: authenticated inbound provider webhook

**Decision**: Async hard/soft bounces and delivery confirmations arrive via a public `POST /api/webhooks/email/events` that the provider POSTs to (SendGrid Event Webhook / SES-via-SNS / Mailgun all support this even when sending over SMTP). Plus synchronous SMTP rejections at send time (5xx at submission) are captured immediately as failures. So bounce detection does **not** depend on the send transport.

**Webhook authentication (FR-016/SC-008)**: verify a provider signature/shared secret on every request **before** any state change; resolve each event to an existing `emailDispatches` row by its opaque `providerMessageRef`, and confirm the row's `workspaceId` — an unauthenticated event → `401` (no state change); an authenticated-but-unmatched/cross-workspace event → `200`-ack-and-ignore (no state change, no existence oracle). The verification secret is an **app-level env Fly secret `CADENCE_EMAIL_WEBHOOK_SECRET`** (process env, never persisted to Mongo, never logged) — this satisfies FR-016's "encrypted/secured at rest" via env-secret injection (it is never in the DB at all; only a hypothetical *per-workspace persisted* signing key would need the `PiiStringConverter`).

**Security-chain wiring (REQUIRED, not optional)**: the existing `@Order(2)` permitAll matcher is `("/api/public/**","/api/candidate/**")` and does NOT cover `/api/webhooks/email/**`; under the current config that path falls to the `@Order(3)` chain whose `/api/**` entry point returns **401** for the unauthenticated provider POST — rejecting it before the signature check runs. F22 MUST add a dedicated chain `securityMatcher("/api/webhooks/email/**").anyRequest().permitAll()` that is **CSRF-exempt** (machine caller, no session) and **STATELESS**, registered so it does NOT widen the `@Order(2)` public chain or the `@Order(3)` `/api/**` 401 / 403 / actuator-404 contracts (a dedicated security-config test asserts this). The handler is unauthenticated-by-design (real auth = in-controller signature), so `RbacEndpointInventoryTest` needs an explicit allow-list entry for it (F02 inventory precedent). "permitAll" governs *routing only* — the signature check is the actual gate.

**Idempotent intake (FR-019/SC-009)**: each provider event carries a stable event id; the bounce service records processed event ids (or CAS-guards the dispatch-status transition) so duplicate/out-of-order callbacks produce exactly one candidate flag and one notification. Hard bounce → `candidate.undeliverable=true` + bounce metadata + `EMAIL_DISPATCH_BOUNCED` audit + recruiter notification; soft bounce → record on the dispatch row only, **no** candidate flag/suppression (FR-018/SC-010).

**Honest bound (SC-005)**: documented as "100% of hard bounces *detectable by the configured provider's event webhook* and 100% of terminal send failures are recorded + surfaced." If a deployment configures a provider without an event webhook, hard-bounce detection degrades to synchronous rejections only — surfaced in `plan.md`/ops docs, not silently.

**§II note**: this is the one genuinely new public surface. The product spec's "Public REST API / webhooks (FR-16)" deferral refers to *our* outbound public API for third parties — an *inbound* provider callback (like the F01.1 OAuth callback and F06 calendar callbacks already shipped) is a provider integration, not a public product API, so it is in-scope and not a constitution conflict.

## D5 — Idempotency key + claim ordering (the exactly-once + crash-window guarantee)

**Decision**:
- **Key (FR-009)**: `idempotencyKey = sha256(workspaceId | candidateId | messageType | scheduledForEpochMillis)` (length-prefixed, the F10 `GoogleEventId` hashing precedent). For an immediate send the caller supplies `scheduledFor = trigger instant`. Unique index `{workspaceId, idempotencyKey}`.
- **Enqueue**: insert one `EmailDispatch` row `PENDING`. A duplicate enqueue hits the unique index → `DuplicateKeyException` → treated as the idempotent success it is (row already exists).
- **All status transitions are raw `findAndModify` CAS — NO `@Version` on `EmailDispatch`.** Critical: `@Version` engages ONLY through `MongoRepository.save(...)` and is silently ignored by `findAndModify`/`updateFirst` (the F03/F21 lesson). Mixing a `findAndModify` claim with a later `save()` would drift the version and throw spuriously. So `EmailDispatch` carries NO `@Version`; the **unique `{workspaceId,idempotencyKey}` index is the durable guarantee** and the **CAS claim is the concurrency guarantee** (exactly like F00.2 `SchedulerCheckpointService.start` and the F01.1 token CAS — neither uses `@Version`). Unlike F21 (which genuinely does load→mutate→`save`), the dispatch row needs no optimistic lock.
- **Claim-before-send (FR-010)**: the worker CAS-claims `findAndModify({_id, status:PENDING, nextAttemptAt:{$lte:now}} → SENDING, set updatedAt, inc attemptCount)`; only the claimer transmits (concurrent claimers get `matchedCount==0` → no-op). After the transport accepts → CAS `findAndModify({_id, status:SENDING} → SENT, set providerMessageRef/sentAt)`. Transient failure → `SENDING → PENDING` with `nextAttemptAt = now + backoff(attempt)+jitter` until the cap → `FAILED` + dead-letter (proven by a gated N-thread test asserting one `SENDING` and `recordingTransport.sentCount()==1`).
- **Crash window**: a crash between transport-accept and the `SENT` write leaves a row stuck `SENDING`. A stale-`SENDING` reaper CAS `findAndModify({_id, status:SENDING, updatedAt < threshold} → SENT_UNCONFIRMED)` and does **NOT** resend — honouring FR-010's "a send the provider already accepted MUST NOT be re-sent." **Config invariant**: `reaper.threshold > transport.read-timeout + max-backoff` so the reaper can never race a live/retrying claim mid-flight (stated in `EmailDeliveryProperties`). The cost is a rare possibly-unsent message in the narrow accept↔commit window, recorded and ops-visible (never a silent clean report) — the honest bound, mirroring F11's `transactionId` "bounded guard vs durable claim" framing. Best-effort de-dup hint: set the SMTP `Message-ID` header = the idempotency-key hash so providers that dedup on Message-ID get a second line of defence.

**Rationale**: the unique outbox row is the **durable** exactly-once guarantee; claim-before-send + version CAS makes concurrency and restart safe; the crash-window trade-off is explicitly biased toward "no duplicate" (the spec's hard requirement) over "never miss," and is documented rather than hidden.

**Alternatives**: blind resend of stuck `SENDING` (at-least-once) — rejected, violates FR-010 no-duplicate. Two-phase provider idempotency token — SMTP has none; Message-ID dedup is best-effort only.

## D6 — Scheduled dispatch on the F00.2 checkpoint pattern

**Decision**: `EmailDispatchScheduler` runs a fixed-delay `@Scheduled` `sweep()` that, wrapped in `SchedulerCheckpointService.start(TASK)/complete(TASK)` (exactly like `RetentionScanTask.runScan`), selects due rows and runs each through `EmailDispatchService`. `@PostConstruct registerReplayAction(TASK, this::sweep)` so a missed firing window (downtime) replays the same sweep once on `ApplicationReadyEvent`. Consuming features (F23/F31/F32) enqueue a `PENDING` row with a future `scheduledFor` — they get idempotency + missed-fire recovery for free (FR-020..022).

**Due-row query — explicit `@Query`, never a derived method (the F12 `InvalidMongoDbApiUsageException` lesson)**: `@Query("{ 'status': ?0, 'scheduledFor': { $lte: ?1 }, 'nextAttemptAt': { $lte: ?1 } }")` with a `Pageable`/`limit` batch cap so a backlog cannot load an unbounded result set into one tick. (`scheduledFor` and `nextAttemptAt` are *different* fields so there is no two-criteria-on-one-field trap, but the explicit `@Query` + batch is the established safe pattern.)

**Re-entrancy note**: `@Scheduled(fixedDelay)` is single-threaded per task (Spring default pool 1), so a slow sweep cannot overlap itself; **correctness does not depend on that** — the per-row CAS claim makes any double-pick (overlap, rolling-deploy two-instance window) a no-op. The scheduler comment must say so, so nobody later removes the CAS as an "optimization." No leader election (spec assumption).

**Rationale**: reuses the constitution-mandated async pattern verbatim; the per-message unique key (D5) is the real safety net.

## D7 — Candidate `undeliverable` suppression via the existing gate

**Decision**: extend F04's `ContactPermissionGate` with an `UNDELIVERABLE` reason: a candidate with `undeliverable=true` is denied (lowest precedence, after the legal reasons `erased > over_retention > withdrawn > no_basis`). So a send to a hard-bounced candidate becomes a recorded **refusal** (FR-006/FR-017) through the single decision point, not a special-case branch. A recruiter clears the flag (after correcting the address) via the recruiter surface; the clear is audited. The `undeliverable` flag + bounce metadata are PII-adjacent candidate fields purged by F04 erasure (set to defaults alongside the existing PII wipe).

The `UNDELIVERABLE` check is inserted as the **last positive guard before `Decision.allow()`** (after the existing `getLawfulBasis()==null` check), so precedence is `erased > over_retention > withdrawn > no_basis > undeliverable` — a legal/consent reason always wins over the operational flag (an erased+bounced candidate reports `ERASED`). Do NOT overload the existing `UNAVAILABLE` (candidate-missing/read-error) for this.

**Erasure-purge is an explicit edit to F04's `CandidateErasureService.wipe`** (a hardcoded `updateFirst` `Update` that today sets only name/email/phone/erasureState/erasedAt + `$unset emailHash`). F22 MUST extend that single `Update` to also reset `undeliverable=false` + clear `undeliverableReason/undeliverableAt/undeliverableClearedAt`, and the erasure test must assert no residual bounce metadata on an erased subject (FR-017/SC-002). This is a required task, not an implied one.

**Rationale**: one decision point (the gate) keeps the "every dispatch is gated" guarantee structural; adding a reason is additive and fail-closed. Erasure-purge keeps GDPR coherent.

## D8 — §II demonstrability without F13/F51

**Decision**: F22's browser→backend→MongoDB→email leg is the **existing** F01 member-invitation and password-reset flows (Admin invites a member in the `admin` feature UI → real SMTP email arrives at the recording transport / local sink) plus the F00.2 dead-letter system alert now sending. The **candidate** consent-gated pipeline (gate + outbox + idempotency + scheduled + bounce) is delivered and demonstrated by automated acceptance tests (API→service→Mongo→recording transport / bounce-webhook stub — §II point 4 explicitly permits an automated acceptance test as the end-to-end proof), and given a real browser trigger via a thin recruiter **"Send to candidate"** action added to the existing F21 `email-templates` preview (which already accepts a `candidateId`). The full candidate pipeline UI surface (pipeline view, bounce badges) is F51; the automated scheduling triggers are F13/F23/F31/F32.

**Rationale**: F22 is infrastructure; its backlog acceptance criteria are all system behaviours (consent check, idempotency, bounce recording, provider swap, no-PII logs) with **no** F22-specific frontend AC. The member-email path is a genuine, already-wired browser flow that F22 makes real — honest §II without building F51. No candidate-management UI is invented.

## D9 — Reuse map (no reinvention)

| Need | Reused seam |
|---|---|
| Consent/erasure decision | F04 `ContactPermissionGate.evaluate(workspaceId, candidateId)` (+ `UNDELIVERABLE`) |
| Render candidate message | F21 `EmailTemplateService` — **add a public `RenderedMessage renderForSend(workspaceId, type, stageKey, candidateId, Map<String,String> nonPiiContext)`** (the existing `resolveForRender` is `private` and returns a private record, so it is NOT reachable today; `renderForSend` does the candidate-name decrypt + scoped read + `resolveForRender` + `renderer.render` *inside* the F21 service, keeping PII decryption there). Reuses `MergeRenderer` → `RenderedMessage`. |
| Recipient address | `Candidate.getEmail()` (decrypted on read) — resolved at send, never stored on the outbox |
| Idempotent scheduled fire | F00.2 `SchedulerCheckpointService` (`start`/`complete`/`registerReplayAction`) |
| Terminal-failure dead-letter + ops alert | F00.2 `DeadLetterService.recordFailure(...)` (PII-sanitised) + `EmailSender.sendSystemAlert` |
| Audit | `AuthAuditService.record(...)` with new `EMAIL_DISPATCH_*` event types (ids/type only, value-free) |
| Concurrency on the dispatch row | **`findAndModify` CAS, NOT `@Version`** (the dispatch row carries no `@Version` — see D5; the unique index + CAS are the guarantees, the F00.2/F01.1 precedent). `@Version`/`save()` is the F21 pattern for the *template* row, not reused here. |
| Hashing | length-prefixed SHA-256 (F10 `GoogleEventId` precedent) for the idempotency key + Message-ID |

## D10 — Logging/PII discipline (carried forward)

Never pass an enum to `StructuredArguments.kv(...)` (the F01.1 logstash Jackson-3 `NoSuchFieldError`) — log `status.name()`/`messageType.name()`/ids only (the new 14-value `DispatchOutcomeReason` is the most likely accidental `kv` victim). No recipient address, candidate name, subject, or body at any level (FR-023). Audit/notification payloads are value-free (ids + type/reason literals). `ci.yml` PII scan extended with F22 sentinels (a body sentinel + a recipient-email sentinel) and a `Message-ID`/header-injection guard.

## D11 — Observability & operability (FR-024)

**Decision**: beyond structured logs + audit, expose Micrometer metrics via the already-present actuator (no new dependency): a gauge of `emailDispatches` by `status` (PENDING backlog depth, oldest-PENDING age), counters for sent/refused/failed/bounced and for reaper-marked `SENT_UNCONFIRMED`, and a counter for webhook unmatched/cross-workspace events (chronic floods → rate-limited PII-free WARN). The `sweep()` emits one PII-free structured log per tick with `due/sent/refused/failed` counts. An operator watches PENDING-backlog depth and reaper-hit count to detect a stalled worker or a dead provider.

**Rationale**: FR-024 requires dispatch/refusal/bounce/dead-letter to be operator-observable; the reaper and reconciliation are invisible without a metric watching them.

## D12 — Dead-letter alert during a full SMTP outage (known limitation)

`DeadLetterService.recordFailure` persists the record first, then calls `EmailSender.sendSystemAlert` inside a swallowing `try/catch` (so there is **no recursion** — a failed alert is logged, never re-dead-lettered). But if the failure cause is "SMTP/provider down," the alert email itself goes over the same dead transport and also fails (logged, not retried). **Documented limitation**: during a full SMTP outage, dead-letter records are still written (the durable signal) but the alert *email* will not arrive; the actuator backlog metric (D11) is the out-of-band signal an operator relies on. The ops-alert uses the **app-level default sender**, not a workspace sender. A transport-independent alert channel is out of MVP scope.
