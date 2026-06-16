# Phase 0 Research: Flow A1 — Single-Stage Scheduling (F13)

All NEEDS CLARIFICATION from the spec were resolved by informed defaults (documented in the spec's Assumptions). This file records the load-bearing design decisions, each as Decision / Rationale / Alternatives.

---

## D1 — Offered slots: persisted snapshot at send time (not live recompute)

**Decision**: At initiation, `RuleEngine.compute(...)` produces the compliant slots once; F13 **snapshots** them as an embedded `List<OfferedSlot>` on the new `SchedulingRequest` document. The candidate page reads the snapshot; correctness against staleness is restored by re-validation at confirm (D4), not by recomputing on every page load.

**Rationale**: Recomputing panel availability on each candidate page view would fan out N calendar free/busy calls per refresh (cost, latency, provider rate-limits) and make the candidate page depend on every interviewer's calendar being reachable at view time. A snapshot is cheap, stable, and the candidate sees a consistent list; the only correctness risk (an interviewer's calendar changed after send) is a narrow window closed by confirm-time re-validation + the atomic claim. Matches the spec Assumption.

**Alternatives**: (a) Live recompute per view — rejected (cost/latency/availability coupling). (b) Persist each offered slot as its own document — rejected; the slots belong to one request lifecycle, embedding keeps the aggregate together and lets the request-level status CAS (D3) act as the single same-candidate concurrency guard. The cross-request guard is a separate collection (D3) regardless.

---

## D2 — Two new collections, no PII; recruiter location passed transiently

**Decision**: `schedulingRequests` (request lifecycle, embedded offered-slot snapshot, hashed token, expiry, chosen-slot/booking refs) and `interviewSlotClaims` (the per-participant reservation guard). Both store **ids + instants + enums only**, with **one exception**: the recruiter-provided interview **location/dial-in** free text. Because the candidate confirms asynchronously (the recruiter is not in the loop at confirm time), the location must be available when the booking happens, so it is persisted on `SchedulingRequest` — but **encrypted at rest** via the existing per-property `PiiStringConverter` (the F03 `emailProviderCredential` precedent), `@JsonIgnore` + `@Field(write=NON_NULL)`, and excluded from every candidate-facing, log, and audit output. It is decrypted transiently only to build the calendar `EventDetails` at confirm. Everything else on both collections is plaintext ids/instants/enums (the `managedCalendarEvents`/`interviewTemplates`/`emailDispatches` un-encrypted precedent).

**Rationale**: Keeps the "no candidate PII in scheduling docs" guarantee structural for all fields except the one workspace-authored value that genuinely must survive to confirm; encrypting it (rather than storing plaintext) covers the case where it carries a private room, dial-in number, or PIN. Re-prompting the recruiter at confirm is impossible (they are not present).

**Alternatives**: Persist location plaintext — rejected (could contain a private dial-in/PIN). Pass it transiently only (not persist) — rejected; the recruiter is absent at confirm, so there is nowhere to re-source it. A separate encrypted collection — rejected (YAGNI; one converter-managed field on the request suffices).

---

## D3 — Atomic reservation: two CAS layers (the load-bearing correctness control)

**Decision**: Reservation is guarded at two levels.

1. **Request-level status CAS** — `findAndModify({_id, status:PENDING_SELECTION, expiresAt>now} → status:BOOKING, chosenSlotId)`. Guards same-candidate double-submit / double-click / link-reopen-during-booking: a lost CAS returns the existing outcome (idempotent, FR-019), never a second booking.
2. **Per-participant claim CAS** — for the chosen slot's required ∪ pool-selected members, insert one `InterviewSlotClaim` per (workspaceId, memberId, startAt) backed by a **unique partial index** `{workspaceId, memberId, startAt}` (partial: `status = ACTIVE`). The **first** booking to insert a given (member, start) wins; a concurrent booking from a *different* candidate/request for the same interviewer-time gets `DuplicateKeyException` → release any claims already inserted for this booking → request back to `PENDING_SELECTION` → **409 slot_taken**. This is the cross-request interviewer double-booking guard.

**Rationale**: One scheduling link belongs to one candidate, so the request-level CAS alone covers intra-request concurrency. It does **not** cover two *different* candidates (two requests) being offered the same interviewer at the same instant — that is the real double-booking race, and a single-document status flip cannot express a cross-document invariant. The unique-index claim is the house pattern for exactly-once across documents (F22 `{workspaceId,idempotencyKey}`, F10 `{workspaceId,bookingRef,memberId,provider}`), is broker-free (constitution §IV), and yields a provable "exactly one winner" under a gated concurrent test (SC-003).

