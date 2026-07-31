# 032 Freemius Billing — Live-Account Promotion Guide

Step-by-step wiring of the real Freemius account to Cadence. Grounded in what the 032 branch
implements (env names, endpoints, and the stub-pinned assumptions that MUST be verified live).
The integration was built and tested against a local stub (the F40/F41 "integration-pending"
pattern); Section 2 is the promotion step the plan schedules as separately reviewed.

## 1. Freemius dashboard setup

Extract four values: **Product ID**, **Team Plan ID**, **API bearer token**, **Secret Key**.

1. In the Freemius Developer Dashboard (`dashboard.freemius.com`), create the Cadence product
   as a **SaaS** product. From **Settings -> Keys**, note the **Product ID** (numeric) and the
   product **Secret Key**.
2. Under **Plans/Pricing**, create one paid plan named **Team**; set its monthly/annual price
   there (prices live only in Freemius -- nothing in the app changes) and note its
   **Plan ID** (numeric).
3. Configure the after-purchase redirect: **Plans -> Customization** -> enable the
   **"Redirect Checkout to a custom URL"** toggle -> enter `https://cadenceapp.cc/admin/billing`.
   (SaaS-type products only; HTTPS required; no separate whitelist page exists -- this toggle IS
   the redirect config. The URL must not itself server-redirect or Freemius's redirect signature
   validation fails; the SPA's Cloudflare `/* -> /index.html 200` catch-all is a rewrite, which
   is fine.) Verified against the hosted-checkout docs 2026-07-31: the redirect carries
   `license_id` (plus `user_id`, `plan_id`, `signature`, ...) as query params -- confirming the
   claim-on-return assumption. NOTE: hosted checkout does NOT honor a `return_url` query param on
   the checkout link; the `return_url` our adapter appends is harmlessly ignored and the dashboard
   toggle is the load-bearing config (optionally drop the param at promotion).
4. Under **Webhooks**, register `https://cadenceapp.cc/api/webhooks/billing/freemius` and
   subscribe to the license events: `license.created`, `license.updated`, `license.extended`,
   `license.shortened`, `license.cancelled`, `license.expired`, `license.plan.changed`.
   Freemius signs deliveries with the product **Secret Key** -- that value is the webhook secret.
5. Create an **API bearer token** (developer/API settings) with read access to licenses.

> Dashboard menu names may drift; the invariants are the four values above and the two URLs
> being registered.

## 2. Verify the stub-pinned shapes in sandbox (REQUIRED before announcing launch)

Four assumptions were pinned by the in-test stub and must be confirmed against real Freemius
using its sandbox/test-purchase mode:

1. **Return redirect param.** The SPA claims from `?license_id=...` on `/admin/billing`
   (`billing.component.ts`). Docs-verified 2026-07-31: the hosted-checkout redirect does carry
   `license_id`. Sandbox still confirms it end-to-end (and that the extra params -- `signature`
   etc. -- don't disturb the claim flow; the app ignores them, trusting only its own server-side
   license verification).
2. **License GET.** Docs-verified 2026-07-31: endpoint path
   `GET /products/{product_id}/licenses/{license_id}.json` confirmed by the API reference;
   `expiration` format `Y-m-d H:i:s` with null for lifetime confirmed; cancellation flag
   confirmed, and since the docs use `is_cancelled`/`is_canceled` interchangeably the adapter
   now accepts BOTH spellings (fail-safe). Sandbox still runs the curl as an end-to-end check:

   ```bash
   curl -H "Authorization: Bearer <token>" \
     "https://api.freemius.com/v1/products/<productId>/licenses/<licenseId>.json"
   ```

3. **Webhook shape.** Docs-verified 2026-07-31: signature header `x-signature`, HMAC-SHA256
   HEX of the raw body with the product secret key, timing-safe compare -- exactly what the
   controller implements. The PAYLOAD field names remain the one true sandbox unknown (the docs
   defer to "fetch the event via API; the API schema is identical to the webhook payload") --
   so on the first sandbox event, `GET /v1/products/{pid}/events/{eventId}.json` settles it in
   one call. The controller reads `id`, `type`, `objects.license.id` with a top-level
   `license_id` fallback.
4. **`license.deleted` / refund behavior — PROBED IN PRODUCTION 2026-07-31.** Findings:
   - **Refund with "keep license" fires NO `license.*` event at all** (only
     `subscription.cancelled` + `payment.refund`) -- the entitlement stays ACTIVE until the
     license's own expiration. Correct per our model (license is the sole truth).
   - **Dashboard license cancel** fires `license.cancelled`, cuts `expiration` to NOW, sets
     `is_cancelled=true`, and the license **stays fetchable (GET 200, no 404)**. The webhook
     cascade re-fetched truth and downgraded the workspace to Free (EXPIRED wins over
     CANCELLED, as coded). Verified end-to-end: ledger row `eventId 1405624963` outcome
     `processed`, entitlement `EXPIRED`, Billing page renders Free again.
   - The 404/retry-loop edge therefore applies **only to an explicit dashboard Delete**,
     which was deliberately NOT probed against prod (it would create a live retry loop).
   **Deferred fast-follow:** ack-on-404 (treat license GET 404 as downgrade-to-Free + 2xx ack)
   in the promotion follow-up PR.
