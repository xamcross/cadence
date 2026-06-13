# Feature Specification: Project Scaffold & Build Pipeline

**Feature Branch**: `001-project-scaffold`  
**Created**: 2026-06-13  
**Status**: Draft  
**Backlog refs**: F00, F00.1, F00.2  
**Input**: User description: "F00 — Project Scaffold and Build Pipeline: Angular 17 SPA + Spring Boot 3.x backend + MongoDB Atlas setup with Docker dev environment, CI/CD pipeline for Cloudflare Pages and Fly.io deployment, observability baseline, and graceful shutdown configuration."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Working Local Development Environment (Priority: P1)

A developer joining the project can start the full stack on their machine using two commands — one for the backend, one for the frontend — without configuring cloud credentials or a remote database.

**Why this priority**: Without a reproducible local environment, no feature can be developed or tested. This is the prerequisite for all other work.

**Independent Test**: Run the backend start command with a local MongoDB Docker container active, then run the frontend start command. The app is live on the local frontend port and the backend health endpoint responds on the management port.

**Acceptance Scenarios**:

1. **Given** a freshly cloned repository with Docker running, **When** a developer runs the backend start command, **Then** the backend starts, connects to a local MongoDB container, and the health endpoint returns a healthy status.
2. **Given** the backend is running, **When** a developer runs the frontend start command, **Then** the Angular SPA starts and can communicate with the local backend with no TypeScript compilation errors.
3. **Given** the local stack is running, **When** a developer makes a code change, **Then** both the Angular hot-reload and the backend dev restart cycle complete without manual intervention.

---

### User Story 2 - Automated Test Execution Without Cloud Dependencies (Priority: P1)

A developer can run the full backend test suite and the Angular unit tests entirely offline, with no cloud database credentials required, using ephemeral containers for database tests.

**Why this priority**: Tests that require cloud credentials cannot run in CI or on developer machines without secret distribution — they block every pull request and every developer's workflow.

**Independent Test**: Run the backend test command from a clean environment with no Atlas credentials; all tests pass. Run the Angular unit test command; all tests pass.

**Acceptance Scenarios**:

1. **Given** no cloud database credentials are present, **When** the backend test suite is executed, **Then** integration tests spin up an ephemeral database container automatically, run all tests against it, and tear it down on completion.
2. **Given** a developer runs the Angular test command, **Then** all unit tests execute in a headless browser and produce a pass/fail report without requiring a running backend.
3. **Given** a developer runs both test suites back-to-back, **Then** no test leaves behind running containers, temp files, or modified shared state.

---

### User Story 3 - Containerised Backend for Deployment (Priority: P2)

The backend application can be packaged into a container image that passes a health check, enabling consistent deployment to any container-hosting environment.

**Why this priority**: Without a container image, deployment to the production host is manual and error-prone. This is the prerequisite for the deployment pipeline.

**Independent Test**: Build a container image from the repository, run it locally, and hit the health endpoint — it returns healthy.

**Acceptance Scenarios**:

1. **Given** the repository source, **When** a developer builds the container image, **Then** the build completes without errors.
2. **Given** a built image, **When** the container is started, **Then** the application starts and the health check endpoint on the management port returns a healthy status within 60 seconds.
3. **Given** a running container that receives a shutdown signal, **Then** the application completes in-flight requests within 30 seconds before exiting cleanly.

---

### User Story 4 - Backend Deployment to Production Host (Priority: P2)

A developer can deploy the latest backend to the production hosting environment using a single command without manually copying files or managing server state.

**Why this priority**: Single-command deployment eliminates manual release processes and makes releases repeatable and auditable.

**Independent Test**: Execute the deploy command; within 5 minutes the production health endpoint returns a healthy status with the new version.

**Acceptance Scenarios**:

1. **Given** a built container image, **When** a developer runs the deploy command, **Then** the new version is deployed and healthy on the production host within 5 minutes.
2. **Given** all runtime secrets (database connection, email provider key, OAuth credentials, signing keys), **When** the application starts in production, **Then** it retrieves all secrets from the hosting environment's secret store — no secrets appear in the repository or deployment configuration files.
3. **Given** a deployment in progress, **When** the previous version receives a health check, **Then** the old version continues serving traffic until the new version passes its own health check.

---

### User Story 5 - Automatic Frontend Deployment on Merge (Priority: P2)

Merging code to the main branch automatically builds and deploys the Angular SPA to the CDN-hosted frontend without any manual action from the developer.

**Why this priority**: Manual frontend deployments are slow and introduce human error. Automatic deployment on merge keeps production continuously up to date.

**Independent Test**: Merge a trivial change to main; within 5 minutes the production frontend URL serves the updated build.

**Acceptance Scenarios**:

1. **Given** a pull request merged to the main branch, **When** the automated pipeline runs, **Then** the Angular SPA is built in production mode and deployed to the CDN frontend host within 5 minutes.
2. **Given** a build failure (such as a TypeScript error), **When** the pipeline runs, **Then** the deployment is blocked and the developer receives a failure notification.
3. **Given** different environments (preview, production), **When** a build is triggered, **Then** each environment receives the correct API base URL injected at build time — not a hardcoded value from another environment.

