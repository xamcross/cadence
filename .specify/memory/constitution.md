<!--
=============================================================================
SYNC IMPACT REPORT
=============================================================================
Version change:       1.0.0 -> 1.1.0   [MINOR: two new principles added]

Principles added:
  V.   Encoding & Verification Discipline (Windows-safe)  [NON-NEGOTIABLE]
  VI.  Mandatory Multi-Role Sub-Agent Review

Principles renumbered (content unchanged):
  V.  Test-First & Acceptance-Driven        ->  VII. Test-First & Acceptance-Driven
  VI. Security & Privacy by Default         -> VIII. Security & Privacy by Default
  VII. Candidate-Experience First           ->   IX. Candidate-Experience First

Sections modified:
  - "Development Workflow & Quality Gates"
      * Constitution Check table: gates C5 and C6 added
      * Definition of Done: two new checklist items added

Sections removed:  n/a

Templates:
  ✅ .specify/templates/plan-template.md
       -- Constitution Check placeholder covers all gates via the 6-point
          checklist now defined in the Governance section.  No structural
          change required; feature plans fill against the updated 9 principles.
  ✅ .specify/templates/spec-template.md
       -- No changes required; section structure still aligns.
  ✅ .specify/templates/tasks-template.md
       -- No changes required; path conventions and phase structure still align.

Deferred TODOs:
  - None. All placeholders resolved.
=============================================================================
-->

# Cadence Constitution

## Core Principles

### I. MVP Scope Enforcement (YAGNI)

Only capabilities defined in **§11 MVP** of `cadence-product-specification.md`
MUST be built in this phase:

- **Flow A1** — single-stage scheduling (recruiter initiates → candidate picks slot →
  all parties confirmed)
- **Flow A3** — one-link reschedule / cancellation with automatic propagation
- **Flow A4** — no-show defense (confirmation cascade, recruiter alert, slot release)
- **Calendar sync** — Google Calendar and Microsoft 365 / Outlook (bi-directional)
- **Email channel only** — no SMS or WhatsApp in MVP
- **Template library** — invitations, confirmations, reminders, holds, rejections
- **Candidate Status Page** — live stage, next steps, expected dates; private link,
  no login required
- **SLA nudges** — draft-for-recruiter-approval mode only; auto-send is deferred
- **Interviewer feedback forms** — structured scorecard with reminder escalation
- **ATS integrations** — Greenhouse and Lever; standalone CSV import mode
- **Core dashboard** — time-to-schedule, no-show rate, current silence list

Everything below is **explicitly deferred** and MUST NOT be started without a
constitution amendment:

| Deferred capability | Target phase |
|---|---|
| Multi-stage loop solver (Flow A2) | v1.5 |
| SMS / WhatsApp channel | v1.5 |
| Voice-to-scorecard capture | v1.5 |
| Auto-send SLA policies | v1.5 |
| Interviewer load-balancing analytics | v1.5 |
| Mobile companion app (iOS/Android) | v1.5 |
| Agency multi-client workspaces | v2 |
| Additional ATS connectors beyond Greenhouse/Lever | v2 |
| Candidate pulse surveys | v2 |
| Public REST API / webhooks | v2 |
| Advanced forecasting / time-to-fill analytics | v2 |

**Rationale**: The product's sole near-term goal is a public, working MVP at
minimal cost. Each deferred item adds surface area, integration risk, and
delivery time without advancing that mandate.

### II. End-to-End Delivery (No Stubs)

Every feature MUST ship as a **fully working, end-to-end flow** or MUST NOT be
started. A flow is "complete" when:

1. The Angular frontend renders the relevant UI and issues real HTTP requests.
2. The Spring Boot backend processes those requests and persists/reads MongoDB.
3. Any external integration (calendar, ATS, email) either calls the real provider
   or a locally-runnable stub that is explicitly labelled "integration-pending"
   in the feature spec.
4. A real user (or an automated acceptance test) can execute the entire scenario
   from browser to database and observe the correct outcome.

