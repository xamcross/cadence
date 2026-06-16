# Quickstart — Email Template Library (F21)

**Branch**: `010-email-template-library` | **Date**: 2026-06-15

## Local run

Prereqs: JDK 21 (`C:\jdk-24.0.1` is the local toolchain), cached Gradle 9.4.0, Docker Desktop (Testcontainers), Node/Angular CLI already installed (zero downloads — §X).

```powershell
# Backend (needs a local MongoDB for manual run)
docker run -d -p 27017:27017 mongo:7
./gradlew bootRun                 # ChangeUnit009 applies the emailTemplates unique index on startup

# Frontend
cd frontend; ng serve             # proxy.conf.json forwards /api to :8080
```

## Manual walk-through (browser -> Spring Boot -> MongoDB)

1. Sign in as an **Admin**; open **Email Templates** (ADMIN/RECRUITER route guard).
2. The list shows all 8 message types; an un-edited type shows its **built-in default** (`source: BUILTIN`).
3. Open **Invitation**, edit the body, **Save** → persisted as an override (`source: OVERRIDE`, `version` increments). Reload → the edit persists; the built-in default is untouched.
4. Click **Apply tone → Friendly** → subject/body replaced with the Friendly-invitation starter wording; still editable.
5. **Preview** with sample data → rendered subject/body; clear one token's sample value → it renders as `[[missing:<token>]]` with a visible warning (never a raw `{{token}}`).
6. Preview with a **selected candidate** → the candidate's name/email merge in (no-store; never logged).
7. Add a **per-stage variant** for an F12 interview stage; preview for that stage → variant wording; preview for another stage → base/built-in (fall-back).
8. As Admin, **Lock** the Rejection template. Sign in as a **Recruiter**: the Rejection editor is read-only; an edit attempt returns **403 `template_locked`**; preview still works. Admin can still edit/unlock.
9. Reset the Invitation override → it falls back to the built-in default.

## Acceptance -> test mapping

| Spec item | Test(s) |
|---|---|
| SC-001 built-in defaults present, non-empty | `EmailTemplateCrudIntegrationTest.listReturnsBuiltInDefaults` |
| US1 edit persists override, default untouched, version++ | `EmailTemplateCrudIntegrationTest.editPersistsOverride` |
| US1/FR-005 tone-apply replaces wording, editable, audited | `EmailTemplateCrudIntegrationTest.applyTone` + `EmailTemplateAuditTest` |
| SC-002 substitute-all, absent+empty→marker, byte-identical | `MergeRendererTest.substitutesAllOccurrences/missingAndEmptyMarker/deterministic` |
| SC-003 body HTML-escape (script) + subject CRLF strip | `MergeRendererTest.escapesHtmlBody/stripsSubjectControlChars` |
| SC-006/FR-016 URL token bare-anchor, no spoof; variant fall-back | `MergeRendererTest.urlTokenAnchorNoSpoof` + `EmailTemplateCrudIntegrationTest.variantResolutionAndFallback` |
| SC-004 invalid token / empty / over-cap → value-free 400, 0 persisted | `EmailTemplateValidationTest` (unknown, disallowed-for-type, malformed, empty subject/body, over-cap length/tokens/variants) |
| SC-005 lock blocks Recruiter edit/tone/variant/reset (403), not view/preview; Admin edits+unlocks; lock Admin-only; stale→409 | `EmailTemplateLockingTest` |
| SC-007 render-message shape + 5-role matrix + no-store + envelopes + inventory test | `EmailTemplateContractTest` |
| SC-008 each change-kind → one append-only audit row, ids/type/stage only | `EmailTemplateAuditTest` |
| SC-009 TRACE scan — content + candidate-email sentinels absent (incl. error paths) | `EmailTemplateLogPiiScanTest` + `ci.yml` PII scan |
| SC-010 render side-effect-free; no transport reachable; F22 owns no-auto-send | `EmailTemplateNoTransportTest` (structural: no EmailSender dep reachable) (+ F22 contract, out of F21) |
| SC-011 frontend: missing-field warning, locked disables edit, preview sample data | `email-templates.component.spec.ts` (Jasmine) |
| D7 foreign-candidate-preview / foreign-stage → 404 (oracle-free) | `EmailTemplateContractTest.foreignCandidate404/foreignStage404` |
| §II end-to-end edit → preview | `frontend/e2e/email-templates.spec.ts` (Playwright) |

## Test run flags (CLAUDE.md house pattern)

```powershell
$env:JAVA_HOME = "C:\jdk-24.0.1"
& "$env:USERPROFILE\.gradle\wrapper\dists\gradle-9.4.0-bin\*\gradle-9.4.0\bin\gradle.bat" test `
  -Dapi.version=1.41 -Dorg.gradle.java.installations.auto-download=false
$env:DOCKER_HOST = "npipe:////./pipe/docker_engine"
```
The first multi-class Testcontainers run after a recompile may throw the one-time `GenericContainer` class-init error — re-run. Frontend: `ng test --watch=false` + `ng build`.

## Notes

- **Zero new dependency / infra**: in-house `{{token}}` substitution + `HtmlUtils.htmlEscape` (spring-web); one new collection + one unique index (`ChangeUnit009`, order off applied `008`); no broker/cache/scheduler/SDK.
- **F21 sends nothing**: rendering produces a `RenderedMessage`; dispatch (consent/erasure gate, hard-bounce, scheduled reminders, the no-auto-send enforcement) is F22; SLA drafting is F31.
- **Deploy** (after merge): `scripts\db-migrate.ps1` then `scripts\deploy-backend.ps1` (Mongock applies `ChangeUnit009` on startup) + `scripts\deploy-frontend.ps1`.
