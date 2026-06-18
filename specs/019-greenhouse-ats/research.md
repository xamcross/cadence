# Phase 0 Research: ATS Integration — Greenhouse (F40)

All four spec-level unknowns were resolved in the `/speckit.clarify` session (2026-06-18): credential = API key, inbound = poll-only, write-back set = scheduling lifecycle + no-show + feedback, stage = raw label. This document records the remaining design-level decisions and the provider facts the `StubGreenhouse` must honor so the adapter is correct when promoted to live credentials.

## D1 — Greenhouse authentication shape

- **Decision**: Workspace-scoped **Greenhouse Harvest API key**, sent as **HTTP Basic auth** with the key as the username and an empty password (the documented Harvest scheme). Stored encrypted at rest on `AtsConnection.apiKey` via the `PiiStringConverter` (the `WorkspaceConfig.emailProviderCredential` precedent). No OAuth app, no refresh.
- **Rationale**: Cheapest/simplest to build (a write-only secret, no token lifecycle); matches the clarified decision and US-F40-1. There is no free candidate-pull path — any live access requires a Greenhouse plan with Harvest API access, a customer-side cost deferred to live promotion.
- **Alternatives considered**: Greenhouse partner OAuth (needs a registered integration app + approval, broader scope, refresh machinery) — rejected for MVP cost/complexity; the `AtsConnector` boundary keeps it swappable later.
- **Verification (`verifyCredential`)**: a single lightweight authenticated GET (e.g. list jobs/users with page size 1). 401/403 → connection not stored as active; the provider body is reduced to a status/category (FR-003).

## D2 — Candidate ↔ application ↔ job → one `atsExternalRef`