Stubs, mocked-out endpoints returned as done, placeholder Angular screens backed
by hardcoded JSON, or backend-only work presented as a shipped feature are
**PROHIBITED**. Partial work MUST remain tracked as in-progress and MUST NOT be
marked done or merged to `main` as a completed increment.

**Rationale**: Half-wired work creates the illusion of progress while producing
nothing demonstrable. The MVP's credibility — with users and investors — depends
on every shipped increment being verifiable by a real person.

### III. Fixed Technology Stack

The following stack is **mandatory**. No layer may be substituted without a
formal, approved constitution amendment filed before writing a single line of
replacement code.

| Layer | Technology | Required version |
|---|---|---|
| Frontend | Angular -- standalone components, no NgModules | 17+ |
| Backend | Java + Spring Boot -- single deployable JAR | Java 21, Spring Boot 3.x |
| Database | MongoDB | 7.x |
| Calendar APIs | Google Calendar API, Microsoft Graph API | current stable |
| Email delivery | Spring Mail + provider SDK (e.g. SendGrid, SES) | -- |
| ATS connectors | Greenhouse REST API, Lever REST API | current stable |

The following substitutions are **prohibited without an amendment**:

- Frontend: React, Vue, Next.js, Svelte, or any non-Angular framework
- Backend: Kotlin, Python, Node.js, .NET, Quarkus, or Micronaut
- Database: PostgreSQL, MySQL, DynamoDB, or any non-MongoDB store
- Additional stateful services: Redis, Elasticsearch, separate caching tiers

**Rationale**: Stack discipline eliminates decision churn, enables knowledge
reuse across the team, and keeps infrastructure simple enough for a
single-instance deployment within budget.

### IV. Single-Instance Deployment Topology

The production deployment is exactly:

- **1 x Spring Boot JAR** -- packaged as a Docker image, deployed as a single
  Fly Machine on **Fly.io** (one region, one instance)
- **1 x MongoDB Atlas cluster** -- the single managed data store (Atlas M10+
  single-region; MongoDB 7.x)
- **1 x Angular SPA** -- built as a static site, served via **Cloudflare Pages**

**Atlas replica set note**: MongoDB Atlas provisions a 3-node replica set by
default. This is acceptable -- it is a fully managed concern with zero
operational overhead and does not violate the intent of this principle. The
prohibition below targets self-managed horizontal scaling, not Atlas's managed
replication.

**Fly.io note**: Fly.io is not Kubernetes, Docker Swarm, or Nomad. A single
Fly Machine deployment is the target. Multi-machine or auto-scaling
configuration MUST NOT be enabled for the MVP.

The following are **prohibited** for the MVP:

- Microservices or any decomposition into separate backend processes
- Kubernetes, Docker Swarm, Nomad, or any container orchestration platform
- Message queues or event brokers (Kafka, RabbitMQ, Redis Streams, SQS)
- Multi-region or multi-availability-zone configuration (applies to both
  Fly.io and Atlas -- single region only)
- Separate caching tiers as standalone services (Redis, Memcached)
- Self-managed MongoDB replica sets, sharding, or distributed clusters
- Fly.io auto-scaling or multi-machine deployment

**Secrets management**: All runtime secrets (Atlas connection string, email
provider API key, OAuth client credentials, JWT signing key) MUST be stored
as **Fly.io secrets** (`fly secrets set`), not in source code or committed
configuration files.

**Async work rule**: If a background/scheduled operation is required (e.g.
sending SLA-breach reminders, dispatching confirmation emails), it MUST be
implemented using Spring's built-in `@Scheduled` / `TaskScheduler` persisting
job state to MongoDB -- not by introducing a queue broker.

**Rationale**: Every additional moving part multiplies operational cost,
deployment complexity, and failure surface. The MVP budget and single-developer
(or small-team) capacity cannot absorb that overhead. Scaling is explicitly
deferred to post-MVP phases.

### V. Encoding & Verification Discipline (Windows-safe)

**This principle is NON-NEGOTIABLE. A violation has already caused a real outage.**

