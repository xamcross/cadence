# Quickstart: SLA Nudge Engine (F31)

## Run locally

```powershell
# 1. MongoDB (local dev container) — if not already running
docker run -d --rm -p 27017:27017 --name cadence-mongo mongo:7

# 2. Backend (cached gradle-9.4.0, installed JDK — no downloads, C7/Principle X)
cd C:\Users\xamcr\Cadence\backend
.\gradlew.bat bootRun   # ChangeUnit016 applies the slaNudgeDrafts index on startup

# 3. Frontend
cd C:\Users\xamcr\Cadence\frontend
ng serve                # /scheduling carries the recruiter SLA panel
```

## Tests

```powershell
# Backend — JUnit 5 + Testcontainers (set JAVA_HOME=C:/jdk-24.0.1, -Dapi.version=1.41,
# DOCKER_HOST=npipe:////./pipe/docker_engine; re-run once if the first multi-class
# Testcontainers run throws the one-time GenericContainer class-init error)
cd C:\Users\xamcr\Cadence\backend
.\gradlew.bat test --tests "com.cadence.sla.*"
.\gradlew.bat test --tests "com.cadence.migration.IndexBootstrapTest" `
                   --tests "com.cadence.api.RbacEndpointInventoryTest"

# Frontend
cd C:\Users\xamcr\Cadence\frontend
ng test --watch=false
```

## Demo walkthrough (browser → DB — the §II leg)

1. **Set the silence window** (Admin → Workspace settings): `slaSilenceWindowDays = 5`. (Reuses the existing F03 settings screen/endpoint — no new UI.)
2. **Seed a silent candidate**: a candidate whose `lastContactAt` is 6+ days ago (older than the window) and who has email-channel consent, is not erased, not undeliverable, and is not in a terminal outcome. (In a test, stamp `lastContactAt` into the past with the `MutableClock`.)
3. **Run the scan**: wait for the `@Scheduled` sweep (`cadence.sla.scan-interval`) or trigger `SlaNudgeScheduler.sweep()` in a test. Exactly **one** OPEN `slaNudgeDraft` is created and a `SLA_DRAFT_PENDING` recruiter notification is recorded. Running the sweep again creates **no** second draft (unique partial index).
4. **Recruiter view** (`/scheduling`, enter the candidate id): the SLA badge shows **RED**; the pending draft appears.
5. **Preview**: the rendered `SLA_HOLDING` message shows the candidate's name, the F30 `{{status_link}}`, and the expected date; any missing field is a visible warning (`no-store`, never logged).
6. **Approve**: exactly one `SLA_HOLDING` email is enqueued through the consent-gated channel and dispatched (stub/real); `lastContactAt` advances → the badge returns to **GREEN** and the candidate leaves the silence list. The email links to the candidate's existing F30 status page.
7. **Dismiss instead** (on a fresh breach): the draft is removed and **no** email is sent; the candidate can be re-drafted on a future breach.

## What to verify (acceptance → SC)

- Repeated/overlapping sweeps never create a 2nd draft or 2nd notification (SC-003).
- Approve dispatches exactly one email and clears the breach; dismiss sends zero (SC-004).
- An erased / no-consent / withdrawn / over-retention / undeliverable candidate is **never** drafted, even past the window (SC-005); approving a draft for a since-erased candidate is **REFUSED at send** (SC-015).
- A mid-scan restart + checkpoint replay produces no duplicate draft/email (SC-006).
- No candidate name, email, recipient address, or status-link token appears in logs, the dead-letter record, the audit entry, or the `slaNudgeDrafts` document — driven with `SENTINELF31*` sentinels (SC-007).
- **No `@Scheduled`/system path sends an SLA message** — only the recruiter approve endpoint enqueues `SLA_HOLDING` (SC-008, call-graph test).
- Boundary + DST-crossing classification is deterministic under the test clock in the workspace zone (SC-009).
- Concurrent approve of one draft → at most one dispatched email (SC-010).
- Each of the five qualifying activities advances `lastContactAt` (SC-014).
- A cross-workspace draft id returns an indistinguishable 404; preview carries `no-store` (SC-016).
- A terminal-outcome (offer/rejection) candidate is not auto-drafted (SC-012).

## Notes / gotchas (from real source)

- **`lastContactAt` is the canonical instant** (research D1): it was dormant (set only at candidate creation). F31 advances it at five sites via `CandidateActivityService.advanceLastContact`. Do not add a sibling field — the `{workspaceId,lastContactAt}` index already backs the scan.
- **No new email template/token/type** — `EmailMessageType.SLA_HOLDING` + its built-in body + `MergeTokenCatalogue` (`STATUS_LINK`,`EXPECTED_DATE`) already exist. Touching them would have to move atomically with the F21 `@PostConstruct`/`BuiltInTemplateCompletenessTest`.
- **Approve must route through `EmailDispatchService.enqueue`** — never build/send mail directly, or the authoritative send-time consent gate is bypassed (FR-016/FR-023).
- **Mongock order is "016"** off the highest applied "015" — never renumber an applied changeset. New-collection index → no dedupe-before-index needed; pure-ASCII comments (the F30 NUL-byte lesson).
- **Internal screen** — the recruiter SLA panel on `/scheduling` is internal: Lighthouse/WCAG are N/A (F50/F51 precedent). F31 ships **no new candidate-facing page**.
- **Test-clock** — drive breach timing by stamping `lastContactAt`/the `MutableClock`, never wall-clock sleeps (the F23 lesson).