---

### User Story 6 - Database Indexes Created Before Traffic (Priority: P2)

All database indexes required by the application are created automatically when the application starts, before it begins accepting user requests, so queries are performant from the first request.

**Why this priority**: Missing indexes cause slow queries that worsen under load. Creating them at startup eliminates the need for manual index-management steps during deployment.

**Independent Test**: Start the application against a fresh database; inspect the database index list — all required indexes are present. Restart the application; the index creation step is a no-op and no errors are logged.

**Acceptance Scenarios**:

1. **Given** a fresh database with no indexes, **When** the application starts, **Then** all required indexes are created before the health endpoint reports healthy.
2. **Given** a database where indexes already exist, **When** the application restarts, **Then** the index-creation step completes without errors or duplicate-index warnings.
3. **Given** an integration test run, **When** tests verify index presence, **Then** all documented indexes are confirmed present on the correct collections.

---

### User Story 7 - Structured Logs and Health Metrics Without PII (Priority: P2)

Operations staff and developers can monitor the running application through a structured log stream and health/metrics endpoints, without any personal information appearing in logs.

**Why this priority**: Unstructured logs are hard to search and alert on. PII in logs creates a compliance risk. Both problems must be solved at the scaffold stage before any feature starts writing logs.

**Independent Test**: Start the application, trigger several requests, inspect the log output — all entries are parseable structured data, no email addresses or candidate names appear. Hit the health and metrics endpoints on the management port; both return 200. Confirm those same endpoints return not-found on the public port.

**Acceptance Scenarios**:

1. **Given** the application is running, **When** any log entry is written, **Then** the entry is in structured format containing timestamp, level, message, and correlation ID — no free-text PII at any log level.
2. **Given** the application is running, **When** the health endpoint is called on the management port, **Then** it returns a healthy response within 200 ms.
3. **Given** the application is running, **When** the health or metrics endpoints are called on the public application port, **Then** they return a not-found response — not 200.
4. **Given** a scheduled background task fails with an uncaught exception, **When** the failure occurs, **Then** an alert notification is dispatched and a dead-letter record is created — the notification payload contains no personal information.

---

### User Story 8 - CI Pipeline Enforces Quality Gates (Priority: P2)

Every pull request is automatically checked for test results and frontend performance, blocking merge if any gate fails, so regressions cannot reach the main branch.

**Why this priority**: Manual code review cannot reliably catch performance regressions. Automated gates applied to every PR enforce quality consistently.

**Independent Test**: Open a PR with a change that causes a Lighthouse performance score below 85 on a candidate-facing route; the CI pipeline fails and blocks merge.

**Acceptance Scenarios**:

1. **Given** a pull request, **When** CI runs, **Then** the backend test suite and Angular unit tests are both executed; a failure in either blocks merge.
2. **Given** a pull request touching a candidate-facing route, **When** CI runs a Lighthouse mobile simulation, **Then** the pipeline fails if any candidate-facing route scores below 85 on Performance.
3. **Given** a CI run, **When** the test log output is scanned, **Then** the pipeline fails if any personal information pattern (email address, personal name) is found in the output.

---

### User Story 9 - Scheduled Job Infrastructure with Missed-Fire Recovery (Priority: P2)

Background scheduled tasks can recover automatically from a mid-task application crash without sending duplicate notifications or losing work.

**Why this priority**: Scheduled tasks that cannot recover from crashes will either silently lose work or send duplicate emails — both are unacceptable in a candidate-communication system.

**Independent Test**: Simulate a crash mid-task by killing the process during an active scheduled job, then restart the application. Inspect the output — no duplicate actions occurred, and the in-progress work is replayed exactly once.

**Acceptance Scenarios**:

1. **Given** a scheduled task is mid-execution, **When** the application process is killed, **Then** on restart the task detects the interrupted state and replays from the correct point without duplicating any completed actions.
2. **Given** a scheduled task completes successfully, **When** the same task trigger fires again in the same window, **Then** the second invocation detects the completed state and exits immediately without repeating work.
3. **Given** a notification produced by a scheduled task is attempted twice (due to a retry), **Then** only one notification is dispatched — the duplicate attempt is a no-op, detected by a unique idempotency key.

---

### Edge Cases

