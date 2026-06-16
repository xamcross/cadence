# Quickstart: Flow A1 — Single-Stage Scheduling (F13)

## Prerequisites

- Local MongoDB (`docker run -p 27017:27017 mongo:7`) for `./gradlew bootRun`; tests use Testcontainers (no cloud creds).
- Run flags (CLAUDE.md): `JAVA_HOME=C:/jdk-24.0.1`, cached gradle-9.4.0 binary, `-Dapi.version=1.41`, `DOCKER_HOST=npipe:////./pipe/docker_engine`.
- F01–F22 are merged on `main` (this branch builds on them).

## Build & test

```powershell
# Backend (JUnit 5 + Testcontainers)
cd C:\Users\xamcr\Cadence\backend
..\gradlew.bat test -Dapi.version=1.41           # full suite incl. com.cadence.scheduling.*
# Frontend
cd C:\Users\xamcr\Cadence\frontend
ng test --watch=false
ng build
# E2E (Playwright) — recruiter initiate -> candidate book
```

## Demo walkthrough (the §II browser→DB leg)

1. Sign in as a Recruiter; ensure at least one interview template (F12) exists whose required interviewers have connected calendars (F10/F11), and a candidate (F04) with recorded contact consent.
2. On the candidate, click **Send scheduling link** (choose template, optional location text). → `POST /api/internal/candidates/{id}/scheduling` → slots computed + snapshotted, invitation email enqueued, status chip shows **Link sent**.
3. Open the invitation email link (the local SMTP sink / `RecordingMailTransport` in tests, or MailHog in dev) → the Angular `/schedule?token=...` page lists available times in the candidate's zone (no login).
4. Pick a slot → `POST /api/candidate/scheduling/{token}/confirm` → calendar events created for all participants (visible on the stub/provider), candidate + participant confirmation emails sent, page shows confirmation.
5. Back as the Recruiter, the candidate status chip shows **Scheduled** with the chosen time.

## Key test scenarios (acceptance-driven, constitution §VII)

| Spec ref | Test | Type |
|---|---|---|
| FR-012 / SC-003 | **Gated** concurrent confirm of the same interviewer-time across two requests → exactly one `BOOKED`, the other `409 slot_taken`, one `ACTIVE` claim | Integration (latch) |
| FR-013 | Confirm after an interviewer's calendar went busy → `409 slot_no_longer_available` + remaining slots; pool re-selection binds a still-free member | Integration |
| FR-014 | Candidate erased between send and confirm → booking refused, no event; live request superseded, claims released | Integration |
| FR-015/016 / SC-004 | Mixed-provider panel, one provider create fails → full rollback, zero orphans (stub residual store); cleanup-delete failure → `CLEANUP_INCOMPLETE` + alert | Integration |
| FR-008/010 / SC-007 | Expired token → 410; used/unknown/superseded → byte-identical 400; >10 req/min/IP → 429 | Contract |
| FR-009/019 | Reopen booked link → existing confirmation, no 2nd event/email; replayed confirm → no-op | Integration |
| FR-011 | Candidate slot payload contains no participant id/name (seed ids, assert absent) | Contract (non-circular) |
| FR-017 | Request stuck in `BOOKING`, clock advanced past threshold, reaper runs → claims released, back to `PENDING_SELECTION` | Integration (test clock) |
| SC-005 | Slot booked across a DST boundary → recorded calendar event body carries correct offset + IANA zone | Integration (DST fixture) |
| SC-006 | Full initiate→book→confirm with PII + token sentinels → zero matches in logs/audit/persisted docs | Integration (PII scan) |
| FR-001..005 | 5-role initiate matrix; no_slots; unschedulable required member | Contract |

Determinism: inject `java.time.Clock` (test `MutableClock`); set `calendar.api.retry-base-backoff: PT0S`; drive crash/expiry windows by stamping `updatedAt`/`expiresAt`, never wall-clock sleeps. Concurrency tests use a `gate(n)` latch (non-vacuous).

## CI

`ci.yml` PII scan extended with `SENTINELF13CANDIDATE_zz9` + `SENTINELF13TOKEN_zz9` + an interviewer-identity sentinel asserted absent from the candidate slot payload. No new Windows scripts (C5 N/A).