- **Decision**: The authoritative external reference is the Greenhouse **application id** (a candidate's instance on a specific job/requisition), not the bare candidate id. `atsExternalRef = "gh_app:" + applicationId`. The job/requisition id + title are denormalized onto the candidate (`atsExternalJobId`, `atsExternalJobTitle`).
- **Rationale**: A Greenhouse candidate can have multiple applications across jobs; the unit Cadence schedules and reports against is one application on one job. Keying on the application id makes "the same person on two jobs" two distinct pipeline records (FR-008: distinct external refs never merge), while `email`/`emailHash` still adopt a pre-existing native candidate when no `atsExternalRef` is set.
- **Alternatives considered**: keying on candidate id (collapses multi-job pipelines — wrong), or a separate `atsRequisitions` collection (YAGNI for F40 — no pipeline view consumes it yet; denormalize instead).

## D3 — Inbound freshness, idempotency, and the resurrection guard

- **Decision**: A `@Scheduled` poll (default `cadence.ats.poll-interval = PT5M`, ≤5 min per FR-009) under `SchedulerCheckpointService` (`"ats-sync-scan"` + `registerReplayAction`). Per workspace: fetch the candidate/application page set (using Greenhouse `If-Modified-Since` / updated-after cursor where available, full page otherwise), normalize, and **upsert guarded on `erasureState = ACTIVE`**. Reconcile precedence: external ref (authoritative) → email-hash (only to adopt a native record with no external ref).
- **Idempotency**: the unique partial `{workspaceId,atsProvider,atsExternalRef}` index makes a re-import an update, and an insert-race a `DuplicateKeyException` caught as the idempotent success (the F22 enqueue precedent). Overlapping polls converge.
- **Resurrection guard (load-bearing GDPR control)**: on erasure we **retain** `atsExternalRef` and wipe only PII. The upsert's update CAS is guarded on `erasureState = ACTIVE`, so an erased record is found by external ref and the update **no-ops** (a non-PII stage update is also suppressed for an erased record); critically, because the ref still resolves, the sync does **not** fall through to *create* a fresh PII-populated record. If `atsExternalRef` were wiped, the next poll would re-create the candidate with full PII from Greenhouse — a resurrection. This is the F04/F31 erasure-vs-async-write race, resolved with the existing atomic guarded-write pattern.
- **Alternatives considered**: webhook ingestion (rejected at clarify — adds a public endpoint/attack surface; poll meets the 5-min SLA); wiping the external ref on erasure (rejected — causes resurrection).

## D4 — Outbound write-back: durability, exactly-once, and the honest bound

- **Decision**: A MongoDB outbox `atsWriteBacks` exactly mirroring `EmailDispatch`: no `@Version`; unique `{workspaceId,idempotencyKey}`; `findAndModify` PENDING→SENDING single-winner claim; insert-then-catch-`DuplicateKeyException` idempotent enqueue; `AtsWriteBackScheduler` drains PENDING-due with bounded backoff; a reaper marks SENDING-stuck rows to an in-flight state.
- **Idempotency anchor / honest bound**: Greenhouse activity-note POST has **no client-supplied dedup key** (unlike Google's deterministic event id / Graph `transactionId`). At-most-once is therefore anchored in the local claim-before-send CAS; the documented honest bound (SC-003) is that a crash between provider-accept and recording DELIVERED leaves a SENDING row reconciled on restart (the F22 `SENT_UNCONFIRMED` reaper) rather than blindly re-sent. A best-effort provider-side de-dup hint (a deterministic note marker text) is sent but not relied upon.
- **Trigger seams**: the six events enqueue best-effort *after* their existing terminal CAS, never blocking or failing the originating flow (the F20 `status_link`/F31 `advanceLastContact` precedent). A non-ATS-linked or erased candidate → no enqueue.
- **Alternatives considered**: synchronous push at the event site (rejected — couples scheduling latency/availability to Greenhouse, violates the resilience FRs); a broker (rejected — §IV).

## D5 — Write-back content & data minimization

- **Decision**: The activity note carries only non-sensitive scheduling facts already known to Greenhouse (event type, interview date/time in the workspace zone, a Cadence reference) — e.g. "Interview scheduled for <date> via Cadence". **No candidate PII, no free-text scorecard, no interviewer assessment** is sent. The feedback write-back signals only that feedback was *submitted* (a completeness signal), never its content.
- **Rationale**: FR-029 data minimization + FR-022 PII discipline; the scorecard `scorecardPayload` is encrypted candidate PII and must never leave Cadence. The outbox row itself holds ids/enums/instants only.

## D6 — Resilience classification & rate limiting

- **Decision**: `AtsApiClassifier` maps Greenhouse responses to TRANSIENT (429, 5xx, network, `Retry-After`) / AUTH-needs-reauth (401, 403 invalid key) / FATAL (other 4xx). `AtsApiRetry` reuses the pure `nextWaitMillis(attempt, retryAfter) = max(backoff+jitter, retryAfter)` (the F11 unit-testable, no-wall-clock pattern). Harvest rate limit (~50 req/10s) is respected by honoring `Retry-After` and by the bulk paginated list (not per-candidate fetches), keeping the burst-of-50 well inside one poll.
- **AUTH outcome** flips the connection to `NEEDS_REAUTH` (pauses sync/write-back, prompts the Admin) — the F01.1 `markNeedsReconnection` analogue; no retry storm on a dead key.

## D7 — Integration-pending delivery (§II)

- **Decision**: `StubGreenhouse` is a JDK `com.sun.net.httpserver.HttpServer` sibling of `StubGoogleCalendar`: method+path matching, per-(method,path) status SEQUENCES (e.g. `503,503,200`), a seeded candidate/application/job store, recorded activity POSTs (for write-back assertions), an injectable `Retry-After`, and a `gate(n)` latch for non-vacuous concurrency. The connection surfaces an **INTEGRATION_PENDING** state until a real base URL/credential is configured. WireMock is NOT used (F01.1 Jackson conflict).
- **Promotion**: live base URL + a real Harvest key (Fly secret `CADENCE_ATS_GREENHOUSE_API_KEY` is *not* used — the key is per-workspace in Mongo; only the live base URL is config) and the mandatory security re-review are a separate, later step (carried into the plan/DoD, not implied complete here).

## D8 — Config & secrets

- **Decision**: `AtsProperties` (`@ConfigurationProperties(prefix="cadence.ats")`): `greenhouse.base-url` (env `CADENCE_ATS_GREENHOUSE_BASE_URL`, defaults to the stub in test), `poll-interval` (PT5M), `connect-timeout`/`read-timeout`, `retry-max-attempts`/`retry-base-backoff`, `sync-page-limit`, `writeback-batch-limit`, `reaper-threshold`, `ops-alert-address`. The per-workspace API key is NOT a global secret — it is stored encrypted in `atsConnections`. The reaper-threshold invariant `> readTimeout + max-backoff` (the F22 invariant) prevents racing a live in-flight claim.

## Open items carried to plan/DoD (not blockers)

- Live-credential promotion + mandatory security re-review (separate step).
- F51 will add HM-requisition scoping over the `atsExternalJobId` denormalization (deferred; no link exists today).
- `ci.yml` PII-scan extended with ATS sentinels (candidate name/email + the API key) and a `greenhouse`-base-URL literal guard on `GreenhouseAtsClient.java` (the F10/F11 precedent).
