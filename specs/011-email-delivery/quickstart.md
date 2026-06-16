# Quickstart: Email Delivery Channel (F22)

## What this feature delivers

The real email channel. After F22:
- F01 **member invitations** and **password-reset** emails actually send (were no-ops) — the browser→backend→email §II leg.
- F00.2 **dead-letter alerts** actually email the ops address.
- **Candidate** emails go through a consent-gated, idempotent, restart-safe dispatch pipeline with bounce handling and a reusable scheduled-send mechanism that F13/F23/F31/F32 build on.

## Run locally

```powershell
# 1. Mongo (Docker) for manual dev
docker run -d -p 27017:27017 mongo:7

# 2. Dev SMTP sink: MailHog / Mailpit (LOCAL DEV ONLY — never deployed to Fly, not part of the
#    production topology). Prod uses a provider SMTP relay (SendGrid/SES) via Fly secrets.
#    application.yml binds: cadence.email.smtp.{host,port,username,password} <- ${CADENCE_EMAIL_SMTP_*};
#    cadence.email.webhook-secret <- ${CADENCE_EMAIL_WEBHOOK_SECRET}.
#    Candidate sends prefer the workspace F03 credential as the SMTP password; member/operational
#    mail (invites/resets/alerts) uses the app-level default CADENCE_EMAIL_SMTP_PASSWORD.

# 3. Backend + frontend
./gradlew bootRun          # from backend/ (after docker run mongo:7)
ng serve                   # from frontend/
```

## Demonstrate end-to-end (§II)

1. **Member email (browser, real SMTP)**: as an Admin, invite a workspace member (`admin` feature → Invite). A real invitation email arrives at the SMTP sink with the accept-invite link. (Password-reset flow likewise.)
2. **Candidate dispatch (recruiter trigger)**: in `email-templates`, preview a template with a candidate, then **Send to candidate** → a consent-gated dispatch runs; the candidate timeline/audit records `EMAIL_DISPATCH_SENT`. Try a candidate with withdrawn consent → `409 not_contactable` (no send).
3. **Bounce**: POST a signed hard-bounce event to `/api/webhooks/email/events` → the candidate is flagged `undeliverable`, the recruiter is notified, and the next automatic send is refused.

## Test

```powershell
# Backend (JAVA_HOME=C:/jdk-24.0.1, cached gradle-9.4.0, -Dapi.version=1.41, DOCKER_HOST=npipe:////./pipe/docker_engine)
./gradlew test --tests "com.cadence.emaildelivery.*"
ng test --watch=false       # frontend (email-templates "Send to candidate" Jasmine)
```

Key suites: `EmailDispatchConcurrencyTest` (gated dedup), `EmailDispatchSchedulerTest` (due + missed-fire replay), `EmailBounceWebhookTest` (signature + idempotent hard/soft), `EmailDispatchConsentGateTest` (each refusal reason), `MailTransportSwapTest` (SC-007), `EmailDeliveryLogPiiScanTest` (SC-006), `EmailDispatchContractTest` (MockMvc).

## Deploy

Backend-only + new Mongock changeset → `scripts\db-migrate.ps1` then `scripts\deploy-backend.ps1` (`ChangeUnit010` applies on startup). Frontend (email-templates change) → `scripts\deploy-frontend.ps1`. Set Fly secrets (UPPER_SNAKE_CASE env-var names) **before first deploy** — the app-level default password is required or the member-email path breaks:

```
fly secrets set CADENCE_EMAIL_SMTP_HOST="smtp.sendgrid.net" CADENCE_EMAIL_SMTP_PORT="587" `
  CADENCE_EMAIL_SMTP_USERNAME="apikey" CADENCE_EMAIL_SMTP_PASSWORD="..." `
  CADENCE_EMAIL_WEBHOOK_SECRET="..."
```

Observability: dispatch metrics (status gauge / sent-refused-failed-bounced counters / reaper hits) are on the existing actuator (FR-024) — watch PENDING-backlog depth + reaper count to detect a stalled worker or dead provider. Known limit (D12): during a full SMTP outage, dead-letter *records* are still written but the alert *email* will also fail (logged, not retried) — the backlog metric is the out-of-band signal.

## Boundaries

F22 = the channel + the reusable scheduled-dispatch pattern. Concrete reminder triggers (24h/1h, SLA, feedback) and the pipeline-view bounce badges are F13/F23/F31/F32/F51.
