# Quickstart: Candidate Status Page (F30)

End-to-end walkthrough for running, testing, and demoing F30. Assumes the F00–F23 stack builds (see CLAUDE.md run flags: `JAVA_HOME=C:/jdk-24.0.1`, cached `gradle-9.4.0`, `-Dapi.version=1.41`, `DOCKER_HOST=npipe:////./pipe/docker_engine`).

## Run

```powershell
# Backend (needs a local mongo:7 for manual dev)
docker run -d -p 27017:27017 mongo:7
.\gradlew bootRun           # applies ChangeUnit015 on startup

# Frontend
cd frontend; ng serve       # proxies /api to the backend
```

## Demo (the §II / §IX leg — browser to database)

1. **Recruiter publishes a status.** Sign in as Admin/Recruiter → open a candidate → in the new **Status** panel set Stage = "Onsite interview", Next step = "We're collecting interviewer feedback", Expected date = (a near-future date) → Save. (Try Save with the date blank → rejected, value-free 400 — the "no dateless we'll-be-in-touch" rule, SC-004.)
2. **Recruiter copies the status link** from the panel (or the candidate receives it: the post-booking `CONFIRMATION` email now carries `{{status_link}}`).
3. **Candidate opens the link** (`/status?token=...`) in a fresh browser — **no login**. The page shows the branded header (workspace logo + colour), the stage, the plain-English next step, the expected date in their local presentation, and a contact route.
4. **Recruiter updates the status** (change next step / date) → candidate **reloads** → the page reflects the change (SC-005, the backlog E2E).
5. **Past the expected date**: with a controlled clock (test) or by setting the date in the past, the page renders the honest "we're past the expected date, your stage is X" framing — not the stale promise (FR-017/SC-013).
6. **Candidate requests deletion**: tap "Request data deletion" → on-page acknowledgement. As Admin, open the erasure-requests queue → the request is **pending Admin confirmation** (no immediate wipe, SC-008). Tap it again as the candidate → still one pending request (idempotent).
7. **Rotate**: recruiter taps "Rotate link" → the old link now shows the generic not-found page (indistinguishable from an unknown token, SC-011); the new link works.

## Test

```powershell
# Backend — full suite (Testcontainers)
.\gradlew test

# Targeted F30
.\gradlew test --tests "com.cadence.status.*"

# Frontend — Jasmine + axe (status page across all displayStates)
cd frontend; ng test --watch=false

# Lighthouse (mobile, blocking >=85) against the real /status route via the stub
cd frontend; node lighthouse/serve-with-stub.mjs   # then: npx @lhci/cli autorun --config=../lighthouserc.json
# Stub matches canned status payloads by DISTINCT demo token in the path (the lighthouse-demo-reschedule precedent):
#   /status?token=lighthouse-demo            -> PUBLISHED (the content-heavy state; the SC-002 perf gate URL)
#   /status?token=lighthouse-demo-terminal   -> TERMINAL   (covered by axe/Jasmine; optional perf URL)
#   /status?token=lighthouse-demo-review     -> UNDER_REVIEW (covered by axe/Jasmine)
# Add ONLY the PUBLISHED URL to lighthouserc.json ci.collect.url[] (one perf URL; numberOfRuns:3) — a11y is the
# authoritative axe gate, so terminal/under-review need not triple CI perf time.
```

## What to verify (acceptance → test mapping)

| Spec | Verified by |
|---|---|
| SC-001 first-paint stage/next-step/date @375px | Jasmine `candidate-status.component.spec` (no-scroll, accessible names) |
| SC-002 < 2 s / Lighthouse ≥ 85 | `lighthouserc.json` `/status?token=lighthouse-demo` (median of 3, performance error gate) |
| SC-003 axe 0 violations, 375/768/1280, long/RTL | `axe.ts` per-state specs + RTL overflow spec |
| SC-004 dateless/contentless publish refused | MockMvc publish 400 (in-progress missing date / blank next-step) |
| SC-005 reflected on next load | MockMvc: publish v1 → GET → assert v1; publish v2 (changed) → GET → assert v2 (no stale) — the backlog reflect-on-reload E2E leg; + Jasmine reload |
| FR-016 concurrent edits | 2-writer integration test (F21 `concurrentFirstEdit`/F13 race precedent): single consistent status, no partial state |
| SC-006 no PII / no token in logs/doc | `NoShowLogPiiScan`-style `StatusLogPiiScanTest` (SENTINELF30*) + raw-driver ciphertext |
| SC-007 view no-oracle | MockMvc byte-identical 404 across {unknown, malformed, erased} |
| SC-008 erasure routed, idempotent, no immediate wipe | Testcontainers: request → 1 PENDING; repeat → still 1; candidate still ACTIVE until Admin confirm |
| SC-009 rate-limit 429 | MockMvc: the (`rateLimitPerMinute`+1)th call/min → 429. NOTE the **test profile sets `rate-limit-per-minute: 5`** (`application-test.yml`), so the 6th call 429s (prod is 10 → 11th). Do not hard-code "11th". |
| SC-010 erasure-submit no-oracle | MockMvc identical 202 ack across {valid, unknown, erased} |
| SC-011 rotation invalidates old | Testcontainers: rotate → old hash 404, new resolves |
| SC-012 transport controls | MockMvc `Cache-Control: no-store`; FE `_headers` grep (no-referrer + CSP) |
| SC-013 past-date framing | unit `displayState` with controlled clock + workspace zone |
| SC-014 audit on every change | Testcontainers audit append assertion (publish, rotate) |
| SC-015 free-text escaped | Jasmine: markup-laden nextStep renders inert (interpolation, no innerHTML) |
| SC-016 displayState precedence | unit matrix (terminal+past, under-review+past, etc.) |

## Deploy (after merge to `main`)

New Mongock changeset (`ChangeUnit015`) ⇒ backend + DB:
```powershell
scripts\db-migrate.ps1        # verify Atlas reachable
scripts\deploy-all.ps1        # backend (Mongock applies on startup) + frontend
```
No new Fly secret (no new provider/credential). The status link base path is config (`cadence.status.spa-status-base-path`, default `/status`).