**Encoding rules for Windows-executed scripts** (`.ps1`, `.cmd`, `.bat`):

- Files MUST be pure ASCII. No em-dash, en-dash, curly quotes, ellipsis,
  non-breaking space, or any other non-ASCII character is permitted anywhere in
  the file, including comments.
- If non-ASCII is genuinely unavoidable, the file MUST be saved as UTF-8 with
  BOM or as UTF-16. This exception MUST be documented in the task notes.

**Encoding rules for other generated files**:

- `.env` files written by scripts MUST be written without a BOM.
- Line endings follow `.gitattributes`:
  - Linux-consumed files (Dockerfile, `*.conf`, `*.yml`, `.env`) MUST use LF.
  - `*.ps1`, `*.cmd`, `*.bat` MUST use CRLF.

**Verification gate -- "done" requires byte-level confirmation, not visual review**:

Before any script or source file change is marked complete, the following MUST
have been performed:

1. **Non-ASCII scan**: run a byte-level scan of every executed script for
   non-ASCII characters (expected result: zero matches). Reading the file in a
   text editor or rendered Markdown does NOT satisfy this gate.
2. **Parse or compile**: when a runtime is available, parse or compile the
   changed file (`pwsh -NoProfile -Command "Get-Command -Syntax ..."` or
   `pwsh -File <script>` for PowerShell; `mvn test-compile` or `mvn test` for
   Java). If no runtime is available, the change MUST be explicitly labelled
   **static-only** in the task notes. Claiming a script "works" when it was only
   read is PROHIBITED.

**Rationale**: Windows PowerShell 5.1 decodes BOM-less files as Windows-1252,
mis-decoding UTF-8 punctuation (e.g. curly quotes, em-dashes) into string
delimiters that silently break the whole script. Visual review cannot catch
byte-level encoding bugs; only a scan or parse can.

### VI. Mandatory Multi-Role Sub-Agent Review

Every non-trivial task MUST be reviewed by at least three role sub-agents
(selecting from: QA, Business Analyst, DevOps, front-end lead, back-end lead,
security lead, or other roles appropriate to the task) before it is considered
done.

**Rules**:

- This review is **mandatory and automatic**: it runs as part of completing the
  task without pausing to ask the user for permission. It is a required step,
  not an optional follow-up.
- Where an encoding or parse-class of bug is possible, the review MUST include
  an actual scan/parse/compile per Principle V. Sub-agent or human reading alone
  does NOT satisfy that verification gate.
- All findings MUST be either applied (fixed in the same task) or explicitly
  reported to the user before the task is closed. Silently discarding findings
  is PROHIBITED.

**Rationale**: Independent role perspectives catch product, quality, and
operational defects early and cheaply, which matters most on a lean MVP with no
dedicated QA staff.

### VII. Test-First & Acceptance-Driven

All non-trivial backend logic MUST be covered by tests written against the
**acceptance criteria in the feature spec** before implementation begins:

