# Quickstart: Flow A3 — Reschedule & Cancellation (F20)

How to run, test, and demonstrate F20 end-to-end. F20 extends F13; the same run flags apply.

## Prerequisites

- Local MongoDB (`docker run -p 27017:27017 mongo:7`) for `bootRun`; tests use Testcontainers (`mongo:7`).
- Run flags (per CLAUDE.md): `JAVA_HOME=C:/jdk-24.0.1`, the cached `gradle-9.4.0` binary (never the wrapper download — Principle X/C7), `-Dapi.version=1.41`, `DOCKER_HOST=npipe:////./pipe/docker_engine`.
- A connected interviewer calendar + an interview template + a confirmed F13 booking (the starting state F20 operates on). The F10/F11 provider **stubs** (`StubGoogleCalendar`/`StubGraphCalendar`) stand in for real providers in tests.

## Build & test

```powershell
# Backend — full suite incl. the new com.cadence.scheduling.* reschedule/cancel/recovery/erasure/IDOR tests
cd backend ; <cached-gradle> test -Dapi.version=1.41

# Frontend — Jasmine + axe (candidate reschedule + cancel pages: 0 WCAG 2.2 AA violations)
cd frontend ; ng test --watch=false ; ng build --configuration production

# Lighthouse — the LHCI stub route list extended to the reschedule/cancel candidate routes (blocking, F20 owns its gate)
cd frontend ; npx @lhci/cli autorun --config=../lighthouserc.json
```

## Demo 1 — Candidate self-service reschedule (US1, the §II leg)

1. Start from a confirmed booking (F13). The candidate's confirmation email contains a **"Reschedule or cancel"** link (`/booking?token=<manageToken>`).
2. Open the link (no login). The booking-manage page shows the current time (candidate's zone) + "Reschedule" / "Cancel".
3. Click **Reschedule** → `POST /api/candidate/booking/{manageToken}/reschedule` opens a round and returns fresh slots (excluding the current time). The reschedule slot-picker (the reused F13 page) renders them.
4. Pick a new slot → confirm. Verify against the stubs: **old events cancelled** on all attendees' calendars, **new events created** at the new time, updated invites + a fresh confirmation (with a new manage token) dispatched, status → `RESCHEDULED` on the old round / `BOOKED` on the new.
5. Inspect `authAuditLog`: one `SCHEDULING_RESCHEDULED` + one candidate `BOOKING_CHANGED`, value-free (no token, no PII).

## Demo 2 — Candidate cancel (US3)

1. From the same manage link, click **Cancel** and confirm (an affirmative POST — a bare GET never cancels).
2. Verify: calendar events removed for all participants, the slot **released** (immediately re-selectable by a fresh computation), the recruiter notified (`INTERVIEW_CANCELLED_BY_CANDIDATE`), candidate sees a respectful confirmation. Audit: `SCHEDULING_CANCELLED` + `BOOKING_CHANGED`.

## Demo 3 — Recruiter reschedule / cancel (US2)

1. As a Recruiter, open the candidate and click **Reschedule** → `POST /api/internal/candidates/{id}/scheduling/reschedule`. The existing booking stays `BOOKED`; the candidate receives a fresh reschedule invitation; the recruiter sees **"Reschedule in progress."**
2. The candidate picks a new time (Demo 1 path) → the booking swaps; recruiter status → `RESCHEDULED`.
3. **Cancel** from the recruiter view removes the events and notifies the candidate (consent-gated).

## What to verify (acceptance gates)

- **Original preserved on failure (SC-003)**: force a stub failure on the new-event create — the reschedule rolls back and the **original booking + its events remain intact and valid**.
- **Atomic single-winner (SC-004)**: the gated concurrent test (reschedule-vs-cancel, double-confirm) yields exactly one committed action, no double-booking, no split state.
- **Recovery (FR-023)**: kill mid-swap; on restart the reaper rolls **forward** if the child reached `BOOKED` (cancels the parent) or **back** if it did not (releases the child, parent stands).
- **Erasure (SC-009)**: erase a candidate with a BOOKED interview — `wipe()` synchronously CASes the booking to CANCELLED + releases claims + clears the manage token + sets `calendarTeardownPending`; the next reaper pass removes the provider events (zero residual events). The erasure ack stays O(1)/non-blocking; PII-free audit survives unmodified.
- **Cap (SC-007)**: after the configured reschedules (default 3), self-service is refused + recruiter notified; recruiter reschedule still works.
- **No oracle / no PII (SC-008/SC-010)**: 410 expired vs byte-identical 400 invalid; log scan finds zero token values and zero candidate PII across the full reschedule/cancel flow.
- **§IX (FR-020)**: axe-core 0 violations + Lighthouse ≥ 85 on the reschedule and cancel candidate routes; all strings `$localize`; no PII/token in URL.
