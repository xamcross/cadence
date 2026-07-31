# Feature Specification: Billing & Plans — Freemius Integration

**Feature Branch**: `032-freemius-billing`
**Created**: 2026-07-30
**Status**: Draft
**Input**: User description: "Remove Stripe references from the project and create a spec to integrate Freemius" (no Stripe integration ever existed — the single cosmetic mention in specs/030 was removed; this is greenfield billing)

## Overview

Cadence is currently free for all early-access workspaces and has **no billing code of any kind**. This feature introduces monetization: a **Free** plan and a single paid **Team** plan, sold through **Freemius** acting as merchant of record (hosted checkout, tax/VAT, subscription renewals, dunning, refunds, customer portal). Cadence itself never collects or stores payment details; it keeps one minimal, PII-free **entitlement record per upgraded workspace** and keeps that record faithful to Freemius through three mutually reinforcing paths: a license **claim** performed by the purchasing admin's authenticated session, signed **webhooks** treated as pokes (truth is always re-fetched from the Freemius API), and a nightly checkpointed **reconciliation sweep** that self-heals missed events.

The Team plan gates three features: **ATS integrations** (Greenhouse and Lever), **no-show defense**, and the **SLA nudge engine**. Everything else — core scheduling, calendar sync, candidate links, reschedule/cancel, email templates, dashboards, pipeline views, CSV import — stays free, and **GDPR tooling and candidate-facing surfaces are never gated under any plan state**.

The integration follows the constitution's provider-seam rule: all Freemius access sits behind a `BillingProvider` interface in `integration/billing/`, implemented with the raw HTTP client and explicit-field JSON parsing like the calendar and ATS adapters. Checkout is the **hosted redirect** flavor, so no third-party script enters the CSP-locked SPA and no runtime dependency is added.

## Clarifications

### Session 2026-07-30

- Q: "Replace Stripe with Freemius" — what exists today? → A: **Nothing.** No billing code, spec, or dependency; the product is free early-access. The only "Stripe" match in the repo was a cosmetic company-name mention in `specs/030-sota-design-system/plan.md`, now removed. This feature is greenfield billing on Freemius.
- Q: Pricing model? → A: **Flat per-workspace tiers** — no seat counting, no usage metering.
- Q: How many tiers at launch? → A: **Free + one paid Team plan.** Higher tiers can be added later; price points live in the Freemius dashboard, not in code.
- Q: What separates Free from Team? → A: **Feature gates, not usage caps.** Gated: ATS integrations (Greenhouse/Lever), no-show defense, SLA nudge engine. Dashboards and pipeline views stay free. GDPR tooling and candidate surfaces are never paywalled.
- Q: Existing early-access workspaces at launch? → A: **Downgrade to Free the day billing ships.** Prior notice is an operational comms task (email/announcement), not product code. Absence of an entitlement record = Free, so launch needs no data migration.
- Q: Integration architecture? → A: **Hosted checkout redirect + claim-on-return + signed webhooks + nightly reconciliation sweep** (chosen over webhook-only binding and over JS-overlay checkout with live API checks, both rejected for fragility / CSP / hot-path reasons).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Upgrade a workspace to Team (Priority: P1)

As a workspace Admin, I can upgrade my workspace to the Team plan by paying through Freemius's hosted checkout, so that gated features unlock for my whole workspace — without Cadence ever seeing my card details.

**Why this priority**: This is the revenue path; nothing else in the feature matters until a workspace can become Team. It is also the smallest independently demonstrable slice: checkout → claim → plan flips.

**Independent Test**: Against a local Freemius API stub, start an upgrade from the Billing page, observe the redirect URL is a server-built hosted checkout link (no third-party script loaded), simulate the return with a license id, and confirm the workspace shows Team with renewal details. Attempt claims with an already-bound, wrong-product, cancelled, and expired license and confirm typed refusals.

**Acceptance Scenarios**:

