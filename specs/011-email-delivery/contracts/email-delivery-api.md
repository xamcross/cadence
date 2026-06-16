# Contracts: Email Delivery Channel (F22)

## A. Recruiter send — `POST /api/internal/candidates/{candidateId}/emails`

Send a templated message to a candidate now (the candidate-pipeline's real browser trigger; F13/F23/F31/F32 will enqueue programmatically through the same `EmailDispatchService`).

- **Auth**: `@PreAuthorize("hasAnyRole('ADMIN','RECRUITER')")` (class or method level).
- **Request**:
  ```json
  { "messageType": "CONFIRMATION", "stageKey": "BASE", "sampleValues": { } }
  ```
  - `messageType` required, one of `EmailMessageType`. `stageKey` optional (default `"BASE"`; a variant id is F21-validated). `sampleValues` optional non-PII contextual scalars (e.g. a date string) for tokens not derivable from the candidate; candidate name is resolved server-side, never client-supplied.
- **Behaviour**: resolve candidate (`findByWorkspaceIdAndId`) → enqueue an immediate `EmailDispatch` (`scheduledFor = now`) → the dispatch path evaluates the consent gate, renders (F21), transmits, records. Returns the dispatch outcome.
- **Responses**:
  | Status | Body | When |
  |---|---|---|
  | `202 Accepted` | `{ "dispatchId", "status": "SENT" | "PENDING", "messageType" }` | enqueued/sent |
  | `200 OK` | `{ "dispatchId", "status": "SENT" }` (idempotent) | same logical message already sent (duplicate idempotency key) |
  | `403` | `{ "error": "forbidden" }` | role denied (RBAC) |
  | `404` | `{ "error": "not_found" }` | candidate not in workspace (oracle-free, shared with scoped-not-found) |
  | `409` | `{ "error": "not_contactable", "reason": "ERASED|WITHDRAWN|OVER_RETENTION|NO_BASIS|UNDELIVERABLE|UNAVAILABLE" }` | consent gate refused (value-free reason; recruiter-scoped, not an external oracle) |
  | `400` | `{ "error": "invalid_request", "fields": { … } }` | bad/missing `messageType`, unknown `stageKey`, null body |

- **No-PII**: response carries ids + status + reason literal only — never the recipient address, rendered subject, or body. `Cache-Control: no-store`.

## B. Provider event webhook — `POST /api/webhooks/email/events`

Inbound provider bounce/delivery/complaint notifications (D4). Public chain, signature-gated.

- **Auth**: NO session. **Required new security chain** `securityMatcher("/api/webhooks/email/**").permitAll()` — **CSRF-exempt + STATELESS** (machine caller). This is mandatory: the existing `@Order(2)` permitAll matcher (`/api/public/**`,`/api/candidate/**`) does NOT cover this path, so without a dedicated chain the `@Order(3)` `/api/**` entry point returns 401 before the controller runs. The chain must not widen the existing 401/403/actuator-404 contracts (dedicated security-config test) and needs a `RbacEndpointInventoryTest` allow-list entry (handler is unauthenticated-by-design). Real auth = the in-controller provider signature/shared-secret (`CADENCE_EMAIL_WEBHOOK_SECRET`, app-level env Fly secret, never persisted/logged) verified **before** any state change.
- **Request**: provider event batch (provider-specific JSON parsed by explicit `JsonNode.path(...)` reads — never bind free-text). Each event yields `{ eventId, providerMessageRef, type ∈ {delivered, bounce(hard|soft), complaint}, occurredAt }`.
- **Behaviour**:
  1. Verify signature → invalid ⇒ `401`, **no** state change (SC-008).
  2. For each event: correlate `providerMessageRef` → `emailDispatches` row; confirm `workspaceId`. Unknown/cross-workspace ⇒ ack-and-ignore (no state change, no existence leak).
  3. Idempotent by `eventId` (already-processed ⇒ no-op, SC-009).
  4. `delivered` → row `lastOutcomeReason=DELIVERED` (informational). `hard bounce`/`complaint` → **ordered, non-transactional, idempotent**: (i) CAS the dispatch row `→ BOUNCED` (idempotent by `eventId`), then (ii) set candidate `undeliverable=true` + value-free metadata, then (iii) `EMAIL_DISPATCH_BOUNCED` audit + recruiter notification. A crash mid-sequence is safe: the gate fail-closes on `undeliverable`, and a missed candidate-flip is caught next dispatch via the row state; the `eventId` idempotency makes a replay a no-op. The parser reads only `{eventId, providerMessageRef, type-enum, occurredAt}` — the provider's free-text `reason`/`description` is **never bound** (F11 parse-discipline). `soft bounce` → row `lastOutcomeReason=SOFT_BOUNCE` only; **no** candidate flag (FR-018).
- **Responses**: `200` (processed/acked, including ignored-unknown), `401` (bad signature). Always value-free; never echoes recipient/subject/body.

## C. Internal dispatch SPI (consumed by F13/F23/F31/F32)

`EmailDispatchService` — the single candidate-send entry point. Not an HTTP endpoint; an injected service.

```java
/** Enqueue (and, if due now, run) a candidate email. Idempotent on (workspace,candidate,type,scheduledFor). */
DispatchResult enqueue(String workspaceId, String candidateId, EmailMessageType type,
                       String stageKey, Instant scheduledFor,
                       Map<String,String> nonPiiContext, String renderContextRef);

record DispatchResult(String dispatchId, DispatchStatus status, DispatchOutcomeReason reason) {}
```

- Immediate send: `scheduledFor = now` → claimed + transmitted inline (or by the next scheduler tick on contention). Future send: `scheduledFor > now` → row sits `PENDING` until the scheduled worker picks it up (D6).
- Every path runs the consent gate at **dispatch time** (FR-007). Duplicate enqueue (same key) → returns the existing row (idempotent, no second send).
- Rendering goes through a **new public** `EmailTemplateService.renderForSend(workspaceId, type, stageKey, candidateId, nonPiiContext)` → `RenderedMessage` (the existing `resolveForRender` is private/unreachable; `renderForSend` does the scoped candidate read + name-decrypt + resolve + render inside F21, keeping PII decryption there).
- `nonPiiContext`/`renderContextRef` MUST be PII-free: `nonPiiContext` is **transient** (passed to render, never persisted); `renderContextRef` is **shape-guarded** (ObjectId/bounded token). Candidate name is resolved internally, never client-supplied. Enforced structurally + by the PII-scan test.

## D. Transport interface (provider-swap boundary, FR-003/SC-007)

```java
public interface EmailSender {                       // existing, widened
    void sendEmail(String toInternalId, String templateId, Map<String,String> mergeFields); // member/operational (F01)
    void sendSystemAlert(String taskName, String errorSummary);                              // ops (F00.2)
    SendOutcome send(OutboundEmail message);                                                  // NEW pre-rendered
}
record OutboundEmail(String workspaceId, String toAddress, String subject, String htmlBody, String messageId) {}
record SendOutcome(boolean accepted, String providerMessageRef, boolean transient_, String reasonCode) {}

interface MailTransport { SendOutcome transmit(OutboundEmail m); }   // NEW thin SPI; SmtpMailTransport (prod) / RecordingMailTransport (test)
```

- `SmtpEmailSender` (`@Primary`, replaces `NoOpEmailSender`) implements `EmailSender`, delegating actual transmission to `MailTransport`. Swapping the provider = replace `MailTransport`/`EmailSender` bean — **zero** calling-service edits (verified by an interface-level test, SC-007).
- `SendOutcome.transient_` drives the retry/terminal classification (D5); `reasonCode` is a value-free code (never provider free-text).

## E. Contract tests (MockMvc + Testcontainers)

- Recruiter send: 202/200-idempotent/403-per-role/404-scoped/409-each-refusal-reason/400-bad-body; response carries no PII; `no-store`.
- Webhook: 401 bad signature → no state change; hard-bounce → candidate flagged + audit + notify; soft-bounce → no flag; duplicate eventId → single flag/notify; unknown ref → ack-no-change.
- Dispatch SPI: gated refusal (each reason) → REFUSED + no transmit; idempotent duplicate enqueue → one row, one send (gated concurrency latch); scheduled future row runs once; missed-fire replay once; transient→recover→one send; cap→FAILED+dead-letter.
- Transport swap: replace `MailTransport` bean, all behaviour preserved, no service edits.
- PII scan: a failing render + a bounce with a sentinel recipient/body → assert absent from logs/audit/outbox/dead-letter (TRACE scan scoped to `com.cadence`).
