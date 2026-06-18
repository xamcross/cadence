# Cadence MVP Product Backlog

**Version**: 1.2.0 | **Prepared**: 2026-06-13 | **Scope**: MVP (v1) per spec §11 and constitution v1.1.0

All items below are within §11 MVP scope. Items outside that scope are marked **DEFERRED** and may not be started without a constitution amendment.

> **Multi-role review completed**: 2026-06-13. Reviewers: Business Analyst, QA Lead, Security/GDPR Lead, Backend/DevOps Lead.
> Findings applied inline. OD-1 resolved 2026-06-13: no video calls in MVP; FR-7 (meeting-link generation) deferred to v1.5.

---

## How to Read This Backlog

- **Feature ID**: `F##` (two-digit; sub-items use `F##.#`)
- **Priority**: P0 = blocking prerequisite; P1 = critical path; P2 = required MVP; P3 = MVP polish
- **Spec ref**: Section in `cadence-product-specification.md`
- **Constitution gates**: C1–C6 pre-checked; any failing gate noted as `[GATE FAIL]`
- Each feature must have an approved `spec.md` + `plan.md` before implementation tasks begin (constitution §Development Workflow)

---

## Tier 0 — Foundation (P0): Blocks All Other Work

### F00 — Project Scaffold & Build Pipeline
**Spec ref**: §5.3, §6, constitution §Stack & Deployment Constraints

Angular 17 standalone-component SPA + Spring Boot 3.x single JAR + MongoDB Atlas (7.x).
Production targets: frontend on **Cloudflare Pages**, backend on **Fly.io** (single Machine), database on **MongoDB Atlas** (single-region M10+).
Local dev uses a Docker MongoDB container (via Testcontainers for tests; standalone `docker run` for manual dev).
Includes observability baseline and graceful-shutdown configuration (see F00.1, F00.2 sub-items).

**Constitution gates**: C1 ✅ C2 ✅ C3 ✅ C4 ✅ C5 ✅ C6 ✅

**User stories**:
- US-F00-1: As a developer, I can `./gradlew bootRun` (with a local MongoDB Docker container running) to start the backend so that I have a working dev environment in one command.
- US-F00-2: As a developer, I can `ng serve` to start the Angular SPA locally pointing at the local backend.
- US-F00-3: As a developer, running `./gradlew test` executes JUnit 5 + Testcontainers integration tests against an ephemeral MongoDB container (no Atlas dependency in tests).
- US-F00-4: As a developer, `ng test` runs Jasmine unit tests.
- US-F00-5: As a developer, I can run `fly deploy` to deploy a new backend image to Fly.io so that releases are a single command.
- US-F00-6: As a developer, pushing to `main` triggers a Cloudflare Pages build and deploys the Angular SPA automatically.

**Acceptance criteria**:
- Spring Boot app starts, hits `/actuator/health` (management port only), returns 200.
- Angular SPA compiles with zero TypeScript errors, serves on localhost:4200.
- Testcontainers MongoDB integration test round-trips a document (uses ephemeral container, NOT Atlas, so tests run without cloud credentials).
- `Dockerfile` at `backend/Dockerfile` builds a runnable image (`docker build` + `docker run` succeeds, health check passes). File MUST use LF line endings per `.gitattributes` (Principle V).
- `fly.toml` at repo root (or `backend/fly.toml`) configures: single Machine, correct internal port, health check path `/actuator/health`, `[env]` block with no secrets inlined (secrets managed via `fly secrets set`).
- Cloudflare Pages build settings configured: build command `ng build --configuration production`, output directory `dist/cadence`, environment variable `API_BASE_URL` set per environment.
- All runtime secrets (Atlas connection string, email provider key, OAuth credentials, JWT signing key) stored as Fly.io secrets; zero secrets committed to source or `fly.toml`.
- CI pipeline includes a Lighthouse mobile-preset job; fails if any candidate-facing route scores < 85.
- `server.shutdown=graceful` with 30-second drain timeout configured; verified by test.
- All new `.ps1`/`.cmd`/`.bat` scripts pass byte-level non-ASCII scan + parse (Principle V).

---

### F00.1 — MongoDB Index Bootstrapping
**Spec ref**: constitution §III, §IV

A startup migration (Mongock or `@PostConstruct`) that creates all production indexes before the application accepts traffic. Each feature's `plan.md` must declare which indexes it depends on.

**Required indexes (minimum at project start)**:
- `interviews: { scheduledAt: 1, confirmationStatus: 1 }` — no-show cascade queries (F23)
- `candidates: { workspaceId: 1, lastContactAt: 1 }` — SLA breach scanner (F31)
- `feedbackRequests: { interviewEventId: 1, submittedAt: 1 }` — reminder escalation (F32)
- `schedulingTokens: { token: 1 }` (unique) — token lookup
- `auditLog: { candidateId: 1, occurredAt: -1 }` — audit queries (F04)
- `schedulerCheckpoints: { taskName: 1 }` (unique) — @Scheduled idempotency (see F00.2)

**Acceptance criteria**:
- All indexes created idempotently at startup; verified by integration test that asserts `db.runCommand({listIndexes: ...})` output.
- No feature's @Scheduled task queries a collection without a covering index. *(Backend: missing indexes were the #1 MongoDB performance risk identified in review.)*

---

### F00.2 — Observability & @Scheduled Job Infrastructure
**Spec ref**: constitution §IV (@Scheduled rule), §VIII (no PII in logs)

Structured JSON logging (Logback), `/actuator/health` + `/actuator/metrics` on a management-only port, and a shared `SchedulerCheckpoint` MongoDB document pattern that all `@Scheduled` tasks must use to achieve idempotency and missed-fire recovery.

**@Scheduled idempotency contract** (applies to F22, F23, F31, F32):
- Before performing work, each task reads its `SchedulerCheckpoint` to find the last-run window.
- On start, each task writes `{ status: "RUNNING", startedAt: ... }` to its checkpoint.
- On completion, writes `{ status: "COMPLETED", completedAt: ... }`.
- On startup, the app replays any checkpoints in `RUNNING` state that are older than a configurable threshold (default: 15 minutes), treating them as missed fires.
- Each email dispatch is guarded by a unique `idempotencyKey` (`candidateId + eventType + scheduledAt`) with a MongoDB unique index, making duplicate sends a no-op.

**Acceptance criteria**:
- A simulated mid-task restart (kill -9 during a job) followed by restart does NOT send duplicate emails. Verified by integration test.
- Structured logs contain no plaintext PII; CI log-grep confirms zero matches for email/name patterns.
- `/actuator/metrics` and `/actuator/health` return 200 on the management port; return 404 on the public port.
- A dead-letter monitor alerts via `EmailSender` if a `@Scheduled` task throws an uncaught exception.