1. **Given** an Admin of a Free workspace on the Billing page, **When** they start an upgrade, **Then** the backend returns a hosted Freemius checkout URL for the Team plan with the Admin's email prefilled read-only, the browser redirects there, and no Freemius script is loaded into the SPA.
2. **Given** a completed payment, **When** the Admin is redirected back with a license reference, **Then** Cadence verifies the license server-side against the Freemius API (correct product, correct plan, active, unbound) and atomically binds it; the workspace immediately shows Team with status and expiry/renewal date.
3. **Given** a license already bound to another workspace, **When** a claim is attempted, **Then** it is refused with a clear "already in use" error and no rebinding occurs.
4. **Given** a license that is for the wrong product or plan, cancelled, or expired, **When** claimed, **Then** the claim is refused with a typed reason and the workspace stays Free.
5. **Given** a buyer who closed the tab before returning, **When** the Admin pastes the license ID from their Freemius receipt email into the Billing page's recovery field, **Then** the same claim flow binds it under the same rules.
6. **Given** a non-Admin member, **When** they access billing management, **Then** they are refused; they can see the workspace's current plan but cannot start checkout, claim, or view billing controls.

---

### User Story 2 - Plan gates enforce entitlement (Priority: P1)

As the platform, I enforce the Team gate at every gated feature's initiation point, so Free workspaces get a consistent upgrade prompt instead of the feature, and downgrades never destroy data or interrupt in-flight work.

**Why this priority**: Enforcement is the other half of monetization — without it the Team plan sells nothing. It also defines the downgrade semantics every other story relies on.

**Independent Test**: With one Free and one Team workspace side by side: confirm the Free workspace is refused at ATS connect/config endpoints and skipped by the ATS sync, cascade-initiation, and SLA-nudge sweeps, while the Team workspace passes all of them; downgrade the Team workspace and confirm retained-but-paused behavior with no data loss.

**Acceptance Scenarios**:

1. **Given** a Free workspace, **When** an Admin attempts to enable or configure an ATS connection, **Then** the API refuses with the standard upgrade-required rejection and the SPA shows an upgrade prompt in place of the configuration surface.
2. **Given** a Free workspace with a previously configured ATS connection (from early access or a lapsed Team plan), **When** the ATS sync sweep runs, **Then** the workspace is skipped, the connection is retained and shown as "paused — requires Team plan", and no imported data is deleted.
3. **Given** a Free workspace, **When** the no-show defense sweep runs, **Then** no new confirmation cascades are initiated for it; cascades already in flight complete normally.
4. **Given** a Free workspace, **When** the SLA nudge sweep runs, **Then** it is skipped and no nudge emails are sent.
5. **Given** a Team workspace, **When** any gated feature is used, **Then** it functions with no plan-related friction.
6. **Given** any plan state (Free, Team, lapsed), **When** a candidate uses a tokenized scheduling/status/feedback page, **Then** behavior is identical — candidate surfaces carry no plan checks.
7. **Given** any gated internal endpoint, **When** called for a Free workspace, **Then** the rejection is HTTP 402 with the stable machine-readable code `upgrade_required`, and the deny-by-default endpoint inventory remains fully enforced.

---

### User Story 3 - Entitlement tracks the subscription lifecycle (Priority: P2)

As the platform, I keep each workspace's entitlement faithful to Freemius across renewals, cancellations, expiries, dunning, and lost webhooks, so nobody keeps paid features without paying and nobody loses paid features due to a transient glitch.

**Why this priority**: Depends on US1 (a bound license must exist). Correct lifecycle handling is what makes the billing trustworthy over months, but it isn't demonstrable until purchases work.

**Independent Test**: Bind a license against the stub, then: deliver signed cancel/expire webhooks and confirm downgrade at the license's effective end; deliver a bad-signature webhook and confirm a generic 401; replay an event id and confirm a no-op; break the stub during the sweep and confirm no state change; advance the test clock past expiry and confirm the reconciliation sweep downgrades.

