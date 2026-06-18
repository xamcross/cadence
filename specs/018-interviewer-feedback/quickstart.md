# Quickstart: Interviewer Feedback Forms & Reminder Escalation (F32)

## Run (local)

```bash
# 1. MongoDB (local dev container)
docker run -d -p 27017:27017 --name cadence-mongo mongo:7

# 2. Backend (applies ChangeUnit017 on startup)
cd backend && ./gradlew bootRun        # JAVA_HOME=C:/jdk-24.0.1; cached gradle-9.4.0; -Dapi.version=1.41

# 3. Frontend
cd frontend && ng serve                # proxy.conf.json routes /api -> :8080
```

## Test (backend)

```bash
cd backend && ./gradlew test           # JUnit 5 + Mockito + Testcontainers (mongo:7)
# F32 package: com.cadence.feedback.*  (unit + integration + MockMvc contract + structural write-only + PII scan)
```

Run flags (Windows, per CLAUDE.md): `JAVA_HOME=C:/jdk-24.0.1`, the cached `gradle-9.4.0` binary, `-Dapi.version=1.41`, `DOCKER_HOST=npipe:////./pipe/docker_engine`. The first multi-class Testcontainers run after a recompile can throw the one-time `GenericContainer` class-init error — re-run.

## Test (frontend — the public scorecard page carries the §IX gate)

```bash
cd frontend
ng test --watch=false                  # Jasmine + axe-core: scorecard-page 0 WCAG 2.2 AA violations across states
ng build --configuration production
npx @lhci/cli autorun --config=../lighthouserc.json   # /feedback?token=lighthouse-demo via serve-with-stub.mjs; Performance >= 85
```

## Demo — the §II end-to-end leg (browser → DB → member mail)

1. **Seed an occurred interview**: a `SchedulingRequest` with `status=BOOKED`, `bookedStartAt` in the past (older than `generationDelay`, default 3h), two ACTIVE `InterviewSlotClaim` rows (two interviewers), `feedbackGeneratedAt=null`.
2. **Run the scan** (`FeedbackScheduler.sweep()` fires on its interval, or invoke directly in a test): it CAS-stamps `feedbackGeneratedAt`, creates one `feedbackRequests` row per interviewer (`PENDING`, hashed token, `nextReminderDueAt = now + submissionDeadline`), and sends each interviewer a `FEEDBACK_REQUEST_ID` operational email (stub/real transport) with their unique `/feedback?token=…` link.
3. **Interviewer (no login)** opens `/feedback?token=…` → blank scorecard (recommendation radios + rating inputs + comment). The page never shows prior content; the token lives only in memory.
4. **Submit** → `POST /api/feedback/{token}` → CAS `PENDING→SUBMITTED`, `scorecardPayload` encrypted at rest (raw-driver read returns ciphertext), `submittedAt` set, reminders stop.
5. **Recruiter** on `/scheduling` opens the feedback-status panel for the interview → sees interviewer A `SUBMITTED` (reads the decrypted scorecard) and interviewer B `PENDING`.
6. **Reminder escalation**: leave B unsubmitted; advance the test clock past the deadline → L1 reminder email; +24h → L2; +24h → L3; stop at max 3. Submitting B at any point stops further reminders.
7. **Deactivated interviewer**: deactivate B before a reminder → the request goes `UNCOLLECTIBLE`, B's link 404s (`USED`), and the workspace Admins/Recruiters get a `FEEDBACK_UNCOLLECTIBLE` alert.
8. **Erase the candidate** → all the candidate's `PENDING` feedback requests go `INVALIDATED`, `scorecardPayload` cleared, tokens dropped (links → `USED`), no further reminders; submitted scorecards' content is wiped.

## Verify the invariants

| Invariant | How to see it |
|---|---|
| Idempotent generation (FR-003/SC-003) | Run `sweep()` twice → still one `feedbackRequests` row + one email per interviewer (unique `{interviewEventId, interviewerMemberId}` + `feedbackGeneratedAt` CAS). |
| Write-only token (FR-017/SC-008) | `GET /api/feedback/{token}` after submission → `{"state":"USED"}`, no content; only `GET /api/internal/interviews/{id}/feedback` (ADMIN/RECRUITER) returns scorecards. |
| STATUS-before-TIME (FR-030/SC-023) | Erased/invalidated/used/unknown tokens all return byte-identical `USED`; only a past-TTL PENDING returns `EXPIRED`. |
| Reminder escalation (FR-012/SC-004/SC-020) | Reminder count + distinct level markers under `MutableClock`; two overlapping sweeps at the same level → one send (per-`{request,level}` CAS). |
| Encryption at rest (FR-028) | Raw `mongosh` read of a SUBMITTED `feedbackRequests` doc shows `scorecardPayload` as ciphertext; erasure sets it null. |
| No PII in logs/dead-letter/audit (FR-028/FR-029/SC-014) | `SENTINELF32*` seeded into scorecard text / merge fields → absent from logs, dead-letter, audit, and the row; a forced render failure's dead-letter carries only the cause class. |
| RBAC + no-oracle (SC-010/SC-011) | 5-role matrix on the recruiter read (ADMIN/RECRUITER 200; HM/Interviewer/Read-only 403); cross-workspace id → indistinguishable 404. |
| §IX public page | `ng test` axe 0 violations; `@lhci/cli` Performance ≥85 on `/feedback`. |
| No auto-read / no-voice (SC-019) | No token-routed handler serves scorecard content (route inventory); no voice/transcription capability exists. |