- What happens when the ephemeral database container for tests fails to start? The test suite fails immediately with a clear diagnostic message rather than timing out silently.
- What happens when a deployment is triggered while a previous deployment is still in progress? The second deployment either queues or is rejected cleanly; the first deployment completes without corruption.
- What happens when the application starts and a required environment secret is missing? The application fails to start immediately with a clear error identifying the missing variable — it does not start in a partially-configured state.
- What happens when index creation at startup encounters a conflicting existing index definition? The application fails to start, logs the specific conflict, and does not accept traffic.
- What happens when the graceful shutdown drain timeout is exceeded? The application force-exits after the timeout so it does not block the deployment pipeline indefinitely.
- What happens when the CI log scan finds a PII pattern in test output? The CI pipeline fails and the PR is blocked from merging.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST provide a single-command local backend start that connects to a locally running database container without cloud credentials.
- **FR-002**: The system MUST provide a single-command local frontend start that serves the Angular SPA and connects to the local backend with no TypeScript errors.
- **FR-003**: The backend test suite MUST execute with no cloud credentials by using an ephemeral database container that starts and stops automatically per test run.
- **FR-004**: The Angular unit test suite MUST execute in a headless browser without requiring a running backend.
- **FR-005**: A container image MUST be buildable from the repository; the running container MUST pass a health check within 60 seconds of start.
- **FR-006**: The application MUST complete in-flight requests and exit cleanly within 30 seconds of receiving a shutdown signal.
- **FR-007**: The backend MUST be deployable to the production host with a single command; the deployed version MUST be healthy within 5 minutes.
- **FR-008**: All runtime secrets MUST be stored in the hosting environment's secret store; zero secrets may appear in the repository or in deployment configuration files.
- **FR-009**: Merging to the main branch MUST automatically trigger a frontend build and deployment to the CDN host.
- **FR-010**: Each deployment environment MUST receive its own API base URL injected at build time — not a shared hardcoded value.
- **FR-011**: All required database indexes MUST be created before the application accepts traffic, on every startup.
- **FR-012**: The index creation step MUST be idempotent — repeated application starts on a correctly-indexed database MUST produce no errors and no duplicate indexes.
- **FR-013**: All application log entries MUST be structured (machine-parseable); no log entry at any level may contain an email address, personal name, or other candidate personal information.
- **FR-014**: Health and metrics endpoints MUST respond only on the management port; they MUST return a not-found response on the public application port.
- **FR-015**: When any scheduled background task fails with an uncaught exception, the system MUST dispatch an alert notification and create a dead-letter record; the alert payload MUST contain no personal information.
- **FR-016**: All scheduled background tasks MUST record start and completion state before and after performing work; on application startup, tasks found in an interrupted state older than a configurable threshold MUST be replayed.
- **FR-017**: Notifications produced by scheduled tasks MUST be guarded by a unique idempotency key; a duplicate attempt to send the same notification in the same window MUST be a no-op.
- **FR-018**: The CI pipeline MUST run the backend and frontend test suites on every pull request and block merge on any test failure.
- **FR-019**: The CI pipeline MUST run a Lighthouse mobile performance simulation on candidate-facing routes and block merge if any route scores below 85 on Performance.
- **FR-020**: The CI pipeline MUST scan test log output and block merge if any personal information pattern is found.

### Key Entities

- **Health Status**: A live report of whether the application is ready to serve traffic, checked by the deployment platform before routing requests.
- **Database Index**: A named performance structure on a collection; created at startup, idempotently, before traffic is accepted.
- **Scheduler Checkpoint**: A persistent record of a scheduled task's last-known state (running or completed); used to detect and replay interrupted jobs.
- **Idempotency Key**: A unique identifier composed of candidate identifier, event type, and scheduled time; prevents duplicate notification dispatch.
- **Dead-Letter Record**: An anonymised record created when a scheduled task fails, containing only internal identifiers — no personal data.
- **Container Image**: A packaged, self-contained build of the backend application used for consistent and repeatable deployment.
- **CI Pipeline**: An automated sequence of checks executed on every pull request; blocks merge on any failure.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A developer with no prior project setup can have the full local stack running within 15 minutes of cloning the repository, following only the README instructions.
- **SC-002**: The full backend test suite completes in under 5 minutes on a standard CI runner, with no cloud credentials required.
- **SC-003**: A production deployment from a passing build is live and healthy within 5 minutes of the deploy command being issued.
- **SC-004**: Frontend deployments triggered by a main-branch merge are live on the CDN within 5 minutes.
- **SC-005**: A simulated mid-task crash followed by application restart results in zero duplicate notifications dispatched — verified by automated test.
- **SC-006**: Zero personal information patterns appear in any test log output, as confirmed by the automated CI log scan on every PR.
- **SC-007**: All candidate-facing pages score 85 or higher on Lighthouse Performance in mobile simulation, as enforced by the CI gate on every PR.
- **SC-008**: The application starts against a fresh database and is accepting healthy traffic within 60 seconds, with all required indexes confirmed present by inspection.

## Assumptions

- Docker is available on developer machines and CI runners for running the ephemeral database container and building container images.
- The production backend host supports container image deployment and provides a secret store for environment variables.
- The CDN frontend host can be configured to trigger automated builds from repository branch events.
- Candidate-facing routes are distinguishable from internal routes at the routing level so the Lighthouse CI gate can target them specifically.
- The 30-second graceful shutdown drain is sufficient for expected in-flight request volume during a rolling deployment.
- The management port for health/metrics is not exposed to the public internet by the hosting platform's network configuration.
- All scripts created as part of this feature use LF line endings, enforced by repository-level line-ending configuration.
- The idempotency window for scheduled notification deduplication is scoped to a single scheduled-event occurrence (candidate ID + event type + scheduled timestamp), not a calendar day.