---

### F01 — Authentication & Session Management
**Spec ref**: §5.4 (FR-19), §6 Security, constitution §VIII

SSO (SAML/OIDC) as the primary login path; email/password as MVP fallback only. JWT tokens issued by the backend, verified on every request. No anonymous access to internal endpoints. Candidate-facing endpoints are whitelisted as public (no auth required).

> **MVP scope decision (2026-06-13, spec 002-authentication)**: SSO for the MVP is **OIDC only**; **SAML 2.0 is deferred to v1.5** (see Deferred table). OIDC satisfies the constitution's "SSO is primary" requirement and matches this entry's only SSO acceptance test (Keycloak/OIDC). The AC below "Token refresh is handled by the `OAuthTokenStore` (see F01.1)" refers to **calendar** OAuth tokens (F01.1), not the member application session; member session handling is owned by F01 itself.

**Constitution gates**: C1 ✅ C2 ✅ C3 ✅ C4 ✅ C5 ✅ C6 ✅

**User stories**:
- US-F01-1: As a workspace member, I can log in via SSO (OIDC/SAML) so that my corporate IdP is the source of truth.
- US-F01-2: As a workspace member with no SSO configured, I can log in with email + password (bcrypt-hashed, rate-limited) so that MVP access is unblocked.
- US-F01-3: As the system, all candidate-facing pages (scheduling, status, feedback forms) work without authentication so that candidates never create accounts.
- US-F01-4: As a developer, the Spring Security filter chain rejects unauthenticated requests to `/api/internal/**` with HTTP 401; `/api/candidate/**` endpoints are explicitly whitelisted and authenticate via private token instead. *(BA DI-3: explicit whitelist required to prevent accidental auth-gate on candidate token endpoints.)*

**Acceptance criteria**:
- OIDC login with a test IdP (e.g., Keycloak local Docker) produces a valid JWT and loads the dashboard.
- Email/password is NOT the default workspace login button (SSO is primary per §VIII).
- Candidate scheduling/status/feedback pages return 200 with no auth cookies; `/api/candidate/**` validates the token from the URL path, not a session.
- No plaintext password or PII in any log line (verified by log-grep in CI).
- Token refresh is handled by the `OAuthTokenStore` (see F01.1).

---

### F01.1 — OAuth Token Store
**Spec ref**: constitution §VIII (encryption at rest), §III (CalendarProvider interface)

A `OAuthTokenDocument` MongoDB collection storing per-user Google and Microsoft OAuth refresh tokens, encrypted via MongoDB CSFLE (or application-level AES-256). Token refresh is managed by the `CalendarProvider` adapter; tokens are never logged.

**Acceptance criteria**:
- Refresh tokens are stored encrypted; reading the raw MongoDB document returns ciphertext.
- Token refresh flow is tested with an expiry-simulation unit test (mock token that expires in 1 s).
- Zero occurrences of `access_token`, `refresh_token`, or `client_secret` patterns in application logs across the full OAuth exchange flow (CI log-grep). *(Security ISSUE-4.)*

---

### F02 — Role-Based Access Control (RBAC)
**Spec ref**: §5.4 (FR-19), §6 Security, constitution §VIII

Five roles: **Admin**, **Recruiter**, **Hiring Manager**, **Interviewer**, **Read-only**. Enforced on every API endpoint via Spring Security method security (`@PreAuthorize`). Role assignment stored in MongoDB; changeable by Admin.

**Constitution gates**: C1 ✅ C2 ✅ C3 ✅ C4 ✅ C5 ✅ C6 ✅

**User stories**:
- US-F02-1: As an Admin, I can assign and change roles for workspace members. *(Spec 003-rbac, 2026-06-14: under the MVP "one role per member" model there is no revoke-to-null state; "revoke" is modelled as reassignment/downgrade, e.g. to Read-only. Reworded from "assign and revoke" to avoid implying a revoke-to-no-role workflow.)*
- US-F02-2: As a Recruiter, I can access scheduling, templates, and dashboard views but NOT workspace-level configuration.
- US-F02-3: As a Hiring Manager, I can confirm or decline a proposed interview slot sent to me by Cadence so that my availability is captured before the candidate is invited. *(BA SG-1: HM slot-confirmation was missing from original user stories.)*
- US-F02-4: As a Hiring Manager, I can view interview details and the candidate status page for my own assigned candidates but NOT the full pipeline.
- US-F02-5: As an Interviewer, I can view only my own upcoming interviews and submit feedback.
- US-F02-6: As a Read-only user, I can view pipeline and analytics data but take no actions.

**Acceptance criteria**:
- API contract tests verify correct role enforcement (403 for disallowed roles) on every `POST`/`PATCH`/`DELETE` endpoint.
- Angular route guards redirect unauthorized users to a `/not-authorized` page, not a 404.
- Jasmine unit tests cover each route guard: correct role passes, each disallowed role redirects correctly. *(QA test-type-gap: route guard unit tests were missing.)*
- Hiring Manager's data access is filtered server-side to their assigned requisitions (not client-side filtering).

---

### F03 — Workspace Setup & Configuration
**Spec ref**: §5.4 (FR-20), constitution §VIII (data retention)

Workspace initial setup wizard and ongoing admin configuration: branding, working hours, time zone, SLA defaults, data-retention period (default displayed and acknowledged during setup), template governance, and email-sending domain.

**Constitution gates**: C1 ✅ C2 ✅ C3 ✅ C4 ✅ C5 ✅ C6 ✅

**User stories**:
- US-F03-1: As an Admin, I can complete a workspace setup wizard (name, logo, time zone, working-hours, data-retention period) so that the workspace is ready to use. *(Spec 004-workspace-config, 2026-06-14: logo is captured in the ongoing branding surface (US-F03-2), not the first-run wizard, since branding is non-blocking — a documented default brand applies until set. Also: §5.4 FR-20 "languages" is **deferred** for the MVP (single-language EN); per-workspace default-language selection and candidate-facing localization are owned by F14/F21. Stakeholder-confirmed.)*
- US-F03-2: As an Admin, I can update branding (logo, brand colour) so that candidate-facing pages match our brand.
- US-F03-3: As an Admin, I can set the default SLA silence window (in days).
- US-F03-4: As an Admin, I can configure the email-sending domain and provider API key in workspace settings so that emails are delivered from our domain. *(BA SG-4: email config moved here from F22 where it was misplaced.)*
- US-F03-5: As an Admin, I can mark specific email templates as "locked" so that Recruiters cannot edit them.