5. **Divergences** get fixed in a small follow-up PR -- that IS the promotion review.

**Pre-flight code items: DONE 2026-07-31** (commit c5e2bff): webhook signature rejection now
logs a fixed PII-free warn; `checkoutUrl` fails closed 503 `billing_unavailable` on blank
product/plan ids; license parser tolerates both cancellation-flag spellings.

**Fly state checked 2026-07-31**: `SPA_BASE_URL` is deployed on `cadence--mlohw` (checkout
return URL will build correctly); no `FREEMIUS_*` secrets exist yet, as expected.

## 3. Merge, set secrets, deploy

1. Merge **PR #49** (`https://github.com/xamcross/cadence/pull/49`).
2. Set the secrets on Fly (restarts the machine):

   ```bash
   fly secrets set -a cadence--mlohw \
     FREEMIUS_PRODUCT_ID=<id> \
     FREEMIUS_TEAM_PLAN_ID=<id> \
     FREEMIUS_API_BEARER=<token> \
     FREEMIUS_WEBHOOK_SECRET=<secret-key>
   ```

   Optional overrides `FREEMIUS_API_BASE_URL` / `FREEMIUS_CHECKOUT_BASE_URL` exist but default
   correctly. Confirm `SPA_BASE_URL` is `https://cadenceapp.cc` (candidate links already depend
   on it; the checkout return URL is built from it).
3. **This deploy IS the early-access downgrade moment**: every workspace without an entitlement
   row reads as Free from the first request (no migration runs). Send the notice email to
   early-access workspaces BEFORE deploying.
4. Deploy with `scripts\deploy-all.ps1`. (CI deploy is broken on the empty
   `CLOUDFLARE_API_TOKEN` secret -- use the local script.)
5. No Cloudflare changes: the webhook lives under `/api/`, already proxied to Fly.

## 4. Production smoke test — EXECUTED 2026-07-31, ALL GREEN

Full lifecycle verified in production with sandbox license `2007773`:

1. Claim via Billing page recovery form -> **Team ACTIVE** (ends 2027-08-01); ATS gate
   opened; DB row `{"plan":"TEAM","status":"ACTIVE","fsLicenseId":"2007773"}`. DONE.
2. Refund executed (keep-license variant): fired `subscription.cancelled` + `payment.refund`
   only -- **no license event**, entitlement correctly stayed ACTIVE. Then dashboard license
   cancel -> `license.cancelled` webhook -> ledger row processed -> re-fetch pulled
   `is_cancelled=true` + expiration cut to now -> entitlement **EXPIRED** -> Billing page
   renders **Free plan** again. DONE (see section 2 item 4 for the full findings).
3. Next morning: `schedulerCheckpoints` shows a completed `billing-entitlement-reconcile`
   run (04:00 UTC nightly). PENDING -- check after 2026-08-01 04:00Z.
4. Recovery-refusal path (`license_already_bound` from a second workspace): NOT run --
   requires a second Free workspace; optional.
5. Cleanup DONE: sandbox purchase refunded, subscription cancelled, license cancelled.

## Reference — what the app consumes

| Env var (Fly secret) | Bound to | Behavior when blank |
|---|---|---|
| `FREEMIUS_PRODUCT_ID` | `cadence.billing.product-id` | checkout URL broken (fail-closed recommended above) |
| `FREEMIUS_TEAM_PLAN_ID` | `cadence.billing.team-plan-id` | claims refused `wrong_plan` |
| `FREEMIUS_API_BEARER` | `cadence.billing.api-bearer` | license fetch 401 -> claim 503 `billing_unavailable` |
| `FREEMIUS_WEBHOOK_SECRET` | `cadence.billing.webhook-secret` | webhook rejects all events (fail-closed) |
| `FREEMIUS_API_BASE_URL` (optional) | `cadence.billing.base-url` | defaults `https://api.freemius.com` |
| `FREEMIUS_CHECKOUT_BASE_URL` (optional) | `cadence.billing.checkout-base-url` | defaults `https://checkout.freemius.com` |

Endpoints: webhook `POST /api/webhooks/billing/freemius` (public, HMAC-gated); admin
`GET /api/internal/billing/entitlement`, `POST /api/internal/billing/checkout-session`,
`POST /api/internal/billing/claim`. Missed webhooks self-heal within 24h via the nightly
reconciliation sweep.
