# Cadence Deployment Runbook

Step-by-step manual setup required to deploy Cadence (feature `001-project-scaffold`).
Last updated: 2026-06-13.

## What's automated vs. manual

The repo already provides: `fly.toml`, `backend/Dockerfile`, `lighthouserc.json`, the four
`scripts/*.ps1`, and `.github/workflows/ci.yml` (auto-deploys on push to `main`). Everything
below is the **manual setup** those automations depend on (accounts, domains, secrets) — the
parts that cannot be scripted.

> **Scaffold reality check:** the backend currently reads **only `MONGODB_URI`** at runtime.
> `JWT_SECRET`, `EMAIL_API_KEY`, and OAuth credentials are listed by the constitution for
> *later* features and are **not consumed yet** — don't set them for this first deploy. There
> are also no API endpoints and no candidate-facing pages yet, so this deploy proves the
> pipeline, not a user-facing app.

---

## Phase 0 — Install CLIs & create accounts (one-time)

1. **Fly.io**: create an account at fly.io, install `flyctl`, then `fly auth login`.
2. **MongoDB Atlas**: create an account at cloud.mongodb.com.
3. **Cloudflare**: create an account, then `npm install -g wrangler` and `wrangler login`.
4. **mongosh** (optional, for the `db-migrate.ps1` connectivity check): install from mongodb.com.

## Phase 1 — Provision MongoDB Atlas

1. Create a cluster — **M10+, single region** (constitution Principle IV; the M0 free tier
   works for a smoke test but is not the target).
2. **Database Access** → add a database user (username + strong password). Save them.
3. **Network Access** → add an allowlist entry. Fly egress IPs are dynamic, so either:
   - allow `0.0.0.0/0` and rely on user/password auth (simplest; acceptable for MVP), **or**
   - set up an Atlas private endpoint / Fly static egress IP (more secure, more setup).
4. Confirm **Encryption at Rest** is on (Atlas default — satisfies Principle VIII).
5. Copy the **connection string**:
   `mongodb+srv://<user>:<pass>@<cluster>.mongodb.net/cadence?retryWrites=true&w=majority`
   (note the `/cadence` database name).

## Phase 2 — Create the Fly app & set the secret

1. The app name in `fly.toml` is **`cadence`** and must be globally unique on Fly. If it is
   taken: `fly apps create <your-unique-name>` and change `app = "..."` in `fly.toml`.
   Otherwise: `fly apps create cadence`.
2. (Optional) change `primary_region = "iad"` in `fly.toml` to a region near your users.
3. Set the **one** runtime secret the app needs now:

   ```
   fly secrets set MONGODB_URI="mongodb+srv://<user>:<pass>@<cluster>.mongodb.net/cadence?retryWrites=true&w=majority" --app cadence
   ```

   (Secrets persist on the app and are injected at runtime. Never put this in `fly.toml`.)

## Phase 3 — Domains (optional for first deploy)

You can deploy **without registering a domain**: Fly gives you `https://<app>.fly.dev` and
Cloudflare Pages gives `https://<project>.pages.dev`. For production:

1. **Register a domain** with any registrar (e.g. `cadence.app`).
2. **Backend (Fly)**: pick `api.<yourdomain>`. Run
   `fly certs add api.<yourdomain> --app cadence`, then add the DNS records Fly prints
   (an `A`/`AAAA` or `CNAME` to your app) at your DNS provider.
3. **Frontend (Cloudflare Pages)**: in the Pages project → **Custom domains** → add
   `<yourdomain>` (or `www`), following Cloudflare's DNS instructions.
4. The committed `environment.prod.ts` placeholder is `https://api.cadence.example.com`; the
   real value is injected at build time via `CADENCE_API_URL` (Phases 4-5), so you don't edit
   the file.

## Phase 4 — Cloudflare Pages project

1. Create a Pages project named **`cadence`** (must match `--project-name cadence` in the
   deploy script and CI). For CI/wrangler-driven deploys you can create it via the dashboard;
   "Connect to Git" is optional since CI runs `wrangler pages deploy`.
2. Note your **Account ID** (Cloudflare dashboard → right sidebar).
3. Create an **API token**: My Profile → API Tokens → Create → permission
   **Cloudflare Pages: Edit**. Save the token.
4. Decide the backend URL the SPA should call:
   - quick path: `https://<app>.fly.dev`
   - production: `https://api.<yourdomain>`

## Phase 5 — GitHub Actions secrets (enables auto-deploy on merge to `main`)

In the GitHub repo → Settings → Secrets and variables → Actions → add:

| Secret | Value | How to get it |
|---|---|---|
| `FLY_API_TOKEN` | Fly deploy token | `fly tokens create deploy --app cadence` |
| `CLOUDFLARE_API_TOKEN` | from Phase 4.3 | Cloudflare API token (Pages: Edit) |
| `CLOUDFLARE_ACCOUNT_ID` | from Phase 4.2 | Cloudflare dashboard |
| `CADENCE_API_URL` | from Phase 4.4 | your backend URL |

## Phase 6 — First deploy

**Prerequisite:** the scaffold work is on branch `001-project-scaffold`. CI auto-deploy triggers
on push to **`main`**, so open a PR and merge it (or push to `main`). On merge, CI runs
`deploy-backend` (Fly, `--remote-only`) and `deploy-frontend` (Cloudflare Pages).

**Or deploy manually** from your machine:

```powershell
$env:MONGODB_URI = "mongodb+srv://...cadence..."   # for the pre-check only
.\scripts\db-migrate.ps1          # 1. confirm Atlas reachable + show Mongock changelog
.\scripts\deploy-backend.ps1      # 2. remote build on Fly + health-poll /actuator/health
.\scripts\deploy-frontend.ps1     # 3. ng build + wrangler pages deploy
# or all three in order:
.\scripts\deploy-all.ps1
```

Mongock creates the 6 indexes automatically on backend startup — no separate migration step.

## Phase 7 — Verify

```powershell
fly status --app cadence
fly logs --app cadence                         # confirm Mongock applied changesets, no errors
fly proxy 8081:8081 --app cadence              # then in another shell:
curl http://localhost:8081/actuator/health     # expect {"status":"UP"}
```

Open the Pages URL (`https://<project>.pages.dev` or your domain) — it should render the
`Cadence` page.

---

## Heads-up for the *next* feature (not blockers now)

- **CORS**: once the Angular SPA makes real cross-origin calls to the Fly backend, you'll need
  CORS config on the backend allowing the Pages origin. There is none yet (no endpoints exist);
  add it with the first API feature.
- **Management port**: `8081` is intentionally **not** published to the public internet. The
  `fly.toml` top-level `[checks]` block reaches it over Fly's internal network; `fly proxy` is
  how *you* reach it. Do not add a public `-p 8081`.
- **`JWT_SECRET` / OAuth / `EMAIL_API_KEY`**: set these via `fly secrets set` only when the auth
  and email features land.