**Acceptance criteria**:
- Data-retention period is displayed and must be acknowledged during workspace setup (GDPR gate).
- When a candidate record's age exceeds the workspace retention period, the system flags it for Admin review, blocks new outbound communications to that candidate, and requires explicit Admin confirmation before permanent deletion (shares the same wipe mechanism as F04 erasure). *(Security ISSUE-10: data retention must be enforced, not display-only.)* **[Scope note 2026-06-14, spec 004-workspace-config]**: the *enforcement* (age-flag/comms-block/Admin-confirmed wipe) **binds in F04**, not F03 — the outbound-comms path (F22) and the shared wipe mechanism (F04) do not exist at F03 time, so building it in F03 would require a stub (§II) or pulling F22/F04 forward (§I YAGNI). F03 ships the configured retention period, the mandatory GDPR acknowledgment, and the binding policy contract F04 consumes. Stakeholder-confirmed.
- Workspace settings persisted to MongoDB; survive restart.
- Email-sending domain config is tested end-to-end in F22.

---

### F04 — GDPR Baseline: Consent, Erasure & Audit Log
**Spec ref**: §6 Privacy & compliance, constitution §VIII

Email-channel consent recorded per candidate on first communication. Admin/Recruiter-triggered and candidate-initiated right-to-erasure flows. Full audit log of every message, booking, and change per candidate (FR-18). Erasure and consent state are checked by the `EmailSender` before every dispatch (enforced in F22).

**Constitution gates**: C1 ✅ C2 ✅ C3 ✅ C4 ✅ C5 ✅ C6 ✅

**User stories**:
- US-F04-1: As the system, I record email-channel consent (timestamp, legal basis) on the candidate record before sending the first communication.
- US-F04-2: As an Admin or Recruiter, I can trigger erasure of a candidate's personal data; the erasure event is written to the audit log.
- US-F04-3: As a Candidate, I can submit an erasure request via my status page link so that my right to erasure can be exercised without contacting the recruiter directly; the request is routed to an Admin for confirmation. *(BA SG-3: GDPR Art. 17 requires a data-subject-initiated path.)*
- US-F04-4: As an Admin, I can view the full audit log for a candidate (all messages, scheduling events, status changes).
- US-F04-5: As a developer, no test or CI run writes plaintext PII to application logs.

**Acceptance criteria**:
- Erasure API (Admin/Recruiter-triggered and candidate-request path) replaces name/email/phone fields with `[ERASED]` and writes an immutable audit record.
- Erasure API returns `202 Accepted` within 2 s; async completion recorded in audit log. *(QA performance gap: erasure must not block on large audit logs.)*
- Audit log entries are append-only (no `DELETE` path exists for audit records).
- CI log-grep step finds zero PII patterns in test output.

---

## Tier 1 — Critical Path (P1): Core Scheduling Value

### F10 — Calendar Integration: Google Calendar
**Spec ref**: §5.1 (FR-1), constitution §III (free/busy-only scope default)

Bidirectional: read free/busy; write/delete/update calendar events. OAuth 2.0 per-user consent, free/busy-only scope by default. Wrapped behind `CalendarProvider` interface. OAuth tokens stored encrypted via F01.1.

**Constitution gates**: C1 ✅ C2 ✅ C3 ✅ C4 ✅ C5 ✅ C6 ✅

**User stories**:
- US-F10-1: As a Recruiter/Hiring Manager/Interviewer, I can connect my Google account to Cadence (OAuth consent, free/busy scope) so that my availability is read correctly.
- US-F10-2: As the system, when a slot is confirmed, I create a Google Calendar event for all participants with the correct title, time zone, and location/dial-in details provided by the recruiter (no auto-generated video link — deferred to v1.5).
- US-F10-3: As the system, when a booking is cancelled or rescheduled, I delete/update the corresponding Google Calendar event automatically.

**Acceptance criteria**:
- OAuth flow completes; token stored encrypted via F01.1 (not logged; CI log-grep confirms zero `access_token` matches).
- Free/busy query for a 5-person panel returns in < 5 s.
- Calendar event appears on attendees' calendars within 10 s of slot confirmation.
- No calendar event content from unrelated meetings (titles, attendees, body) is read, stored, or logged at any time.
- A slot booked 1 h before a DST boundary renders at the correct wall-clock time on all attendees' calendars; verified by integration test with a synthetic DST-crossing fixture. *(QA edge-case: DST transitions are a known silent failure mode.)*
- On `429` or `503` from the Google Calendar API, the adapter retries with exponential backoff and jitter (max 3 retries); integration test stubs a 429 and asserts correct retry behaviour. *(Backend: rate-limit backoff not mentioned in original backlog.)*
- If Google Calendar event creation fails after retries during a booking, the booking is rolled back atomically (no partial state). *(QA edge-case: partial calendar failure.)*

---

### F11 — Calendar Integration: Microsoft 365 / Outlook
**Spec ref**: §5.1 (FR-1), constitution §III (Microsoft Graph API)

Same functional scope as F10 via Microsoft Graph. Uses `MicrosoftCalendarAdapter` implementing `CalendarProvider`.

**Constitution gates**: C1 ✅ C2 ✅ C3 ✅ C4 ✅ C5 ✅ C6 ✅

