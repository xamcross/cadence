# API Contracts: Flow A4 — No-Show Defense (F23)

Two new HTTP endpoints (one candidate-facing public-by-token, one recruiter internal) plus a reuse of the existing F03 workspace-config update for the two cascade settings, plus the internal cascade SPI (no HTTP). All times are absolute `Instant`s on the wire; the candidate payload is times-only (no participant identity, no location). Error envelope reuses the F13/F20 `SchedulingExceptionHandler` shape.

---

## A. Candidate — confirm attendance (public-by-token)

Rides the existing `@Order(2)` permitAll / STATELESS chain (no session, no `@PreAuthorize`; the confirm token IS the auth — the `/api/candidate/` prefix is allow-listed in `RbacEndpointInventoryTest`). Rate-limited per IP (429). The booking is resolved **solely** from the credential (FR-008, no IDOR); the request body is ignored for target resolution. `Cache-Control: no-store`. Added to `CandidateBookingController`.

### A1. `POST /api/candidate/booking/{confirmToken}/confirm`

Affirmative, state-changing confirm (never a GET — a prefetch/scanner following a link MUST NOT confirm, FR-006).

**Path**: `confirmToken` — the raw confirm token from the `REMINDER_24H` email's `{{confirm_link}}`.

**200 OK** (confirmed, or idempotent replay of an already-confirmed booking):
```json
{ "status": "confirmed", "bookedStart": "2026-06-20T09:00:00Z", "zoneId": "Europe/Prague", "at": "2026-06-19T09:03:11Z" }
```

**Responses** (the F20 candidate-link precedence, no oracle — evaluated **status before time**):
| Order | Condition | Status | Body |
|---|---|---|---|
| 1 | Unknown / cleared / superseded confirm token | `400` | `{error:"invalid"}` |
| 2 | Resolved but **not `BOOKED`** (cancelled / recruiter-released / rescheduled-away) | `400` | `{error:"invalid"}` — byte-identical to #1 |
| 3 | `BOOKED` but `chosenStart` already passed | `410` | `{error:"expired"}` — distinct expired experience |
| 4 | Confirmed now / already confirmed (replay) | `200` | `{status:"confirmed", bookedStart, zoneId, at}` |
| — | Rate limit exceeded | `429` | `{error:"rate_limited"}` |

