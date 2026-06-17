# Quickstart: Flow A4 — No-Show Defense (F23)

How to run, test, and demonstrate the no-show confirmation cascade end to end. Reuses the F13/F20 scheduling stack, the F22 email channel, and the F10/F11 provider stubs.

## Prerequisites

- A local MongoDB (`docker run -p 27017:27017 mongo:7`) for `./gradlew bootRun`; tests use Testcontainers (`mongo:7`, no Atlas).
- Run flags (the cached toolchain, zero-download C7): `JAVA_HOME=C:/jdk-24.0.1`, the cached `gradle-9.4.0` binary, `-Dapi.version=1.41`, `DOCKER_HOST=npipe:////./pipe/docker_engine`.
- A confirmed single-stage booking (F13/F20): recruiter sends a scheduling link → candidate picks a slot → `schedulingRequests` row is `BOOKED` with a `bookedStartAt`.

## Run

```powershell
# backend (cascade runs on the @Scheduled fixed delay; advance via test clock in tests)
.\gradlew.bat bootRun   # com.cadence.scheduler.NoShowDefenseScheduler sweeps every cadence.noshow.cascade-interval-ms

# frontend (candidate confirm page + recruiter release action)
cd frontend; ng serve
```

Config (all defaulted; per-workspace overrides on `WorkspaceConfig`):
```yaml
cadence:
  noshow:
    confirmation-lead-time: PT24H     # global default; per-workspace override wins
    escalation-deadline: PT2H
    cascade-interval-ms: 60000
    cascade-query-bound: PT72H        # global upper bound for the indexed sweep (>= any workspace lead time)
    cascade-sweep-batch-limit: 200
  scheduling:
    spa-confirm-base-path: /confirm
```

## Demonstrate (the §II end-to-end leg)

1. **Confirmation request**: with a booking whose start is < 24 h out (or advance the test clock to the lead-time boundary), the cascade dispatches one `REMINDER_24H` email to the candidate (consent-gated) carrying a `Confirm attendance` link → `…/confirm?token=<raw>`. The booking shows `confirmationRequestedAt` set.
2. **Candidate confirms**: open the confirm link (no login, mobile, local time zone) → tap "Confirm attendance" → `POST /api/candidate/booking/{token}/confirm` → 200 `confirmed`. The booking shows `candidateConfirmedAt`; no recruiter escalation is raised.
3. **Unconfirmed path**: with a booking left unconfirmed, advance the clock past the escalation deadline (default 2 h before start) → one `INTERVIEW_UNCONFIRMED` recruiter notification; the booking shows `escalatedAt`.
4. **One-tap release**: as a recruiter, `POST /api/internal/scheduling/{candidateId}/release` → calendar events removed for all participants (against the F10/F11 stubs), the slot released and immediately re-selectable, booking `CANCELLED`, audit `SCHEDULING_CANCELLED`.
5. **No-show signal**: a booking that reaches its start unconfirmed gets `noShowAt` stamped (the F50 data point).

## Test

```powershell
.\gradlew.bat test    # backend: JUnit 5 + Testcontainers
cd frontend; ng test --watch=false   # confirm page: Jasmine + axe-core
```

Key tests (Test-First, one per user story + the backlog E2E):
- **Cascade timing** (`@Primary MutableClock`, never wall-clock): stage 1 fires exactly one `REMINDER_24H` at the lead-time boundary; stage 2 one `INTERVIEW_UNCONFIRMED` at the deadline; stage 3 stamps `noShowAt` at start (SC-001/003/011). The scheduler MUST read the injected `Clock` (QA fix).
- **Start passes mid-sweep** (test clock): a booking whose start crosses `now` → stage 2 does NOT escalate a past interview (predicate `bookedStartAt > now`); stage 3 stamps `noShowAt` instead (spec edge).
- **DST** (synthetic DST-crossing fixture + test clock): the lead/deadline offsets fire at the correct wall-clock instant across a transition (no hour drift), asserted on the absolute fire `Instant` (SC-013).
- **Idempotency / missed fire** (Testcontainers, test clock): a simulated mid-task restart replays without a duplicate reminder or duplicate escalation (SC-006); the **lost-reminder safety-net** — mock `dispatch.enqueue` to throw **after** the stage-1 CAS commits, advance to the deadline, assert stage 2 still escalates on `confirmationRequestedAt != null` (D8).
- **Reschedule resets the cascade** (FR-003): confirm round 1 → reschedule → assert the round-2 booking gets a **fresh** `REMINDER_24H` (not suppressed by the F22 outbox key — distinct `scheduledFor`) and round-1's `candidateConfirmedAt` does not satisfy the new time.
- **Confirm** (MockMvc + Testcontainers): affirmative POST records once; replay is a no-op; a **GET does not confirm** (FR-006); **status-before-time precedence** — byte-identical 400 across {unknown, released-CANCELLED, erased, SUPERSEDED}, 410 only for a still-`BOOKED` past interview, 429 (SC-008); IDOR — a confirm token cannot act on another booking (FR-008); **multiple bookings per candidate** — two distinct confirm tokens, confirm A ⇏ confirm B.
- **Confirm-after-release ordering** (US2 AC#3): candidate confirms after the escalation alert but before release → booking reads confirmed; the recruiter view is not pushed to release.
- **Concurrency** (gated `@RepeatedTest` with a `CountDownLatch` released after both threads resolve the booking but **before** they CAS — so they genuinely collide): two releases, or release racing a confirm/cancel, yield one authoritative transition (SC-007).
- **Config** (Testcontainers): non-default offsets shift the cascade; `escalation ≥ lead` rejected; **`lead > cascadeQueryBound` rejected** + a lead set **at** the bound is still swept; defaults apply with no admin action (SC-011); the cross-field validator runs after `wsValue ?? default` resolution.
- **Contactability + erasure**: a not-contactable candidate gets no reminder but still escalates via the **same coarse** alert (SC-009, no oracle); erasure **between a stage-1 CAS and the outbox dispatch** → **zero email leaves the transport** (the F22 send-time re-gate) and the confirm token is unusable (SC-009, Security fix).
- **E2E** (Testcontainers test clock, F10/F11 stubs — NOT Playwright, C7): scheduled fire → reminder → unconfirmed → **assert persisted `escalatedAt` on the booking row** → recruiter alert → release → slot available in MongoDB → audited (SC-005).
- **Frontend** (Jasmine + axe-core): confirm page 0 WCAG 2.2 AA violations across states (loading/confirm/confirmed/expired/invalid), no-login, local time zone, `$localize`, no token in storage/console (SC-002).
- **PII/log scan + scope guard**: no candidate PII or token value across cascade/confirm/escalate/release (SC-010), with `SENTINELF23*` sentinels in `ci.yml`, plus a CI grep banning `sms|whatsapp|twilio|waitlist` literals (SC-012).
- **Partial-unique `{confirmTokenHash}`**: two cleared rows do not collide on null (the F01 `write=NON_NULL` footgun).
