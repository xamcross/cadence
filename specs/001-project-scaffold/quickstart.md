# Quickstart: Project Scaffold & Build Pipeline

**Feature**: 001-project-scaffold  
**Audience**: New developers joining the project

---

## Prerequisites

| Tool | Required version | Install check |
|---|---|---|
| Java | 21 | `java -version` |
| Gradle (wrapper in repo) | — | `./gradlew --version` |
| Node.js | 20 LTS | `node --version` |
| npm | 9+ | `npm --version` |
| Angular CLI | 17+ | `ng version` |
| Docker | 24+ | `docker --version` |
| Fly CLI | current | `fly version` |

---

## 1. Clone and verify

```bash
git clone <repo-url> cadence
cd cadence
```

---

## 2. Start the local backend

```bash
# Start a local MongoDB container (one-time per session)
docker run -d --name cadence-mongo -p 27017:27017 mongo:7

# Start the Spring Boot backend
./gradlew bootRun
```

The backend starts on port `8080` (application) and `8081` (management).  
Verify: `curl http://localhost:8081/actuator/health` → `{"status":"UP"}`

**First run only**: Mongock runs all changesets (creates indexes). Subsequent starts skip already-applied changesets.

---

## 3. Start the local frontend

```bash
cd frontend
npm install
ng serve
```

The Angular SPA starts on `http://localhost:4200`. It connects to the backend at `http://localhost:8080` by default (configured in `src/environments/environment.ts`).

---

## 4. Run tests

### Backend (JUnit 5 + Testcontainers — no cloud credentials needed)

```bash
./gradlew test
```

Testcontainers starts an ephemeral MongoDB container for the test JVM. Tests run against it; the container is stopped on completion. Atlas credentials are NOT required.

### Frontend (Jasmine — no backend needed)

```bash
cd frontend
ng test --watch=false
```

---

## 5. Build and verify the container image

```bash
cd backend
docker build -t cadence-backend:local .

# Publish only the application port (8080); access the management port via docker exec
docker run -d --name cadence-test \
  -e MONGODB_URI="mongodb://host.docker.internal:27017/cadence" \
  -p 8080:8080 \
  cadence-backend:local

# Wait ~30s then check health via docker exec (management port is NOT bound to host)
docker exec cadence-test curl -sf http://localhost:8081/actuator/health
```

Expected: `{"status":"UP"}`

> **Note**: Port 8081 (management) is intentionally NOT published to the host with `-p 8081:8081`. Publishing it locally normalises a pattern that would be dangerous in any network-reachable environment. Access the management port exclusively via `docker exec` locally and via the Fly private network in production.

---

## 6. Deploy to production

**First deployment only** — set Fly.io secrets (never in source or fly.toml):

```powershell
fly secrets set MONGODB_URI="mongodb+srv://..."
fly secrets set JWT_SECRET="..."
fly secrets set EMAIL_API_KEY="..."
# OAuth credentials (added when F01/F10/F11 are implemented)
# fly secrets set GOOGLE_CLIENT_ID="..."
# fly secrets set GOOGLE_CLIENT_SECRET="..."
```

**CI/CD secrets** — set these in the GitHub Actions repository secrets (Settings → Secrets and variables → Actions):

| Secret name | Purpose |
|---|---|
| `CLOUDFLARE_API_TOKEN` | Authenticates `wrangler pages deploy` in CI |
| `CLOUDFLARE_ACCOUNT_ID` | Identifies the Cloudflare account for Pages |
| `FLY_API_TOKEN` | Authenticates `fly deploy` in CI (`fly tokens create deploy`) |

**All deployments**:

```powershell
scripts\deploy-all.ps1
```

Or individually:

```powershell
scripts\db-migrate.ps1        # Verify Atlas reachable
scripts\deploy-backend.ps1    # Build + fly deploy
scripts\deploy-frontend.ps1   # ng build + Cloudflare Pages
```

---

## 7. CI pipeline overview

Every pull request runs:

| Step | What it does |
|---|---|
| `Backend tests` | `./gradlew test` with Testcontainers MongoDB |
| `Frontend tests` | `ng test --watch=false` headless |
| `Lighthouse CI` | Mobile simulation on candidate-facing routes; fails if Performance < 85 |
| `PII log scan` | `grep` on CI test output for email/name patterns; fails on any match |

CI workflow file: `.github/workflows/ci.yml`

---

## 8. Troubleshooting

**Backend won't start — "connection refused" to MongoDB**  
→ Ensure the local MongoDB container is running: `docker ps | grep cadence-mongo`  
→ Restart it: `docker start cadence-mongo`

**Backend won't start — "missing required secret"**  
→ Check that all required environment variables are set. In local dev, create `backend/src/main/resources/application-local.yml` (git-ignored) with the missing values.

**Testcontainers error — "Docker daemon not reachable"**  
→ Ensure Docker Desktop is running. On Linux, check `sudo systemctl status docker`.

**Mongock fails — "duplicate index key error"**  
→ A conflicting index with a different definition already exists. Drop the conflicting index manually in Atlas or the local container, then restart. This indicates a changeset was modified after execution — never modify an existing Mongock changeset; add a new one instead.

**Angular build fails with missing API URL in production bundle**  
→ The production URL is injected at build time by a Node.js pre-build script that writes `environment.prod.ts` from the `CADENCE_API_URL` environment variable. In Cloudflare Pages, ensure `CADENCE_API_URL` is set under Settings → Environment Variables. In local testing of the production build, set `CADENCE_API_URL=http://localhost:8080` before running `ng build --configuration production`.

---

## Environment files

| File | Tracked | Purpose |
|---|---|---|
| `backend/src/main/resources/application.yml` | ✅ Yes | Shared configuration; no secrets |
| `backend/src/main/resources/application-test.yml` | ✅ Yes | Test overrides (Testcontainers replaces `spring.data.mongodb.uri`) |
| `backend/src/main/resources/application-local.yml` | ❌ `.gitignore` | Local dev overrides (never committed) |
| `frontend/src/environments/environment.ts` | ✅ Yes | Development environment (localhost URLs) |
| `frontend/src/environments/environment.prod.ts` | ✅ Yes | Production environment (placeholder URL; overwritten at build time by `CADENCE_API_URL` env var) |
