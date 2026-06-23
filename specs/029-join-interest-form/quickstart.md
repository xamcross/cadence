# Quickstart: Join / Express-Interest Request Form (F70)

**Feature**: 029-join-interest-form | **Date**: 2026-06-23

## What this delivers

A public, no-login "Request access" form (linked from the sign-in screen and the public home) where a prospective workspace member expresses interest, plus an Admin-only review queue that converts a request into an invitation through the existing invitation flow. It never grants access itself — invitation-only is preserved.

## Configuration (new `cadence.interest.*` properties)

| Property | Default | Purpose |
|---|---|---|
| `cadence.interest.default-workspace-id` | `cadence` | Server-resolved owning workspace for anonymous submissions (FR-019). Set to your real workspace id. |
| `cadence.interest.retention-fallback-days` | `180` | Retention if the workspace has no `retentionPeriodDays` set (FR-021). |
| `cadence.interest.max-per-ip-per-window` | `5` | Per-hashed-IP submission cap (R6). |
| `cadence.interest.ip-window` | `PT10M` | Per-IP window. |
| `cadence.interest.max-per-workspace-per-window` | `100` | Per-workspace flood ceiling (R6). |
| `cadence.interest.workspace-window` | `PT1H` | Per-workspace window. |
| `cadence.interest.min-fill-millis` | `1500` | Bot heuristic: minimum form-fill time. |

No new secret. No new Fly secret.

## Manual end-to-end check (the §II demonstrable leg)

1. **Submit (US1)**: open `/request-access` (unauthenticated), fill name + email (+ optional org/message), submit → see the confirmation. Submit again with the same email → still a confirmation, but the queue still shows ONE open request (coalesced, SC-007).
2. **No-oracle (SC-005)**: submit with (a) an existing member's email, (b) an unknown email → identical confirmation, no timing tell.
3. **Review (US2)**: sign in as an Admin, open the interest queue → the request appears with email/org labelled "unverified". Mark reviewed → drops from the triage filter. Invite with a role → an invitation email is issued via the normal flow and the request shows "invited".
4. **Already-member (FR-015)**: invite from a request whose email is already a member → admin sees "already a member", no second invitation, no error.
5. **Notification (US3)**: a new submission produces a value-free admin alert linking to the queue (no submitter PII in it).
6. **Erasure (FR-022)**: as Admin, erase a request → PII wiped, no longer discoverable by email.
7. **Retention (SC-008)**: with a controllable clock in tests, advance past the retention window → the purge scan hard-deletes aged rows.

## Tests to write (Test-First, Principle VII — one acceptance test per user story minimum)

- **Unit**: status-transition guard (no double-invite); bot-heuristic/honeypot + min-fill-time; per-source limiter (real-client-IP resolution from `CF-Connecting-IP`) + retention cutoff math with the `<=0` fallback sentinel (Clock).
- **Integration (Testcontainers)**: public submit → row persisted (PII encrypted at rest — raw-driver ciphertext assert; `emailHash`/`openEmailHash` stored as-is); dedup coalesce (**gated concurrent insert → one open row**); invite→`InvitationService` issues an invitation + request INVITED + `openEmailHash` unset; **gated concurrent-invite (2 admins) → exactly one `InvitationService.create` call + one 409** (FR-016, SC US2.6); already-member path (terminal, no 2nd invite, no 500); admin erasure `$set [ERASED]` + `$unset` hashes; retention purge deletes aged rows (MutableClock) + a checkpoint-replay/double-sweep idempotency proxy (the F31/F40 honest-bound); per-workspace **DB-count ceiling** blocks a flood of rotated-IP-hash submissions while a normal single submit succeeds (**SC-006(b)**); index bootstrap (`ChangeUnit023`).
- **Notification (SC-011)**: a same-email burst produces **exactly one** value-free `RecruiterNotification` row (`notify(ws, null, INTEREST_REQUEST)`); assert no submitter PII in the row.
- **Contract (MockMvc)**: public 202 **byte-identical (body+status+headers)** across {member, pending-invite, open, unknown} (the SC-005 4-case test; timing is structural, not asserted); 400/429 envelopes; 5-role matrix on the internal endpoints (only ADMIN passes, **403 stays 403 not a swallowed 500** — the handler re-throws `AccessDenied`/`Authentication`); scoped 404 no-oracle; cross-workspace isolation (SC-013).
- **PII**: `InterestLogPiiScanTest` — drive submit + a failing path + notification with high-entropy sentinels for name/email/org/message; assert absent from **logs, the `deadLetters` collection, the `recruiterNotifications` row, and the persisted `interestRequests` doc** (exception messages reduced to a cause-class string at the service boundary — the F22 lesson, since `DeadLetterService` sanitizes only emails). CI `SENTINELF70*` scan block.
- **Frontend (Jasmine + axe)**: `/request-access` axe 0 WCAG 2.2 AA violations across states, 44px targets, token/PII-free, no web-storage; Lighthouse ≥ 85 via the F14 stub harness (add the `/request-access` url to `lighthouserc.json` + matrix; **no new canned stub route** — the form renders without a backend call). Admin queue component logic (list/filter/actions).
- **Untrusted-input (SC-012 — fully closed)**: a `<script>` payload is **inert in the admin display** (Angular interpolation auto-escape — assert via a binding test). The **export half is now IMPLEMENTED**: `GET /api/internal/interest-requests/export?status=` (Admin-only, workspace-scoped, audits `INTEREST_REQUESTS_EXPORTED`) routes every free-text cell (name/email/organization/message) through `CsvInjectionEscaper` at the export boundary (the F50 `DashboardService.renderCsv` precedent), so a `=cmd|...`/`+SUM(1)`/`@foo`/leading-`-` payload is neutralized (prefixed + RFC-4180-quoted) and cannot execute in a spreadsheet. `InterestExportIT` asserts neutralization + Admin-only (403 not 500) + `text/csv`/attachment/`no-store` + workspace-scoping; `interest-requests.component.spec.ts` asserts the Export CSV action passes the current status filter. Both SC-012 halves (display-inert + export-neutralization) are closed.

## Run (local)

```powershell
# backend tests (Testcontainers; JAVA_HOME=C:/jdk-24.0.1, cached gradle, -Dapi.version=1.41, DOCKER_HOST npipe)
cd backend ; ..\gradlew.bat test
# frontend
cd frontend ; npx ng test --watch=false ; npx ng build --configuration production
```

## Out of scope (do not build)

No submitter email/confirmation message; no public erasure or status-lookup endpoint; no multi-workspace routing; no re-open of an invited request on invitation expiry; no broadening of review beyond Admin. (See spec "Out of Scope".)