**Acceptance Scenarios**:

1. **Given** a bound Team workspace, **When** Freemius delivers a validly signed lifecycle event (cancelled / expired / plan changed) for that license, **Then** Cadence re-fetches the license from the Freemius API and updates the entitlement from that fetched state — the workspace becomes Free no earlier than the license's effective end.
2. **Given** a webhook with a missing or invalid signature, **When** received, **Then** it is rejected with a generic 401 carrying no detail, and nothing is processed.
3. **Given** a webhook event id that was already processed, **When** redelivered, **Then** processing is an idempotent no-op.
4. **Given** a webhook for a license not bound to any workspace, **When** received, **Then** it is acknowledged and ignored (claim is the only binding act).
5. **Given** webhooks were lost entirely, **When** the nightly reconciliation sweep runs, **Then** every bound entitlement is re-verified against the API and corrected within 24 hours.
6. **Given** a transient Freemius API failure during reconciliation or webhook processing, **When** verification cannot complete, **Then** the entitlement is left unchanged (a provider error never downgrades anyone) and the row is retried on the next run.
7. **Given** a renewal payment failure inside Freemius's dunning window, **When** Freemius still reports the license active, **Then** the workspace remains Team.

---

### User Story 4 - Billing launch lands cleanly on early-access workspaces (Priority: P2)

As an early-access workspace, when billing ships I drop to the Free plan with my data intact and a clear path to upgrade, and the public pricing page tells the truth about plans.

**Why this priority**: One-time launch behavior. It must be right on day one but has no ongoing surface beyond what US2 already defines.

**Independent Test**: Deploy the feature over a database of pre-existing workspaces with no entitlement records; confirm every workspace reads as Free with zero migration writes, gated surfaces show upgrade prompts, previously configured ATS connections follow the paused-not-deleted rule, and the pricing page shows the Free/Team split.

**Acceptance Scenarios**:

1. **Given** the feature is deployed, **When** any workspace without an entitlement record is evaluated, **Then** it is on the Free plan — no data migration or backfill writes occur at launch.
2. **Given** an early-access workspace with gated features previously configured, **When** billing goes live, **Then** those features follow the US2 downgrade semantics: retained, paused, nothing deleted, in-flight work completes.
3. **Given** an Admin of a Free workspace visiting a gated surface, **When** the upgrade prompt is shown, **Then** it links to the Billing page; a non-Admin member instead sees a "contact your workspace admin" notice.
4. **Given** the release is live, **When** the public pricing page is viewed, **Then** it describes the Free and Team plans accurately (candidate-facing pages stay free by nature) and no longer promises free early access.

---

### Edge Cases

- **Claim race**: two Admins claim the same license concurrently → atomic bind (CAS + unique license index) lets exactly one win; the loser gets the "already in use" refusal.
- **Double purchase**: an Admin buys a second license while one is bound → second claim refused while an active license is bound; the UI points to the Freemius customer portal for a refund.
- **Webhook before claim**: `license.created` arrives before the buyer returns → ignored as unbound; the claim performs the binding and fetches current truth anyway.
- **Stale expiry vs live truth**: local `expiresAt` has passed but Freemius reports the license active (renewal raced the sweep) → API truth wins on re-verify; no flapping downgrade.
- **Unknown plan id on a bound license** (e.g., operator adds a new plan in the Freemius dashboard) → entitlement left unchanged and the row flagged in logs (ids only) for operator attention; never silently downgraded.
- **Freemius checkout outage** → purchases fail on Freemius's side; Cadence's Free behavior is unaffected and no partial state is created.
- **Workspace deletion** → its entitlement record is deleted with it; the customer/subscription relationship lives on in Freemius, where the buyer manages cancellation.

## Requirements *(mandatory)*

### Functional Requirements

**Plans & entitlement**

