# Data Model: Email Delivery Channel (F22)

## 1. New collection — `emailDispatches` (outbox)

One document per logical outbound candidate message. **No PII at rest** (candidate internal ID + ids/instants/opaque refs only) → un-encrypted by design, like `managedCalendarEvents`/`interviewTemplates`/`emailTemplates`.

| Field | Type | Notes |
|---|---|---|
| `_id` | ObjectId | |
| `workspaceId` | String | scope; part of the unique key |
| `candidateId` | String | internal ObjectId hex only — the recipient is resolved (decrypted) from `candidates` at send time, **never stored here** |
| `messageType` | `EmailMessageType` | F21 enum (CONFIRMATION, REMINDER_24H, …) |
| `stageKey` | String | F21 variant key (`"BASE"` or interview-template id) used to resolve the template at render |
| `idempotencyKey` | String | `sha256(workspaceId|candidateId|messageType|scheduledForEpochMillis)`; **unique** with `workspaceId` |
| `status` | `DispatchStatus` | state machine §3 |
| `attemptCount` | int | incremented on each claim |
| `scheduledFor` | Instant | due time; = trigger instant for immediate sends |
| `nextAttemptAt` | Instant | backoff gate for retries (≤ `scheduledFor` initially) |
| `sentAt` | Instant | set on `SENT` |
| `lastOutcomeReason` | `DispatchOutcomeReason` | value-free enum (no submitted/PII text) |
| `providerMessageRef` | String | opaque provider message id (set on send; webhook correlation key) |
| `renderContextRef` | String (nullable) | optional non-PII reference to a source entity (e.g. bookingId) a consumer feature passes so a retry re-renders deterministically. **Shape-guarded** to ObjectId-hex or a bounded opaque token (the `DeadLetterService.sanitiseCandidateId` precedent) so a free-text value can never smuggle PII in. |
| `createdAt` / `updatedAt` | Instant | `updatedAt` set on every transition (the reaper's staleness basis) |

**No `@Version`** on this document: `@Version` engages only via `MongoRepository.save(...)` and is silently ignored by `findAndModify` — and every status transition here is a raw `findAndModify` CAS (D5). The unique `{workspaceId,idempotencyKey}` index is the durable guarantee; the CAS claim is the concurrency guarantee (the F00.2/F01.1 precedent — neither uses `@Version`).

**Excluded by design** (FR-013/SC-006): recipient email address, candidate name, rendered subject, body, and merge-field values. The caller's `nonPiiContext`/`sampleValues` are **transient to the render call — never persisted on the row**; merge values are re-derived at send from the candidate record (+ the shape-guarded `renderContextRef` source) so a retry renders identically with zero PII persisted.

### Indexes (ChangeUnit010, order "010")
- **unique** `{ workspaceId: 1, idempotencyKey: 1 }` — the durable exactly-once guarantee (FR-009).
- `{ status: 1, nextAttemptAt: 1 }` — the scheduled due-row picker (D6); covers the hot worker query.
- `{ providerMessageRef: 1 }` — webhook event → dispatch correlation (FR-019). Sparse (null until sent).
- `{ workspaceId: 1, candidateId: 1, createdAt: -1 }` — per-candidate communications history (FR-014 audit/timeline read).

## 2. Modified collection — `candidates` (F04)

Add operational deliverability fields (PII-adjacent → purged on erasure):

| Field | Type | Notes |
|---|---|---|
| `undeliverable` | boolean (default false) | set true on hard bounce; suppresses further automatic sends (D7) |
| `undeliverableReason` | `DispatchOutcomeReason` (nullable) | value-free (e.g. HARD_BOUNCE); never the provider's free-text |
| `undeliverableAt` | Instant (nullable) | |
| `undeliverableClearedAt` | Instant (nullable) | set when a recruiter clears the flag |

- **Erasure interaction (required edit, not implied)**: F04's `CandidateErasureService.wipe` is a hardcoded `updateFirst` `Update` setting only name/email/phone/erasureState/erasedAt + `$unset emailHash`. F22 MUST extend that single `Update` to also `set("undeliverable", false)` and clear the three nullable fields — no residual bounce metadata on an erased subject. A test asserts an erased candidate has no residual `undeliverable*` state (FR-017/SC-002).
- **No new index** — the dispatch/webhook reads the candidate by `_id`/`{workspaceId,_id}` (existing). `toString()` already omits PII; the new fields are non-PII booleans/instants and may appear in `toString()` safely.
- `ContactPermissionGate` gains an `UNDELIVERABLE` deny reason checking `candidate.isUndeliverable()` (lowest precedence).

## 3. Dispatch state machine (`DispatchStatus`)

```
                 enqueue
                   │
                   ▼
   (gate refuse) ┌──────┐  claim (CAS PENDING→SENDING)   ┌─────────┐  transport accept  ┌──────┐
   ◄─────────────│PENDING│───────────────────────────────►│ SENDING │───────────────────►│ SENT │
   REFUSED       └──────┘                                  └─────────┘                    └──────┘
   (terminal,        ▲   transient fail (SENDING→PENDING, backoff, attemptCount<cap)  │        │
    audited,         └──────────────────────────────────────────────────────────────┘        │ provider webhook
    notify)                                                                                    ▼
                                          cap exhausted / permanent send error          ┌──────────┐
                                          (SENDING→FAILED + dead-letter + notify)        │ BOUNCED  │ (hard, FR-017)
                                                            │                            └──────────┘
                                                            ▼                            (soft bounce: NO status change,
                                                        ┌────────┐   stale-SENDING reaper   recorded on row only, FR-018)
                                                        │ FAILED │   (older than threshold)
                                                        └────────┘          │
                                                                            ▼
                                                                   ┌──────────────────┐
                                                                   │ SENT_UNCONFIRMED │ (crash window; NO resend, FR-010)
                                                                   └──────────────────┘
```

All transitions are raw `findAndModify` CAS (no `@Version`); the gate is evaluated at claim time on the PENDING row, so a refusal happens before any transmit.

- **PENDING → SENDING**: atomic `findAndModify({_id, status:PENDING, nextAttemptAt:{$lte:now}} → SENDING, set updatedAt, inc attemptCount)`; only the winner sends (concurrency-safe, D5). The consent gate is evaluated here — a deny short-circuits to `REFUSED` instead.
- **SENDING → SENT**: `findAndModify({_id, status:SENDING} → SENT)` on transport accept; set `providerMessageRef`, `sentAt`.
- **SENDING → PENDING**: transient transport error, `attemptCount < cap`; set `nextAttemptAt = now + backoff(attempt)+jitter`.
- **SENDING → FAILED**: `attemptCount ≥ cap` or permanent error; `DeadLetterService.recordFailure` + recruiter notification (FR-012).
- **(claim) → REFUSED**: gate denies at claim time (FR-006/FR-007); terminal; audited + recruiter notification (FR-008). Re-evaluated at fire time, never cached.
- **SENT → BOUNCED**: hard-bounce webhook (FR-017) → candidate `undeliverable=true` + notify.
- **SENDING → SENT_UNCONFIRMED**: stale-`SENDING` reaper only — `findAndModify({_id, status:SENDING, updatedAt < threshold} → SENT_UNCONFIRMED)`, crash between accept and commit; no resend (FR-010). Config invariant: `reaper.threshold > transport.read-timeout + max-backoff` so it never races a live claim.
- Soft bounce: recorded as `lastOutcomeReason=SOFT_BOUNCE` on the row; **no** status change, **no** candidate flag (FR-018/SC-010).

## 4. Enums

- **`DispatchStatus`**: `PENDING, SENDING, SENT, SENT_UNCONFIRMED, FAILED, BOUNCED, REFUSED`.
- **`DispatchOutcomeReason`** (value-free): `NONE, NO_BASIS, WITHDRAWN, ERASED, OVER_RETENTION, UNAVAILABLE, UNDELIVERABLE, TRANSPORT_REJECTED, RETRY_EXHAUSTED, HARD_BOUNCE, SOFT_BOUNCE, COMPLAINT, RENDER_FAILED, NO_PROVIDER_CONFIG`. (Maps the `ContactPermissionGate.Reason` set + transport/bounce outcomes; never the provider's free-text.)
- **`AuthEventType`** (append-only additions): `EMAIL_DISPATCH_SENT, EMAIL_DISPATCH_REFUSED, EMAIL_DISPATCH_FAILED, EMAIL_DISPATCH_BOUNCED`. (Audit carries ids + type + reason literal only — value-free, F21 audit precedent.)

## 5. Operational (member/system) email — no new collection

Member invitation, password-reset, and dead-letter system-alert emails are **not** outbox-tracked (they are not candidate sends, not consent-gated, not retried as a candidate dispatch). `SmtpEmailSender` resolves the member address + renders an `OperationalEmailTemplates` constant and transmits directly. (Their existing F01 single-use-token / audit machinery is unchanged.)

## 6. Validation & guards

- Recruiter send request: `messageType` required + valid; `candidateId` workspace-scoped (`findByWorkspaceIdAndId`, empty → `ScopedNotFoundException` → 404, oracle-free); optional `stageKey` validated like F21; gate evaluated → a refusal returns a value-free `409`/`422`-style envelope (no oracle on consent state beyond the recruiter's own scope).
- Webhook: signature first (reject → 401, no state change); event→row correlation by `providerMessageRef` + `workspaceId`; unknown ref → 2xx-ack-and-ignore (don't leak existence) with no state change; duplicate event id → idempotent no-op.
- Null/blank request bodies → `400 invalid_request` (no NPE/500), F21 precedent.
