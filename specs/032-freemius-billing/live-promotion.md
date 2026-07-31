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
4. **`license.deleted` / refund behavior (new sandbox question).** If a deleted/refunded
   license makes the GET return 404, the webhook currently answers 503 (retry loop) because the
   re-fetch fails; the nightly sweep isolates the row but never downgrades it. In sandbox:
   refund the test purchase and observe -- if GET 404s permanently, decide the handling
   (ack + explicit downgrade on 404 is the likely fix) in the promotion follow-up PR.
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

## 4. Production smoke test

1. From a real workspace's **Billing** page, run a **sandbox-mode** checkout -> return ->
   page flips to Team; `workspaceEntitlements` has the row.
2. Cancel the sandbox subscription in the Freemius customer portal -> webhook lands:
   entitlement status becomes CANCELLED (Billing page, or the `BILLING_ENTITLEMENT_UPDATED`
   row in `authAuditLog`).
3. Next morning: `schedulerCheckpoints` shows a completed `billing-entitlement-reconcile`
   run (04:00 UTC nightly).
4. Recovery path once: paste the sandbox license ID into "Already purchased?" from a DIFFERENT
   Free workspace and confirm the typed `license_already_bound` refusal.
5. Clean up: refund/void the sandbox purchase in the dashboard.

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