- **FR-001**: The system defines exactly two plans at launch: **Free** (default) and **Team**. A workspace is on Free if and only if it has no entitlement record that still confers Team — a record confers Team while its license has not passed its effective end, including a cancelled license whose paid period has not yet ended.
- **FR-002**: Each upgraded workspace has exactly one entitlement record storing: workspace reference (unique), plan, status (active / cancelled / expired), Freemius license id (unique among bound records), Freemius user id and plan id, expiry instant, bound-at and last-verified-at instants. **No buyer PII** (name, email, address, payment data) is ever stored in Cadence.
- **FR-003**: The Team gate covers exactly: ATS integrations (Greenhouse and Lever — connect, configure, sync), no-show defense cascades, and the SLA nudge engine. All other features are available on Free. GDPR tooling and candidate-facing tokenized surfaces are never gated.
- **FR-004**: Gates are enforced at initiation points only — ATS connect/config endpoints, the ATS sync sweep, cascade initiation, and the nudge sweep. On downgrade, in-flight work completes, configuration and imported data are retained (ATS connections shown as paused), and nothing is deleted.

**Purchase & claim**

- **FR-005**: An Admin-only endpoint returns a **server-built hosted checkout URL** (Freemius product, Team plan, return URL, Admin email prefilled read-only). No Freemius JavaScript is ever loaded into the SPA; the CSP is unchanged.
- **FR-006**: An Admin-only **claim** endpoint accepts a license reference, verifies it live against the Freemius API (correct product, correct plan, active, not bound to any workspace), and binds it atomically via CAS plus a unique index on the license id. Refusals carry typed, non-leaking reasons.
- **FR-007**: A manual recovery path lets an Admin paste a license ID from the Freemius receipt email into the Billing page; it uses the identical claim endpoint and rules.

**Webhooks**

- **FR-008**: The public webhook endpoint verifies the HMAC-SHA256 `X-Signature` over the raw request body using a constant-time comparison; anything invalid receives a generic 401 with no distinguishing detail.
- **FR-009**: Webhook processing is idempotent by provider event id (unique-indexed processed-events collection). Only the event id, event type, and license id are parsed from the payload; no other provider fields are bound or trusted.
- **FR-010**: A relevant event for a **bound** license triggers a re-fetch of that license from the Freemius API, and the entitlement is updated from the fetched state (webhook = poke, API = truth). Events for unbound licenses are acknowledged and ignored.

**Reconciliation**

- **FR-011**: A nightly scheduled sweep, checkpointed via the existing scheduler-checkpoint mechanism with per-row CAS, re-verifies every bound license against the Freemius API. A transient provider error never changes an entitlement; the row is retried on the next run.
- **FR-012**: Only an explicit expired/cancelled state from Freemius (via poke-then-fetch or sweep) downgrades a workspace; Freemius-side dunning that keeps the license active keeps the workspace on Team.

**Enforcement & API surface**

- **FR-013**: Gated endpoints reject Free-workspace requests with **HTTP 402** and the stable machine-readable code `upgrade_required`; the SPA maps this code to upgrade prompts.
- **FR-014**: All new internal endpoints carry `@PreAuthorize` (billing management restricted to workspace Admins, authorization read from the persisted member role); the webhook endpoint is registered as an explicit public exception in the endpoint-inventory test, like the candidate token endpoints.

**Frontend**

- **FR-015**: A Billing page under workspace settings (Admin-guarded route) shows current plan, status, and renewal/expiry; offers the upgrade action and the recovery claim field; and links to the Freemius customer portal for payment methods, invoices, and cancellation. Cadence renders no payment forms.
- **FR-016**: Gated surfaces show a shared upgrade-prompt component when the workspace is Free: Admins get a link to Billing; non-Admins get a contact-your-admin notice. All strings `$localize`, standalone components, axe-clean.
- **FR-017**: The public pricing content page is updated to describe the Free/Team split, replacing the early-access copy; exact price figures remain editorial content there and are configured in Freemius, never in application code.