1. Write the test (derived from the spec's acceptance scenarios) -- it MUST fail.
2. Write the minimum production code to make it pass.
3. Refactor without breaking green.

**Required test types**:

| Type | Scope | Tooling |
|---|---|---|
| Unit | Business logic, rule engine, SLA calculations | JUnit 5 + Mockito |
| Integration | Spring service + MongoDB repository | Testcontainers (MongoDB) |
| API contract | Each REST endpoint -- shape, status codes, error envelope | MockMvc / RestAssured |
| Frontend unit | Non-trivial component logic, form validation, state | Jasmine / Jest |
| E2E | Critical candidate paths (scheduling flow, status page) | Cypress or Playwright |

Tests are not optional for business-logic services or acceptance-criteria paths.
A feature plan MUST list at least one acceptance test per user story.

**Rationale**: The scheduling and reminder paths are time-critical; a silent
regression (missed reminder, double-booking) is a product failure. Tests catch
those regressions at the cheapest possible moment.

### VIII. Security & Privacy by Default

Every implementation decision MUST assume GDPR applicability.

- **Logging**: Candidate name, email, phone, and any free-text field are NEVER
  written to application logs in plaintext. Use anonymised identifiers in logs.
- **Encryption**: All personal data at rest MUST use MongoDB encryption-at-rest
  (CSFLE or server-side encryption); all data in transit MUST use TLS 1.2+.
- **Calendar scopes**: OAuth requests MUST default to free/busy-only scope.
  Requesting broader scopes requires explicit user consent and a spec-documented
  justification approved in the feature plan.
- **RBAC**: Five roles -- Admin, Recruiter, Hiring Manager, Interviewer,
  Read-only. Every API endpoint MUST enforce the minimum required role. No
  anonymous access to internal endpoints.
- **Authentication**: SSO (SAML/OIDC) is the primary authentication path.
  Email/password is permitted as MVP fallback only and MUST NOT be the default
  for workspace login in v1.5+.
- **Candidate consent**: Email-channel consent MUST be recorded per candidate
  record at the time of first communication and checked before sending.
- **Right to erasure**: A recruiter/admin-triggered erasure workflow MUST be
  included in the MVP -- it MUST wipe personal data fields and audit the deletion.
- **Data retention**: Configurable per workspace; default retention period MUST
  be displayed during workspace setup.

**Rationale**: Candidate data is sensitive personal data under GDPR. A data
incident post-launch is an existential risk for a product built on candidate
trust. Privacy by default is cheaper than retrofitting.

### IX. Candidate-Experience First

The candidate interaction surface is Cadence's primary differentiator and MUST
be held to the highest quality standard:

- **No login or app install required** for any candidate-facing action: slot
  selection, rescheduling, cancellation, and status page access.
- All candidate-facing pages MUST be **WCAG 2.2 AA compliant** and
  **mobile-first** (min 375 px viewport, touch targets >= 44 px).
- The scheduling page MUST load in **< 2 seconds on a 4G connection**
  (target Lighthouse Performance >= 85 on mobile simulation).
- The Candidate Status Page MUST display the current stage, a plain-English
  description of what happens next, and an expected date. A status of
  "we'll be in touch" with no date is **not acceptable**.
- **No automated message** MAY be sent to a candidate without recruiter
  one-click approval in the MVP. Auto-send is deferred to v1.5.
- All candidate-facing UI strings MUST be externalized for localization
  (Angular i18n or `$localize`); at minimum English is required for MVP.

**Rationale**: Candidate drop-off and ghosting are the core problems Cadence
solves. If the candidate UX is degraded, the product fails its mission regardless
of backend quality.

## Stack & Deployment Constraints

### Reference Source Layout

```text
cadence/
+-- backend/                          # Spring Boot single JAR
|   +-- src/
|   |   +-- main/java/com/cadence/
|   |   |   +-- api/                  # REST controllers (@RestController)
|   |   |   +-- domain/               # Domain models (POJOs / MongoDB docs)
|   |   |   +-- repository/           # Spring Data MongoDB repositories
|   |   |   +-- service/              # Business logic services
|   |   |   +-- scheduler/            # @Scheduled tasks (reminders, SLA)
|   |   |   +-- integration/          # Calendar, ATS, email provider adapters
|   |   |   +-- config/               # Spring configuration classes
|   |   +-- resources/
|   |       +-- application.yml
|   |       +-- application-test.yml
|   +-- src/test/java/com/cadence/    # JUnit 5 + Testcontainers
|
+-- frontend/                         # Angular 17+ SPA
|   +-- src/
|   |   +-- app/
|   |       +-- core/                 # Auth, HTTP interceptors, route guards
|   |       +-- features/             # Feature directories (scheduling, dashboard)
|   |       +-- shared/               # Standalone components, pipes, directives
|   +-- e2e/                          # Cypress / Playwright acceptance tests
|
+-- docs/
    +-- specs/                        # Feature specs, plans, and task lists
        +-- [###-feature-name]/
            +-- spec.md
            +-- plan.md
            +-- tasks.md
```

### Dependency Policy

- **Backend**: Use Spring Boot starters. Any additional library (JWT library,
  HTTP client) MUST be recorded in the feature plan with a one-line justification.
  No infrastructure SDK (Kafka client, Redis client, K8s SDK) may be added.
- **Frontend**: Angular CDK and Angular Material for UI primitives. Tailwind CSS
  utility classes are permitted. No additional third-party component library
  (e.g. PrimeNG, NG-ZORRO) without an amendment.
- **Integration adapters**: Google Calendar, Microsoft Graph, Greenhouse, Lever,
  and email provider SDKs MUST be wrapped in a domain interface
  (`CalendarProvider`, `AtsConnector`, `EmailSender`). Business logic MUST
  depend on the interface, not the SDK class, enabling provider swap without
  touching service code.

## Development Workflow & Quality Gates

1. **Spec before code**: A feature MUST have an approved `spec.md` -- with user
   stories, acceptance scenarios, and success criteria -- before any task is
   created or implementation begins.

2. **Plan before implementation**: A `plan.md` with a passing Constitution Check
   MUST exist before Phase 0 research. The plan MUST document the selected source
   structure and list any Complexity Tracking violations.

3. **Constitution Check** (mandatory gate in every plan):

   | Gate | Question | Fail action |
   |---|---|---|
   | C1 | Is this feature within MVP scope (spec §11)? | Defer or amend constitution |
   | C2 | Does it require a new service, queue, or replica? | STOP -- amend topology first |
   | C3 | Does it expose candidate personal data to unauthorized roles? | Redesign RBAC |
   | C4 | Does it add a dependency outside the fixed stack? | STOP -- amend stack first |
   | C5 | Do any new/modified Windows scripts contain non-ASCII characters? | Fix encoding before marking done |
   | C6 | Is the multi-role sub-agent review (>=3 roles) scheduled for task close? | Add review step to task plan |
   | C7 | Does implementation download any build tool, runtime, or CLI distribution? | STOP — use highest already-installed version; never download tools |

4. **Definition of Done** for a feature increment:

   - [ ] Full end-to-end flow works: Angular -> Spring Boot -> MongoDB ->
         external provider (calendar / ATS / email) as applicable.
   - [ ] Unit + integration + contract tests are green in CI.
   - [ ] Candidate-facing screens pass WCAG 2.2 AA automated scan (axe-core).
   - [ ] Performance: scheduling page Lighthouse >= 85 (mobile).
   - [ ] PR reviewed and approved; no direct push to `main`.
   - [ ] No plaintext PII in application logs (verified by log-grep in CI).
   - [ ] All new/modified Windows scripts (.ps1/.cmd/.bat) scanned for non-ASCII
         (zero matches); parse/compile result recorded or change labelled static-only.
   - [ ] Multi-role sub-agent review (>=3 roles) completed; findings applied or
         reported before task closure.

5. **Complexity justification**: Any architectural pattern beyond the minimum
   needed (e.g., adding an event bus for a single notification, a strategy
   pattern for a one-off rule) MUST appear in the plan's Complexity Tracking
   table with a written justification. Unapproved complexity discovered in review
   MUST be removed or justified before merge.

6. **Deployment workflow** (run after every completed feature increment):

   All deployment scripts live in `scripts/`. They MUST remain pure ASCII with
   CRLF line endings (Principle V). After merging a feature to `main`:

   | Step | Script | When to run |
   |---|---|---|
   | 1. DB check | `scripts\db-migrate.ps1` | Always -- verify Atlas is reachable before deploying |
   | 2. Backend | `scripts\deploy-backend.ps1` | Any backend change (Java, config, Mongock migration) |
   | 3. Frontend | `scripts\deploy-frontend.ps1` | Any Angular change |
   | All-in-one | `scripts\deploy-all.ps1` | Full release (runs steps 1-3 in order) |

   **Quick reference by feature area**:

   | Feature area | Scripts to run |
   |---|---|
   | Backend-only change | `db-migrate.ps1` then `deploy-backend.ps1` |
   | Frontend-only change | `deploy-frontend.ps1` |
   | New MongoDB index or Mongock changeset | `db-migrate.ps1` then `deploy-backend.ps1` (Mongock applies on startup) |
   | Full feature (Angular + Spring Boot + DB) | `deploy-all.ps1` |

   **Secrets**: All credentials (Atlas URI, JWT key, OAuth secrets, email API key)
   MUST be stored as Fly.io secrets before the first backend deploy:

   ```
   fly secrets set MONGODB_URI="mongodb+srv://..."
   fly secrets set JWT_SECRET="..."
   fly secrets set EMAIL_API_KEY="..."
   ```

   Never commit secrets to source or `fly.toml`.

   **Migration behaviour**: Mongock changesets apply automatically on Spring Boot
   startup inside `deploy-backend.ps1`. No separate migration step is needed
   unless verifying Atlas connectivity first with `db-migrate.ps1`.

### X. Zero-Download Implementation Rule

**This principle is NON-NEGOTIABLE.**

During implementation, **no build tool, runtime, SDK distribution, or CLI installer may be downloaded**. This prohibition covers any automated fetch triggered by agent actions, including but not limited to:

- Gradle wrapper auto-downloading a new Gradle distribution (via `gradlew` / `gradlew.bat`)
- `npm install -g` or `npx` fetching CLI tools not already on the system
- `Invoke-WebRequest`, `curl`, or `wget` fetching runtimes or build tools
- Package manager bootstrappers fetching themselves (Scoop, Chocolatey, winget, etc.)
- Any other mechanism that transfers tool binaries over the network during a coding session

**Required behavior before using any build tool or wrapper**:

1. **Discover what is already installed.** Check the local cache and PATH before referencing any version:
   - Gradle: inspect `~/.gradle/wrapper/dists/` — use the highest version already present
   - Node / npm: run `node --version` and `npm --version`
   - Java: run `java -version`; check `JAVA_HOME`
   - Angular CLI: check npm global cache
2. **Use the highest already-available version.** Never hardcode a version number from training data; always confirm the actual installed version on this machine.
3. **If a required tool is genuinely absent**: STOP. Inform the user which tool is missing and the exact command to install it. Do NOT install it yourself.
4. **Wrapper config must match cached distributions.** When writing `gradle-wrapper.properties` or equivalent config, set `distributionUrl` (or equivalent) to the highest already-cached distribution to guarantee zero downloads on first wrapper invocation.

**Rationale**: Two simultaneous 125 MB+ Gradle downloads were triggered mid-session by automated wrapper invocations, blocking all work, consuming bandwidth, and causing user-facing disruption. The system always has the required tools installed; the agent's job is to find and use them, not to provision them.

## Governance

This constitution supersedes all other practices, README instructions, and
verbal agreements for the Cadence MVP phase.

**Amendment procedure**:

1. Open a pull request that edits `.specify/memory/constitution.md` only.
2. The PR description MUST state: (a) what changes, (b) why it is necessary,
   and (c) what migration is required for any in-flight feature work.
3. The project owner MUST approve the PR before it merges.
4. After merge: update `LAST_AMENDED_DATE` to today; increment
   `CONSTITUTION_VERSION` per the versioning policy below.

**Versioning policy**:

- **MAJOR** -- backward-incompatible governance change: removal of a principle,
  stack substitution, or topology change.
- **MINOR** -- new principle or section added; material expansion of existing
  guidance.
- **PATCH** -- clarifications, wording fixes, typo corrections, non-semantic
  refinements.

**Compliance review**: Every feature plan's Constitution Check gate MUST be
completed. A failing gate MUST be resolved -- either by adjusting the feature
scope or by filing a constitution amendment -- before any implementation task is
started. A failing gate discovered during PR review MUST block merge.

**Version**: 1.3.0 | **Ratified**: 2026-06-13 | **Last Amended**: 2026-06-13