**Honest bound (documented, MVP-acceptable)**: the unique key is exact `(member, startAt)`. Because single-stage offered slots for a given template share one cadence grid, two candidates contending for the same interviewer collide on an identical `startAt` and are correctly serialized. Two *different* templates (different duration/cadence) could offer the same interviewer **partially overlapping** slots with **different** start instants, which the exact-start unique index would not catch. Confirm-time re-validation (D4) reads `managedCalendarEvents` + free/busy and rejects a slot whose interviewer is now busy, closing this for the committed-event case; a sub-second TOCTOU between two simultaneous cross-template confirms remains theoretically possible and is documented as an accepted MVP limitation (the dominant same-template case is fully atomic). Closing it fully would need an interval-overlap reservation (a range index or a per-member booking calendar), deferred.

**Alternatives**: (a) MongoDB multi-document transaction across N claims — rejected; Atlas supports it but the constitution prefers single-document CAS and it adds a coordinator/retry surface for no gain over per-claim unique inserts. (b) Reserve only the offered-slot status in the request — rejected; does not prevent cross-request double-booking (the core failure mode).

---

## D4 — Confirm-time re-validation + pool re-selection

**Decision**: Before claiming/booking, `SlotReservationService` re-runs `AvailabilityService.query(...)` for the chosen slot's window over the required members and each pool's candidates. Required members must all be free (status `DATA`, no busy overlap); for each pool, re-select a still-free qualifying quorum of size `n`. If a required member is busy or a pool can no longer form its quorum → refuse this slot and return the remaining still-offered slots (the candidate picks another). A successful re-selection MAY bind a different qualifying participant set than the snapshot; the booking proceeds with the re-selected set (FR-013).

**Rationale**: The snapshot can go stale between send and pick (D1). Re-validation is the correctness backstop and the only place the pool quorum is *bound* to concrete members (F12 explicitly defers pool-member binding to "F13's atomic re-validation"). Reuses `AvailabilityService` unchanged.

**Alternatives**: Trust the snapshot — rejected (books conflicts). Recompute the whole template — rejected (unnecessary; only the chosen slot needs validating).

---

## D5 — Token: 256-bit opaque, hashed at rest, expiry kept (no TTL delete), 410 vs 400

**Decision**: The link token is a 256-bit value from the F01 `SecureTokens.newToken()` (URL-safe base64), delivered only in the email link `{spaBaseUrl}/schedule?token=<raw>`; stored only as `tokenHash = TokenHasher.hashToken(raw)` (HMAC-SHA-256 + `TOKEN_PEPPER`) with a unique index. `SchedulingRequest.expiresAt = sentAt + workspace TTL (default 72h)`. The request document is **not** auto-deleted by a Mongo TTL index; expiry is computed from `expiresAt` at read time. Lookup is by `tokenHash`:
- hash matches a request that is past `expiresAt` (and not booked) → **410 Gone** ("expired").
- hash matches nothing, OR matches a `BOOKED`/`SUPERSEDED` request → **400** indistinguishable "invalid" (reopening a booked link shows the existing confirmation to the candidate via the booked-request branch; an unknown/superseded hash is a flat 400). Used/invalid/unknown are byte-identical (no existence oracle, FR-008/FR-010).

**Rationale**: A Mongo TTL index would delete expired requests, turning an expired token into "not found" → 400 and **losing the 410 distinction** the spec requires. Keeping the (PII-free, tiny) document lets us return 410 for genuinely-expired-but-extant tokens while collapsing unknown/used into 400. Reuses the F01 token-hash primitive verbatim. ≥128-bit requirement (FR-006) is exceeded (256-bit).

**Alternatives**: TTL-index auto-reap — rejected (breaks 410). Store raw token — rejected (constitution §VIII; F01 precedent hashes). Derive token from candidate id — rejected (FR-006).

---

## D6 — Stuck-reservation recovery: `@Scheduled` reaper on SchedulerCheckpoint

**Decision**: `SchedulingReaper` is a `@Scheduled(fixedDelay)` sweep wrapped in `SchedulerCheckpointService.start/complete` with a `@PostConstruct registerReplayAction` (the F22 `EmailDispatchScheduler` precedent). It does two idempotent jobs: (1) requests stuck in `BOOKING` older than a configurable threshold → release their `InterviewSlotClaim`s and CAS `BOOKING → PENDING_SELECTION` (so the candidate can retry); (2) `PENDING_SELECTION` requests past `expiresAt` → CAS to `EXPIRED`. **Config invariant** (pinned in `SchedulingProperties` and asserted by a unit test, the F22 precedent): a confirm runs a panel calendar create that fans out per participant, so the worst-case in-flight duration is roughly `(perCallReadTimeout + maxBackoff) × maxPanelSize`; therefore `reaperThreshold` MUST exceed that product so the reaper never releases a still-live in-flight confirm.