**Integration & operations**

- **FR-018**: All Freemius access goes through a `BillingProvider` interface in `integration/billing/`; the adapter uses the raw HTTP client with explicit-field JSON parsing and bearer auth against `api.freemius.com/v1`. No SDK or new runtime dependency is added.
- **FR-019**: Configuration (Freemius product id, Team plan id, API bearer token, webhook secret) is supplied via environment secrets (`fly secrets set`, UPPER_SNAKE); never in source or `fly.toml`.
- **FR-020**: Logs and audit entries reference workspace ObjectIds and Freemius numeric ids only — no emails, names, or payload bodies; the CI PII log scan must remain green.
- **FR-021**: Schema changes (entitlement and processed-event collections, indexes) ship as append-only Mongock changesets.
- **FR-022**: Launch requires no data migration: the absence-of-entitlement-means-Free rule downgrades all early-access workspaces implicitly at deploy.

### Key Entities

- **WorkspaceEntitlement** — the single source of a workspace's plan when present: workspace reference (unique), plan, status, Freemius license id (unique among bound), Freemius user id, Freemius plan id, expiry, bound-at, last-verified-at. Absence means Free.
- **BillingWebhookEvent** — idempotency ledger for processed provider events: provider event id (unique), event type, license id, received-at, outcome. Carries no payload bodies and no PII.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: An Admin can go from Free to an active Team workspace (checkout → return → gates open) in under 5 minutes without support involvement.
- **SC-002**: An entitlement reflects a Freemius lifecycle change within 60 seconds of webhook receipt, and within 24 hours even if every webhook is lost.
- **SC-003**: 100% of gated initiation points reject Free workspaces with `upgrade_required` — enforced by a test that iterates the gate list.
- **SC-004**: Zero buyer PII stored or logged by Cadence; the CI PII scan stays green over the full purchase and lifecycle flows.
- **SC-005**: No new third-party script, CSP directive, or runtime dependency; candidate-page Lighthouse scores unaffected.
- **SC-006**: Concurrency tests prove one license can never yield two Team workspaces and a replayed webhook can never double-process.
- **SC-007**: Candidate-facing tokenized pages behave byte-identically across Free, Team, and lapsed states.

## Assumptions

- Freemius acts as full merchant of record: checkout, tax/VAT, invoicing, renewals, dunning, refunds, and the customer self-service portal. Cadence never touches payment instruments.
- One Freemius product with a single paid "Team" plan is configured manually in the Freemius dashboard (product id, plan id, price, return URL, webhook URL); identifiers reach the app only via secrets. Adding future plans is a dashboard + configuration exercise plus a gate-map change.
- One license per workspace; Freemius multi-seat/quantity licensing is unused.
- All automated tests run against local in-test HTTP stubs of the Freemius API and webhook signer (JDK `HttpServer`, consistent with the calendar/ATS test approach); live credentials are provisioned only at rollout, following the integration-pending pattern used by F40/F41.
- Pre-launch notice to early-access workspaces (email/announcement) is an operational task outside this feature's code.
- The webhook endpoint is reachable via the existing same-origin `/api` proxy topology; no new ingress is required.

## Out of Scope

- Per-seat, usage-based, or multi-tier pricing; annual/monthly variants beyond what Freemius plan configuration provides without code changes.
- Trials, coupons, proration mechanics, and refund workflows (Freemius-side concerns; support-driven).
- In-app invoice history or payment-method management (customer portal link instead).
- Automatic license binding via buyer-email matching (explicitly rejected — claim is the only binding act).
- Gating dashboards or pipeline views (deliberately free).
- Grandfathering or grace-period schemes for early-access workspaces (decision: downgrade at launch).
- Removing or rewriting historical mentions of billing plans in past specs (history is append-only).