> **Precedence is load-bearing (no oracle)**: status-not-`BOOKED` → 400 is checked **before** the past-start → 410, so a rescheduled-away (now-`RESCHEDULED`) parent whose old start is in the past yields the indistinguishable **400**, never a distinguishable 410. The past check uses the in-memory `chosenStart(row)` (the `viewBooking`/`cancelByBooking` source), not the sweep-only `bookedStartAt`. A contract test asserts byte-identical 400 across {unknown, released-`CANCELLED`, erased (token `$unset`), `SUPERSEDED`}.
>
> **CSRF**: the confirm `POST` rides the `@Order(2)` permitAll/**STATELESS** chain — no session cookie, the path token is the sole authenticator, so no CSRF token is required (a forged cross-site POST without the secret cannot confirm). Any *helpful* state messaging is shown only on an **authenticated** state page (the F20 `GET /api/candidate/booking/{token}` read surface), never on this bare confirm response (FR-018).

---

## B. Recruiter — one-tap release of an unconfirmed slot (internal)

`@PreAuthorize("hasAnyRole('ADMIN','RECRUITER')")` (class-level on `SchedulingController`), workspace-scoped (an out-of-workspace candidate/booking returns the same indistinguishable scoped 404 as a non-existent one — FR-020, no cross-workspace oracle). A sibling of the existing `/cancel` and `/reschedule` on `SchedulingController` (`@RequestMapping("/api/internal/candidates/{candidateId}/scheduling")`). **Reuses the existing F20 `SchedulingService.cancelByRecruiter(workspaceId, actorMemberId, candidateId, ip)`** verbatim (D6) — no new resolution/refusal code.

### B1. `POST /api/internal/candidates/{candidateId}/scheduling/release`

Releases the candidate's authoritative BOOKED interview (the no-show recovery action). Idempotent / single-winner vs a concurrent confirm/cancel/reschedule (FR-013).

**200 OK**:
```json
{ "status": "cancelled", "at": "2026-06-20T07:05:00Z", "cleanupIncomplete": false }
```

**Responses**:
| Condition | Status | Body |
|---|---|---|
| Released (events removed, slot freed, candidate notified) | `200` | `{status:"cancelled", at, cleanupIncomplete:false}` |
| Released but a provider event could not be removed after retries | `200` | `{status:"cancelled", at, cleanupIncomplete:true}` (recruiter alerted; surfaced orphan, FR-012) |
| No active BOOKED booking for this candidate | `409` | `{error:"no_active_booking"}` |
| Interview start already passed (can't release a past interview) | `409` | `{error:"ineligible"}` |
| Booking outside the caller's workspace / unknown candidate | `404` | scoped-not-found (indistinguishable) |
| Caller lacks ADMIN/RECRUITER | `403` | `{error:"forbidden"}` |

> The recruiter "Release slot" affordance in an alert email (if any) MUST land on this authenticated in-app action — it MUST NOT execute the destructive release on a bare link-GET (FR-011).

---

## C. Admin — per-workspace cascade settings (reuses F03 workspace-config update)

The two settings ride the **existing** workspace-config update surface (`WorkspaceConfigService`, `@PreAuthorize("hasRole('ADMIN')")`). No new endpoint; the request DTO and `GET` config response gain two optional fields.

**Fields** (on the existing workspace-config update/read):
```json
{ "confirmationLeadTime": "PT24H", "unconfirmedEscalationDeadline": "PT2H" }
```

| Condition | Status | Body |
|---|---|---|
| Valid (`0 < escalation < lead ≤ cascadeQueryBound`, both positive) | `200` | updated config (Durations echoed) |
| `escalation ≥ lead`, non-positive, or `lead > cascadeQueryBound` | `400` | `{error:"invalid_config", field:"unconfirmedEscalationDeadline"\|"confirmationLeadTime"}` — prior settings retained (FR-014) |
| Caller not ADMIN | `403` | `{error:"forbidden"}` |

Omitted/`null` fields ⇒ the `NoShowProperties` global defaults apply (FR-015), no migration.

---

## D. Internal cascade SPI (no HTTP) — `NoShowDefenseScheduler` → `NoShowCascadeService`

Not user-facing; the `@Scheduled` cascade contract (D1/D8), exercised by tests via a test clock.

- `sweep()` — wrapped in `SchedulerCheckpointService.start/complete("no-show-cascade")`; registered as the `@PostConstruct` replay action (missed-fire-safe). Runs three `Pageable`-capped stage finders, Java-filters per-workspace offsets, drives each row through its stage CAS. Idempotent across overlapping sweeps and a mid-task restart (per-stage CAS + F22 outbox key) — duplicates impossible; a crash between stage-1 CAS and reminder-enqueue loses at most one reminder, caught by stage-2 escalation (D8, the honest bound).
- `requestConfirmation(booking, now)` → stage-1 CAS; contactable ⇒ mint `confirmTokenHash` + `dispatch.enqueue(ws, candidateId, REMINDER_24H, "BASE", now, {confirm_link, interview_date, interview_time, time_zone, location, stage_name}, null)`; not contactable ⇒ set `confirmationNotRequestable`.
- `escalateUnconfirmed(booking, now)` → stage-2 CAS + `notifications.notify(ws, candidateId, INTERVIEW_UNCONFIRMED)` + audit `NOSHOW_UNCONFIRMED_ESCALATED`.
- `stampNoShow(booking, now)` → stage-3 CAS (sets `noShowAt`).
- `confirmAttendance(rawConfirmToken, ip)` → rate-limit; resolve by `confirmTokenHash`; CAS set `candidateConfirmedAt`; audit `NOSHOW_ATTENDANCE_CONFIRMED` + candidate-audit `BOOKING_CHANGED/ATTENDANCE_CONFIRMED`; 410/400/429 per A1.

---

## Cross-cutting

- **No PII / no token value** in any response beyond the opaque path token; times-only candidate payload; `no-store` on candidate responses (FR-019/FR-022).
- **Confirm token** ≥ 128-bit entropy (reuses 256-bit `SecureTokens` + HMAC `TokenHasher`), hashed at rest, single-use for the positive confirm, expires at interview start (FR-017).
- **Email-only** (FR-023): the cascade dispatches `REMINDER_24H` via the F22 channel and one in-app `INTERVIEW_UNCONFIRMED`; no SMS/WhatsApp path exists (SC-012). No waitlist-invite endpoint.