**Rationale**: A crash between request-status CAS and the `BOOKED` write would otherwise leave a slot permanently held (FR-017). Releasing `BOOKING→PENDING_SELECTION` is safe because the provider create is idempotent (F10 bookingRef-keyed deterministic id / F11 transactionId) — a resumed/retried confirm re-claims and re-creates idempotently. Correctness rests on the per-row CAS, not single-threading; a double-pick is a no-op. Constitution §IV (no broker) honoured.

**Alternatives**: Mongo TTL on the claim — rejected (TTL is best-effort minute-granularity and wouldn't release the request status or be missed-fire-safe). A held-state lease timestamp checked inline — rejected (only runs when a candidate happens to retry; the reaper guarantees release).

---

## D7 — Two confirmation recipient paths

**Decision**: **Reuse the existing `EmailMessageType` values — do NOT add new ones.** F21 already ships built-in templates and merge-token sets for `EmailMessageType.INVITATION` (mapped to the `SCHEDULING_LINK` merge token) and `EmailMessageType.CONFIRMATION` (mapped to interview date/time tokens) — exactly the two candidate sends F13 needs. The scheduling-invitation (initiation) is `EmailDispatchService.enqueue(workspaceId, candidateId, INVITATION, stageKey, now, nonPiiContext, renderContextRef)`; the booking confirmation is `enqueue(..., CONFIRMATION, ...)`. Both are consent-gated, idempotent on `{workspaceId,idempotencyKey}`, F21-rendered; using two distinct message types means they never collide on the candidate-keyed idempotency key. Each **internal participant** confirmation is sent through the F01 non-consent-gated member-mail path `EmailSender.sendEmail(memberId, "<operational-template-id>", model)` (a String template id, not an `EmailMessageType`), the same path F01 uses for invitations/resets (the consent gate does not apply to staff mail). A confirmation send failure does **not** roll back the committed booking (FR-018) — the candidate path is absorbed by the F22 outbox; member-path failures are best-effort + logged.

**Rationale**: `EmailMessageType` is a **closed enum** consumed by three `@PostConstruct`-checked catalogues (`BuiltInEmailTemplates.verifyComplete()`, `MergeTokenCatalogue`, `EmailTemplateService.list()`) that iterate `values()` — adding a bare constant without extending all three **fails the ApplicationContext at startup** (verified in the plan review). F21 was built for these two sends, so reuse is both correct and zero-cost. Participants are `members`, not `candidates`; they cannot ride the candidate-keyed consent-gated `enqueue`, so the member-mail path is the correct seam.

**Alternatives**: Add new `SCHEDULING_INVITATION`/`INTERVIEW_CONFIRMATION` enum values — **rejected (build-breaker)** unless all three catalogues are extended with built-ins + token sets, which is unnecessary since `INVITATION`/`CONFIRMATION` already cover it. Route participant mail through `EmailDispatchService` — rejected (candidate-keyed + consent-gated by construction).

---

## D8 — Per-IP rate limiting: in-memory, single-instance

**Decision**: Candidate token endpoints (`GET /api/candidate/scheduling/{token}`, `POST .../confirm`) are rate-limited to a configurable default **10 requests/min/IP** (FR-010), returning **429** on breach, by a small **new** in-memory sliding-window component `CandidateRateLimiter` keyed by `TokenHasher.hashIp(ip)` (so no raw IP is held even in volatile memory). It is modelled on the existing `LoginAttemptService.tryConsumeIp` (the proven F01 in-memory throttle) but hashes its key. The limiter is **advisory/best-effort**: it is NOT a correctness control — the no-double-book and no-oracle guarantees rest entirely on the DB unique-index claim (D3) and the 410/400 response design (D5), never on the limiter. State resets on restart / is per-instance during a rolling deploy; that is acceptable because the 256-bit token is unguessable, so a brief relaxed window enables no practical enumeration. No new dependency; single-instance topology (constitution §IV) makes in-memory authoritative.

**Rationale**: Brute-force/enumeration defense without Redis (prohibited). Hashing the key keeps even the transient in-memory map free of raw IPs (constitution §VIII), and explicitly decoupling it from correctness means a limiter reset/deploy never threatens the booking invariants.

**Alternatives**: Redis/bucket4j-distributed — rejected (new infra/dep, single instance doesn't need it). Gateway-level limit — rejected (not in our control on Cloudflare Pages free tier for API paths proxied to Fly).

---

## D9 — Booking aggregate & calendar bookingRef

**Decision**: The `SchedulingRequest.id` is the `bookingRef` passed to `CalendarEventService.createPanelEvents(workspaceId, bookingRef, participants, details)`. One request → at most one booking → one `bookingRef`, so the F10 unique `{workspaceId,bookingRef,memberId,provider}` index naturally dedups a retried panel create. Participants are built from the re-validated required ∪ selected-pool members, each with its time zone (member/workspace zone). `EventDetails` carries the interview title (from template/workspace) + the transient decrypted `locationText` + slot start/end + zone.

**Rationale**: Reuses the F10/F11 panel create + compensating-delete rollback + `CLEANUP_INCOMPLETE` honest bound unchanged. `PanelOutcome.CREATED` → `BOOKED`; `ROLLED_BACK` → release claims + back to `PENDING_SELECTION` + 409/retry; `CLEANUP_INCOMPLETE` → request `CLEANUP_INCOMPLETE` + recruiter alert + audit (FR-016, never a silent orphan, never a clean-success report).

**Alternatives**: A separate `bookings` collection — rejected; the request already is the booking aggregate, and `managedCalendarEvents` holds the per-participant event refs.

---

## D10 — Erasure interaction (FR-014)

**Decision**: `SlotReservationService` re-evaluates `ContactPermissionGate.evaluate(...)` at confirm; if not permitted (erased / withdrawn / over-retention / undeliverable / no-basis) the booking is **refused** (no calendar event for an erased data subject) and the candidate sees a neutral "this link is no longer available." Additionally, `CandidateErasureService.wipe(...)` is extended to **supersede** any live `schedulingRequests` for the erased candidate (status → `SUPERSEDED`) and release their `InterviewSlotClaim`s, so an erased candidate carries no live scheduling state.

**Rationale**: Closes the spec's Security-BLOCKING finding — contactability must be checked at confirm, not only at initiation, and erasure must not leave a bookable link. Reuses the F22 gate precedence (`erased > over_retention > withdrawn > no_basis > undeliverable`) verbatim.

**Alternatives**: Gate only at initiation — rejected (the spec's blocking finding). Let an erased candidate book with a suppressed email — rejected (orphaned booking for an erased subject; GDPR risk).

---

## D11 — §II demonstrable leg & frontend scope

**Decision**: Recruiter clicks "Send scheduling link" on a candidate (a minimal action surfaced on the existing candidate/email-templates surface — F51's full pipeline is out of scope), which calls the initiate endpoint (browser→Spring→Mongo→email). A new standalone Angular `schedule` feature renders the candidate slot-picker at a public, guard-free route, reading the token from the URL, displaying **times only** in the candidate's local zone, and POSTing the confirm. F13 ships this **functional**; F14 owns the formal WCAG-AA axe gate, Lighthouse ≥ 85, and full localization. All candidate strings are `$localize`-marked now so F14 hardens rather than rewrites.

**Rationale**: Constitution §II requires a real browser→DB flow; this delivers it end-to-end while honouring the spec's F13/F14 scope split.

**Alternatives**: Backend-only F13 — rejected (constitution §II prohibits backend-only "done"). Full F14 polish in F13 — rejected (scope; F14 owns it).

---

## D12 — Mongock ordering & indexes

**Decision**: `ChangeUnit012_SchedulingIndexes` (order **"012"**, off the highest applied `011` — NOT the branch number, the established rule). Native `createIndex` + targeted `dropIndex` rollback (never `dropIndexes()`). Indexes in data-model.md §Indexes.

**Rationale**: Established Mongock conventions (CLAUDE.md). The F00.1-reserved `schedulingTokens {token:1} unique` intent is satisfied by the unique `schedulingRequests {tokenHash:1}` index (token folded into the request doc, hashed — a deliberate, documented consolidation; no separate `schedulingTokens` collection).

**Alternatives**: Separate `schedulingTokens` collection — rejected (an extra collection + join for no benefit; one request doc owns its token).

---

## Dependency Policy note

No new backend or frontend runtime dependency. The in-memory rate limiter (D8) is JDK-only. The Spring `@Scheduled` reaper (D6) reuses the existing scheduling support. No provider SDK (calendar via the existing `CalendarProviderClient`, email via the existing `EmailSender`).