**Graph permission scope** (must be documented in `plan.md`): Use `/users/{id}/calendar/getSchedule` for free/busy under `Calendars.Read` with strict `$select=start,end,isAllDay` field projection — no subject, body, or attendee fields requested or stored. This is the spec-documented justification per constitution §VIII. *(Security ISSUE-2: Microsoft Graph's `Calendars.Read` returns full event content if not field-projected; must be explicit.)*

> **Erratum (2026-06-15, spec/plan 008-microsoft-calendar — supersedes the paragraph above)**: two corrections from the F11 spec+plan, both reviewed: (1) **Scope is `Calendars.ReadWrite`** (plus `openid profile email offline_access`), not `Calendars.Read` — the §11 MVP mandates **bi-directional** sync (event create/update/delete), which read-only `Calendars.Read` cannot do, and Graph has **no** owned-events-only delegated write scope (so it is necessarily broader than F10's Google `calendar.events.owned`; §VIII justification approved in `008-microsoft-calendar/plan.md`). The `openid profile email` scopes yield the id_token whose email/UPN is the mailbox address `getSchedule` requires. (2) **`$select=start,end,isAllDay` is NOT the §VIII control** — `getSchedule` has no event-field `$select`, and on the caller's **own** mailbox it can return `subject`/`location`; the no-content guarantee is therefore enforced by **parse-discipline** (the adapter reads only `start`/`end`/`status` via explicit JSON path reads) and **verified by a non-circular test** (seed content into `scheduleItems`, assert absence), not by a structural field projection. See `008-microsoft-calendar/{spec,research,plan}.md`.

**User stories**:
- US-F11-1: As a Recruiter/Hiring Manager/Interviewer on Microsoft 365, I can connect my Outlook calendar via OAuth so that my availability is read correctly.
- US-F11-2: As the system, when a slot is confirmed for a Microsoft 365 participant, I create an Outlook calendar event via Microsoft Graph with title, time zone, and recruiter-provided location details (no auto-generated meeting link — deferred to v1.5).
- US-F11-3: As the system, reschedules and cancellations propagate to the Microsoft 365 calendar automatically.

**Acceptance criteria**:
- OAuth scope is limited to free/busy field-projected query only; no event titles, attendee lists, or body text stored at any log level. *(Security ISSUE-1, ISSUE-2.)*
- OAuth refresh token stored encrypted via F01.1; CI log-grep confirms zero token values in logs.
- Mixed Google + Microsoft 365 teams can be scheduled in a single flow without errors.
- If one provider's event creation fails after retries, the entire booking is rolled back (no orphaned calendar events on the other provider). *(QA edge-case: mixed-provider partial failure.)*
- Free/busy responses normalised to the same internal `TimeSlot` model as F10's adapter.
- DST boundary integration test (same as F10, verified for Microsoft time zone handling). *(QA.)*
- Rate-limit backoff (same policy as F10). *(Backend.)*

---

### F12 — Interview Template & Rule Engine
**Spec ref**: §5.1 (FR-2), §4 Flow A1 step 1

Recruiter-created interview stage template: duration, required/optional participants, panel composition ("any 2 of pool X"), buffer before/after, daily interview cap, blackout periods, time-zone handling. Used by the scheduler at booking time.

**Constitution gates**: C1 ✅ C2 ✅ C3 ✅ C4 ✅ C5 ✅ C6 ✅

**User stories**:
- US-F12-1: As a Recruiter/Admin, I can create an interview stage template (duration, participants, buffers, caps) so that repeated interview types don't need to be reconfigured each time.
- US-F12-2: As the rule engine, I apply the template rules when computing available slots so that only genuinely compliant slots are offered.
- US-F12-3: As an Admin, I can set workspace-wide defaults (working hours, time zone) that templates inherit unless overridden.

**Acceptance criteria**:
- Rule engine unit tests cover: buffer enforcement, daily cap, "any N of pool" selection, time-zone normalisation, DST-adjacent slot generation.
- A template with `max 2 interviews/day` for an interviewer does not offer a third slot on the same day.
- API contract test (MockMvc) verifies slot-computation endpoint shape (response body, status codes, error envelope) against the shape expected by F14's Angular consumer. *(QA test-type-gap: contract test was missing.)*

---

### F13 — Flow A1: Single-Stage Scheduling
**Spec ref**: §4 Flow A1, §5.1 (FR-1, FR-2, FR-3, FR-5)

The end-to-end scheduling flow: Recruiter selects candidate + template → Cadence computes compliant slots → candidate receives self-scheduling email → candidate picks slot → Cadence atomically reserves the slot → books calendar events → dispatches confirmations.

> **Open Decision OD-1**: Flow A1 references attaching a "video-call link" to calendar events (§4 step 4) but FR-7 (meeting-link generation for Google Meet / Teams / Zoom) is listed in the backlog's deferred table as "To assess." Implementing F13 end-to-end without a meeting link is technically possible (location field can say "TBD" or use a static URL), but spec §4 implies a link. **This must be resolved before F13 implementation begins.** Options: (a) include a single provider (Teams/Meet) in F10/F11 scope; (b) accept "link TBD — recruiter adds manually" for MVP; (c) add FR-7 to MVP scope via constitution amendment. See Open Decisions.

**Constitution gates**: C1 ✅ C2 ✅ C3 ✅ C4 ✅ C5 ✅ C6 ✅

**User stories**:
- US-F13-1: As a Recruiter, I can initiate scheduling for a candidate by selecting a template and clicking "Send scheduling link" in one action.
- US-F13-2: As a Candidate, I receive a branded email with a self-scheduling link; clicking it opens a mobile-friendly slot-picker showing only available, rule-compliant times; I can book without creating an account.
- US-F13-3: As the system, once the candidate picks a slot, all participants receive a calendar invite (with recruiter-provided location/dial-in details) and a confirmation email within 30 s. No video link is generated automatically.
- US-F13-4: As a Recruiter, I can see the scheduling status of each candidate (link sent, slot picked, confirmed) on the pipeline view.

**Acceptance criteria**:
- **Atomic slot reservation**: The slot confirmation uses `updateOne({ _id: slotId, status: "AVAILABLE" })` as an atomic write (MongoDB `findAndModify` or transaction); the slot transitions to `HELD` before any calendar event is created. If two candidates submit the same slot simultaneously, exactly one receives a confirmation and the other receives a `409 Conflict` with a user-facing "slot already taken" message and a prompt to pick another. Verified by a concurrent integration test (two simultaneous POST requests to the same slot). *(QA edge-case + Backend: double-booking race condition.)*
- **Token security**: Scheduling link token is a cryptographically random 128-bit value (URL-safe base64, not derived from candidate ID or email); stored hashed in MongoDB; the URL contains only the token — no PII in query parameters; token generation event is audit-logged with candidateId and timestamp (no token value in log). *(Security ISSUE-9.)*
- End-to-end Cypress/Playwright test: recruiter initiates → test-candidate follows link → picks slot → all invites sent → pipeline status updated.
- Scheduling page Lighthouse Performance >= 85 on mobile simulation.
- Slot-picker page is WCAG 2.2 AA (axe-core: 0 violations).
- No candidate login or app install required at any step.
- Email confirmations depend on F21 (Template Library) and F22 (Email Delivery) being complete. F13 backend (slot computation + reservation) may ship first; "confirmation email dispatch" closes only after F22 is ready. *(BA DI-1: F22 is a P1 dependency of F13's full close.)*

---

### F14 — Candidate Scheduling Page (UX)
**Spec ref**: §5.1 (FR-3), §6 Accessibility, constitution §IX

Candidate-facing Angular slot-picker. Mobile-first, WCAG 2.2 AA, < 2 s on 4G. No login. All strings externalised via Angular `$localize`.

**Constitution gates**: C1 ✅ C2 ✅ C3 ✅ C4 ✅ C5 ✅ C6 ✅

**User stories**:
- US-F14-1: As a Candidate, the scheduling page loads in < 2 s on a 4G connection.
- US-F14-2: As a Candidate using a screen reader, all interactive elements have correct ARIA labels and a logical tab order.
- US-F14-3: As a Candidate on a 375 px mobile screen, slot cards are touch-friendly (>= 44 px tap targets) and readable without horizontal scroll.
- US-F14-4: As a Candidate, the page displays slot times in my local time zone with clear DST-adjusted labelling.

**Token & expiry requirements** (applies equally to F20, F30, F32):
- Scheduling token TTL: 72 hours from link send (configurable per workspace).
- An expired token returns HTTP 410 Gone with a user-facing message ("This link has expired — contact your recruiter"); distinct from a used/invalid token (400).
- Token is invalidated (single-use) on first slot confirmation (F13).
- Token validation endpoint is rate-limited to 10 requests/minute per IP; repeated invalid attempts return 429.
*(Security ISSUE-3, QA security-gap.)*

**Acceptance criteria**:
- Lighthouse Performance >= 85 (mobile simulation).
- axe-core: 0 WCAG 2.2 AA violations.
- Page renders correctly at 375 px, 768 px, and 1280 px.
- All UI strings use `$localize` or Angular i18n markers.
- Expired-token path renders a helpful user-facing message (not a 404 or 500 error).

---

## Tier 1 — Critical Path (P1): Email Infrastructure

*(Promoted from P2 — these are blocking dependencies for F13's full close.)*

### F21 — Email Template Library
**Spec ref**: §4 Pillar B, §5.2 (FR-9), constitution §IX

Template library: invitation, confirmation, reminder (24 h, 1 h), hold/update, rejection, feedback request. Merge fields (`{{candidate_name}}`, `{{date}}`, `{{link}}`), tone presets, per-stage variants. Admins can lock templates.

**Constitution gates**: C1 ✅ C2 ✅ C3 ✅ C4 ✅ C5 ✅ C6 ✅

**User stories**:
- US-F21-1: As an Admin, I can view and edit the default template library so that all outgoing emails match our brand voice.
- US-F21-2: As a Recruiter, I can preview a rendered template with a specific candidate's merge fields so that I can verify the message before dispatch.
- US-F21-3: As a Recruiter, I can approve a draft SLA message with one click (MVP: no auto-send).
- US-F21-4: As an Admin, I can mark a template as "locked" to prevent Recruiter edits.

**Acceptance criteria**:
- All merge fields render correctly; missing fields produce a visible warning (not a broken `{{variable}}`).
- Jasmine unit tests cover: merge-field rendering with missing fields produces visible warning; locked template prevents Recruiter edit action; preview renders correctly with sample data. *(QA test-type-gap: frontend unit tests were missing.)*
- Templates stored in MongoDB; changes are version-tracked in the audit log.
- No email is dispatched via F22 without either recruiter one-click approval OR a system-event trigger (calendar confirmation); auto-send for SLA messages is deferred to v1.5.

---

### F22 — Email Delivery Channel
**Spec ref**: §5.2 (FR-12, email only for MVP), constitution §III, §IV

Transactional email via Spring Mail + provider SDK (e.g., SendGrid or SES). Provider wrapped behind `EmailSender` interface. Scheduled reminders use `@Scheduled` + `SchedulerCheckpoint` (F00.2 pattern). The `EmailSender` checks consent and erasure state before every dispatch.

**Constitution gates**: C1 ✅ C2 ✅ C3 ✅ C4 ✅ C5 ✅ C6 ✅

**User stories**:
- US-F22-1: As the system, transactional emails are delivered reliably via the configured email provider within 60 s of the trigger.
- US-F22-2: As the system, scheduled reminder emails use the `@Scheduled` + `SchedulerCheckpoint` pattern (F00.2) so that no queue broker is needed and missed fires are replayed correctly.
- US-F22-3: As a developer, swapping from SendGrid to SES requires only changing the `EmailSender` bean — no service code changes.

**Acceptance criteria**:
- `EmailSender` checks `{ consentRecorded: true, erasureStatus: false }` on the candidate record before dispatching any outbound email; a missing or erased record throws a checked exception, writes a dead-letter record (candidateId only, no PII), and notifies the Recruiter in-app. *(Security ISSUE-6: consent-check before dispatch.)*
- Hard bounces are recorded on the candidate record and surfaced in the pipeline view; Recruiter receives an in-app notification for any hard bounce. *(BA AC-4: email is the sole channel — bounce handling is critical.)*
- Provider SDK dependency declared in `plan.md` with one-line justification (constitution §Dependency Policy).
- No personal data (email address) in application logs; only anonymised candidate ID.
- Integration test sends to a test inbox (or SMTP mock) and records delivery status.
- Swapping provider requires only a bean change (interface test verifies this).

---

## Tier 2 — Required MVP (P2): Resilience & Communication

### F20 — Flow A3: Reschedule & Cancellation
**Spec ref**: §4 Flow A3, §5.1 (FR-5)

Either side can trigger reschedule via a single link. Cadence re-runs slot computation, re-books calendar events, notifies all parties, logs the change.

**Constitution gates**: C1 ✅ C2 ✅ C3 ✅ C4 ✅ C5 ✅ C6 ✅

**User stories**:
- US-F20-1: As a Candidate, I can click "Reschedule" in my confirmation email and pick a new slot without contacting the recruiter.
- US-F20-2: As a Recruiter, I can trigger a reschedule on behalf of any party from the pipeline view.
- US-F20-3: As the system, when a reschedule is confirmed, old calendar events are cancelled and new ones created; all parties receive updated invites within 30 s.
- US-F20-4: As a Candidate, I can cancel an interview via the same link, triggering slot release and a recruiter notification.

**Acceptance criteria**:
- Reschedule token TTL: 72 hours from send; single-use after new slot confirmed (see F14 token requirements). *(Security ISSUE-3.)*
- A scheduling token may be used for reschedule no more than N times (configurable, default 3); on breach, the recruiter is notified and the link is invalidated. *(BA AC-2: abuse prevention.)*
- Old calendar events appear as cancelled on all attendees' calendars.
- Audit log records: who initiated the reschedule, old slot, new slot, timestamp.
- Required E2E test: Candidate clicks Reschedule → picks new slot → old calendar events cancelled → new invites sent → audit log updated. *(QA: reschedule E2E was missing.)*

---

### F23 — Flow A4: No-Show Defense
**Spec ref**: §4 Flow A4, §5.1 (FR-6)

Confirmation cascade: 24 h before interview → email confirmation request; if unconfirmed → recruiter alert with one-tap slot release. Email-only (no SMS/WhatsApp in MVP). Uses `@Scheduled` + `SchedulerCheckpoint` pattern (F00.2).

**Constitution gates**: C1 ✅ C2 ✅ C3 ✅ C4 ✅ C5 ✅ C6 ✅
**Deferred**: SMS/WhatsApp nudge step → v1.5.

**User stories**:
- US-F23-1: As the system, 24 h before an interview I send the candidate a confirmation email; if unconfirmed within the configurable window, I notify the recruiter.
- US-F23-2: As a Recruiter, I receive an alert with a one-tap option to release the slot.
- US-F23-3: As the system, a released slot is marked available again.
- US-F23-4: As a Recruiter, I can configure the confirmation window per workspace.

**Acceptance criteria**:
- `@Scheduled` task uses `SchedulerCheckpoint` (F00.2): idempotent, missed-fire-safe. *(Backend: @Scheduled idempotency required.)*
- `@Scheduled` task fires the confirmation email at the correct time; verified by unit test with a mocked clock.
- Integration E2E test (test-clock injection): full cascade from scheduled task fire → confirmation email sent → unconfirmed state → recruiter alert → slot released → slot available in MongoDB. *(QA: no-show E2E was missing; unit mock alone is insufficient.)*
- No-show rate visible on the core dashboard after the interview window passes.
- SMS nudge step is NOT present in MVP codebase.

---

## Tier 2 — Required MVP (P2): Candidate Experience

### F30 — Candidate Status Page
**Spec ref**: §4 Pillar B, §5.2 (FR-11), constitution §IX

Private-link page (no login, no app): current stage, plain-English next-step description, expected date. "We'll be in touch" with no date is prohibited. Mobile-first, WCAG 2.2 AA.

**Constitution gates**: C1 ✅ C2 ✅ C3 ✅ C4 ✅ C5 ✅ C6 ✅

**User stories**:
- US-F30-1: As a Candidate, I can open my private status page link and see my current stage, a plain-English next-step description, and an expected date.
- US-F30-2: As a Recruiter, I can update the candidate's expected date and next-step text from the pipeline view.
- US-F30-3: As a Candidate, if the process is complete (offer or rejection), the status page reflects that honestly.
- US-F30-4: As a Candidate, I can submit a right-to-erasure request via the status page (routed to F04). *(BA SG-3: candidate-initiated erasure path surfaces here.)*

**Token requirements**: Status page token is valid for the candidate's lifecycle (not time-limited like scheduling tokens); token is bound to the candidate record and invalidated on erasure. Candidate cannot guess or derive another candidate's token. Rate-limit applies as per F14. *(Security ISSUE-3.)*

**Acceptance criteria**:
- Backend rejects an update that omits expected date (`stage + nextStep + expectedDate` all required).
- "We'll be in touch" text without a date is blocked by validation.
- Status page loads in < 2 s on mobile; Lighthouse Performance >= 85.
- axe-core: 0 WCAG 2.2 AA violations.
- Status page URL contains only the token — no candidateId or PII in the URL path. *(Security ISSUE-9 pattern.)*
- Required E2E test: status page accessed via private token → displays current stage + expected date → Recruiter updates stage → page reflects change on reload. *(QA: status page E2E was missing.)*
- Token validation endpoint is rate-limited to 10 requests/minute per IP.

---

### F31 — SLA Nudge Engine
**Spec ref**: §4 Pillar B, §5.2 (FR-10), constitution §IX

Admin-defined silence rules; breach detection via `@Scheduled` + `SchedulerCheckpoint` (F00.2); draft messages queued for recruiter one-click approval. Auto-send deferred to v1.5.

**Constitution gates**: C1 ✅ C2 ✅ C3 ✅ C4 ✅ C5 ✅ C6 ✅
**Deferred**: Auto-send SLA policies → v1.5.

**User stories**:
- US-F31-1: As an Admin, I can define an SLA silence rule (e.g., "max 5 days without a candidate update").
- US-F31-2: As the system, when an SLA threshold is approaching, I draft a holding message for recruiter approval.
- US-F31-3: As a Recruiter, I see an amber/red indicator on the pipeline dashboard for candidates nearing or exceeding the SLA.
- US-F31-4: As the system, a drafted SLA message is NOT dispatched until the recruiter approves it.

**Acceptance criteria**:
- SLA nudge drafting is suppressed for candidates in erased or no-consent state (checked against F04 consent record). *(Security ISSUE-6.)*
- `@Scheduled` SLA checker uses `SchedulerCheckpoint` (F00.2): idempotent, missed-fire-safe. *(Backend.)*
- Integration test (Testcontainers): candidate with last-update timestamp N+1 days ago triggers SLA breach → `draftMessage` document created in MongoDB → candidate flagged `slaStatus: RED`. *(QA test-type-gap: MongoDB write path must be verified by integration test, not just unit mock.)*
- A candidate who has had no update for N+1 days has a red SLA indicator on the dashboard.
- Auto-send code path does NOT exist in the MVP codebase.

---

### F32 — Interviewer Feedback Forms & Reminder Escalation
**Spec ref**: §4 Pillar B, §5.2 (FR-13)

Post-interview scorecard link (no login required); reminder escalation via `@Scheduled` + `SchedulerCheckpoint` (F00.2); scorecards visible to Recruiter + Hiring Manager (own candidates only). Voice-to-scorecard deferred to v1.5.

**Constitution gates**: C1 ✅ C2 ✅ C3 ✅ C4 ✅ C5 ✅ C6 ✅
**Deferred**: Voice-to-scorecard → v1.5.

**User stories**:
- US-F32-1: As an Interviewer, I receive an email link after my interview to a lightweight scorecard form completable in < 5 min without logging in.
- US-F32-2: As the system, if an interviewer has not submitted feedback by the configurable deadline, I send reminder emails with escalating urgency.
- US-F32-3: As a Recruiter, I can view submitted scorecards and see which interviewers have not yet responded.
- US-F32-4: As a Hiring Manager, I can view scorecards for interviews on my own assigned requisitions only (server-side filtered). *(BA SG-2: HM story split from Recruiter story with explicit scope.)*
- US-F32-5: As the system, interviewer feedback submission compliance stats are visible on the dashboard.

**Scorecard token requirements**: Token TTL 72 h; token is bound to `{ interviewEventId, interviewerId }` — a single token grants write-only access to exactly one scorecard record and MUST NOT return previously submitted content in the response. *(Security ISSUE-5.)*

**Acceptance criteria**:
- `GET /api/scorecards/{id}` enforces Recruiter or Hiring Manager role (403 for Interviewer reading peers, 403 for Read-only); HM access is filtered to own requisitions server-side. *(Security ISSUE-5.)*
- Scorecard submission handler logs only anonymised candidateId and interviewerId; free-text fields are never logged at any level. *(Security ISSUE-7, QA PII gap.)*
- Reminder `@Scheduled` task uses `SchedulerCheckpoint` (F00.2). *(Backend.)*
- Scorecard data stored in MongoDB, linked to candidate + interview event.
- Required E2E test: Interviewer follows scorecard link (no login) → submits scorecard → data persists → Recruiter view shows submission. *(QA: scorecard E2E was missing.)*
- Dashboard shows feedback-pending count per requisition.

---

## Tier 3 — Required MVP (P2): Integrations

### F40 — ATS Integration: Greenhouse
**Spec ref**: §5.3 (FR-15), constitution §III (AtsConnector interface)

Bidirectional: pull candidate/stage data from Greenhouse; push scheduling events back as ATS activity. Wrapped behind `AtsConnector` interface. Integration-pending stub until live OAuth credentials are available.

**Constitution gates**: C1 ✅ C2 ✅ C3 ✅ C4 ✅ C5 ✅ C6 ✅

**User stories**:
- US-F40-1: As a Recruiter using Greenhouse, I can connect Cadence to Greenhouse via API key so that candidates and stages are imported automatically.
- US-F40-2: As the system, when a scheduling event occurs in Cadence, I write it back to the Greenhouse candidate timeline.
- US-F40-3: As a Recruiter, new candidates added to a Greenhouse stage appear in Cadence within 5 minutes.

**Acceptance criteria**:
- `GreenhouseAtsAdapter` implements `AtsConnector`; business logic depends only on the interface; no Greenhouse SDK class referenced directly in service code.
- The `AtsConnector` adapter logs only `candidateId` (internal) and `atsRefId` (external opaque ID) — no name, email, or stage label at any log level; CI log-grep confirms. *(Security ISSUE-7.)*
- Candidate PII fields (name, email) stored in MongoDB using workspace encryption-at-rest configuration (CSFLE or server-side encryption). *(Security ISSUE-8.)*
- **Error handling / degraded mode**: If Greenhouse is unreachable, Cadence logs the failure (candidate ID only), surfaces a banner in the pipeline view, and queues the write-back for retry within 15 minutes using a MongoDB-persisted retry record; no data is silently dropped. *(BA AC-1.)*
- A burst of 50 candidates via webhook is fully processed within 5 minutes (integration test). *(QA performance gap.)*
- Integration test uses a locally-runnable stub labelled "integration-pending" until live credentials are available.
- **Multi-role review at integration-pending → live-credential promotion** must include Security role verifying API key/OAuth credential is not logged and is stored in environment config, not source. *(QA DoD-gap.)*

---

### F41 — ATS Integration: Lever
**Spec ref**: §5.3 (FR-15)

Same scope as F40 via Lever REST API. Implements `LeverAtsAdapter` with the same `AtsConnector` interface.

**Constitution gates**: C1 ✅ C2 ✅ C3 ✅ C4 ✅ C5 ✅ C6 ✅

**Acceptance criteria**: All F40 acceptance criteria apply (error handling, PII logging, encryption, burst processing, security review at promotion). *(Security ISSUE-7, ISSUE-8, BA AC-1.)*

Additional:
- Lever and Greenhouse can coexist in a workspace (multi-connector support verified by integration test).

---

### F42 — Standalone CSV Import Mode
**Spec ref**: §5.3 (FR-17), §7 Differentiation (SMB-friendly)

Import candidates via structured CSV (name, email, stage, requisition). MVP pipeline view usable without an ATS. Import is processed asynchronously to avoid blocking the HTTP thread.

**Constitution gates**: C1 ✅ C2 ✅ C3 ✅ C4 ✅ C5 ✅ C6 ✅

**User stories**:
- US-F42-1: As a Recruiter with no ATS, I can upload a CSV of candidates so that I can use Cadence without an ATS integration.
- US-F42-2: As a Recruiter, the CSV import validates field formats and reports errors per row before committing.
- US-F42-3: As a Recruiter, imported candidates appear in the pipeline view identically to ATS-synced candidates.

**Acceptance criteria**:
- `POST /import/csv` accepts the file, persists it to a `PendingImport` MongoDB document, returns `202 Accepted` with a job ID immediately; processing occurs via `TaskScheduler` (constitution-compliant); `GET /import/{jobId}/status` returns progress and per-row errors. *(Backend: blocking 500-row import would hold HTTP thread; async required.)*
- Validation errors are reported per-row (not whole-file rejection); a file where > 80% of rows fail validation is rejected with a summary error before any rows are committed.
- Duplicate detection: rows whose email address matches an existing candidate are flagged as duplicate warnings; Recruiter must choose merge or skip before the import commits. *(BA AC-3.)*
- Maximum file size enforced before processing (configurable, default 5 MB); CSV injection cells (starting with `=`, `+`, `-`, `@`) are stored as literal strings, not executed. *(QA edge-case.)*
- CSV rows are NOT logged at any level; validation errors log only `rowNumber` and field name, not field value. *(Security ISSUE-7.)*
- PII from CSV stored encrypted at rest (same config as F40/F41). *(Security ISSUE-8.)*

---

## Tier 4 — Required MVP (P3): Visibility

### F50 — Core Dashboard
**Spec ref**: §4 Pillar C, §5.4 (FR-21), §11 MVP

Three core MVP metrics: **time-to-schedule**, **no-show rate**, **current silence list**. Exportable as CSV. Recruiter and Admin access; Hiring Manager sees own requisitions only; Interviewer has no access.

**Constitution gates**: C1 ✅ C2 ✅ C3 ✅ C4 ✅ C5 ✅ C6 ✅
**Deferred**: Recruiter-hours-saved projections, feedback turnaround analytics, candidate pulse NPS → v1.5/v2.

**Role access matrix** (server-side enforced): Admin ✓ full, Recruiter ✓ full, Read-only ✓ view-only (no export), Hiring Manager ✓ own requisitions only, Interviewer ✗. *(Security ISSUE-4.)*

> **Erratum (2026-06-18, spec 024-core-dashboard)**: The Hiring Manager "✓ own requisitions only" row is **deferred to F51**. There is no candidate→requisition→assignment link in the codebase today (confirmed against current source; the documented F32/F40 precedent), so per-requisition scoping cannot be built in F50 without a stub (§II) or pulling F51 forward (§I YAGNI). For the MVP the dashboard is **workspace-scoped only** and the Hiring Manager is **denied** dashboard access (rather than shown unscoped cross-requisition data, which would violate the C3 minimum-exposure gate). The effective F50 access matrix is therefore Admin ✓ full, Recruiter ✓ full, Read-only ✓ view-only, **Hiring Manager ✗ (deferred to F51)**, Interviewer ✗. F51 — which owns the requisition linkage — restores HM scoped access. Stakeholder confirmation to be recorded at `/speckit.plan`.

**User stories**:
- US-F50-1: As a Recruiter/Admin, I can view time-to-schedule (median and per-requisition) for a user-selectable window so that I can track scheduling velocity.
- US-F50-2: As a Recruiter/Admin, I can see the no-show rate for the selected window.
- US-F50-3: As a Recruiter/Admin, I can see a silence list of candidates currently exceeding their SLA window.
- US-F50-4: As an Admin, I can export dashboard data to CSV.

**Acceptance criteria**:
- Each metric displays for a user-selectable window (default: rolling 30 days); selected window persists per session. *(BA AC-5: without time window, metric definitions are ambiguous.)*
- Dashboard data computed from MongoDB event records (not in-memory); survives restart.
- Silence list auto-refreshes when SLA nudge engine marks new breaches.
- API contract test verifies role enforcement: Interviewer receives 403; HM receives only own-requisition data.
- Dashboard pages are internal; Lighthouse and WCAG ACs explicitly do not apply (DoD items 3 and 4 are N/A for internal screens; documented here to prevent ambiguous CI gate failures). *(QA DoD-gap.)*

---

### F51 — Pipeline View
**Spec ref**: §5.2 (FR-14 bulk actions), §4 Pillar C

Recruiter's primary working view: all candidates colour-coded by SLA/scheduling status. Bulk actions. Hiring Manager view is filtered server-side to their own requisitions.

> **Spec ref clarification**: FR-14 in the product spec refers to "Bulk actions: schedule, update, or close out many candidates at once." FR-14 in the deferred table in this backlog was incorrectly labelled as "Candidate pulse micro-survey" — that is actually from §4 Pillar C and is correctly deferred to v2. FR-14 bulk actions are in-scope for the Pipeline View. *(BA SL-1/SL-2: FR-14 numbering conflict resolved.)*

**Constitution gates**: C1 ✅ C2 ✅ C3 ✅ C4 ✅ C5 ✅ C6 ✅

**User stories**:
- US-F51-1: As a Recruiter, I can see all my active candidates in a sortable/filterable list with their stage and SLA status colour.
- US-F51-2: As a Recruiter, I can select multiple candidates and send them a scheduling link or an update email in one bulk action.
- US-F51-3: As a Recruiter, I can click on a candidate to see their full timeline (messages, scheduling events, feedback status).
- US-F51-4: As a Hiring Manager, I can see a pipeline view filtered to only my assigned requisitions (server-side filter, not client-side). *(BA SG-1 / Security ISSUE-4.)*

**Acceptance criteria**:
- HM pipeline view is filtered server-side; API contract test verifies HM cannot retrieve candidates outside their requisitions even with direct API call.
- Bulk-action endpoints return 403 for Hiring Manager, Interviewer, and Read-only roles (contract test).
- Colour status updates on SLA breach within one polling cycle (default: 60 s).
- Bulk action applies to all selected candidates; partial failures reported per-candidate.
- Pipeline view with 200 active candidates renders in < 3 s on desktop (performance test or Lighthouse equivalent). *(QA performance gap.)*
- Candidate timeline shows events in chronological order; anonymised IDs in logs.
- Internal screen; Lighthouse and WCAG ACs are N/A (same as F50).

---

## Explicitly Deferred (DO NOT START without constitution amendment)

| Feature | Spec ref | Target phase |
|---|---|---|
| Flow A2 — Multi-stage loop solver | §4 Flow A2 | v1.5 |
| SAML 2.0 SSO (OIDC ships in MVP F01) | §6 Security, F01 | v1.5 |
| SMS / WhatsApp channel | §5.2 FR-12 | v1.5 |
| Voice-to-scorecard capture | §5.2 FR-13 | v1.5 |
| Auto-send SLA policies | §5.2 FR-10 | v1.5 |
| Interviewer load-balancing analytics (FR-8) | §5.1 FR-8 | v1.5 |
| Mobile companion app (iOS/Android) | §5.5 FR-22/23 | v1.5 |
| FR-7 — Meeting-link generation (Google Meet/Teams/Zoom) | §5.1 FR-7 | v1.5 |
| Candidate pulse micro-survey | §4 Pillar C | v2 |
| Public REST API / webhooks (FR-16) | §5.3 FR-16 | v2 |
| Agency multi-client workspaces | §3 P5 | v2 |
| Additional ATS connectors (Workable, SmartRecruiters, etc.) | §5.3 FR-15 | v2 |
| Advanced forecasting / time-to-fill analytics | §4 Pillar C | v2 |

---

## Delivery Sequence (Recommended)

```
P0 Foundation
    F00 Project Scaffold (+ F00.1 MongoDB Indexes + F00.2 Observability/Scheduler)
    F01 Auth + F01.1 OAuth Token Store
    F02 RBAC
    F03 Workspace Config
    F04 GDPR Baseline
         |
         +-- P1 Calendar & Rules (parallel)
         |       F10 Google Calendar
         |       F11 Microsoft 365
         |       F12 Rule Engine
         |
         +-- P1 Email Infrastructure (parallel with Calendar)
                 F21 Template Library
                 F22 Email Delivery
         |
P1 Flow A1 E2E (F13) + Scheduling Page UX (F14)
    [Depends on: F10/F11, F12, F21, F22, F01.1]
    [First deployable end-to-end demo]
         |
         +-- P2 Resilience (can parallel after F13)
         |       F20 Flow A3 Reschedule/Cancel
         |       F23 Flow A4 No-Show Defense
         |
         +-- P2 Candidate Experience (can parallel after F13)
         |       F30 Candidate Status Page
         |       F31 SLA Nudge Engine [depends on F30, F22]
         |       F32 Feedback Forms [depends on F22]
         |
         +-- P2 Integrations (can parallel after F04)
                 F40 Greenhouse ATS
                 F41 Lever ATS [depends on F40 interface]
                 F42 CSV Import
         |
P3 Dashboard & Pipeline
    F50 Core Dashboard [depends on event data from all above]
    F51 Pipeline View [depends on F50 + F31 SLA indicators]
```

---

## Backlog Health Checklist (pre-implementation gate per feature)

Before any feature moves to `spec.md` + `plan.md`:

- [ ] Feature is listed in §11 MVP (C1 gate) — or constitution amendment filed
- [ ] No new infrastructure service introduced (C2 gate)
- [ ] Candidate PII exposure scoped to minimum required roles (C3 gate)
- [ ] No dependency outside the fixed stack (C4 gate)
- [ ] Any script artefacts will be scanned for non-ASCII before done (C5 gate)
- [ ] Multi-role sub-agent review (>= 3 roles) scheduled for task close (C6 gate)
- [ ] MongoDB indexes for this feature declared in `plan.md` (F00.1 pattern)
- [ ] Any `@Scheduled` task uses `SchedulerCheckpoint` pattern (F00.2)
- [ ] Token expiry and rate-limiting specified for any private-link feature
- [ ] Consent-check and erasure-check wired through `EmailSender` for any outbound comms
