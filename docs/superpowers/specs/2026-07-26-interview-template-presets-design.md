# Interview Template Presets & Starter Emails — Design

**Date**: 2026-07-26
**Status**: Approved in brainstorming; awaiting implementation plan
**Related**: F12 interview templates (`specs/009-interview-rule-engine/`), F21 email template library (`specs/010-email-template-library/`)

## Problem

Email templates already ship with built-in defaults (8 message types, tone presets) so that surface is never empty. Interview templates have nothing: a new workspace starts with an empty screen and recruiters must invent duration, cadence, buffers, caps, and panel structure from scratch. There are no ready-made examples mapping the product to common hiring use cases.

## Goal

Ship a persistent "Start from a preset" gallery on the interview-templates screen: six code-shipped presets covering standard interview types, each pre-filling the template editor, plus matching per-stage starter email variants the recruiter can opt into after saving. Nothing is auto-created; presets are reachable in new and mature workspaces alike.

## Non-goals

- No demo/sample workspace data (candidates, requisitions, bookings).
- No public marketing use-case pages.
- No re-application of starter emails after the post-save dialog is dismissed (tone presets remain the ongoing wording tool).
- No per-workspace preset editing — presets are code-shipped starting points, not stored documents.

## Approach (decided)

Code-shipped preset catalogue, mirroring the email library's built-in-default (D1) and tone-preset (D10) patterns. Rejected alternatives: frontend-only presets (splits email wording authority between FE and BE, diverges from established i18n/content patterns); Mongock-seeded documents (append-only changesets turn every wording tweak into a migration; static content does not belong in the DB).

## 1. Preset catalogue (content)

Six presets. Each pre-fills the numeric/structural fields of `InterviewTemplate` and carries a *panel hint* (required-seat count / pool shape). Member ids are always chosen by the recruiter — presets cannot know the workspace's people. Blackouts and time-zone/working-hours overrides stay empty (inherit workspace defaults).

| Preset key | Duration | Cadence | Buffers (pre/post) | Daily cap | Panel hint | Starter emails |
|---|---|---|---|---|---|---|
| `PHONE_SCREEN` | 30 min | 15 | 0 / 5 | 4 | 1 required | INVITATION, CONFIRMATION |
| `HM_INTRO` | 45 min | 15 | 5 / 5 | 3 | 1 required | INVITATION |
| `TECH_DEEP_DIVE` | 60 min | 30 | 10 / 10 | 2 | 1 required + optional shadow | INVITATION (prep/screen-share expectations), CONFIRMATION, REMINDER_24H |
| `PANEL_LOOP` | 90 min | 30 | 15 / 15 | 1 | 1 required + pool "any 2 of N" | INVITATION (agenda/what-to-expect), CONFIRMATION, REMINDER_24H |
| `HR_CULTURE` | 45 min | 15 | 5 / 5 | 3 | 1 required | INVITATION |
| `FINAL_ROUND` | 60 min | 30 | 10 / 10 | 2 | 1 required + pool "any 1 of N" | INVITATION, CONFIRMATION |

Starter emails are **per-stage variants** of the existing F21 message types. Only types whose wording genuinely differs by interview kind get a starter (mostly INVITATION); REMINDER_1H, REJECTION, SLA_HOLDING, HOLD_UPDATE, and FEEDBACK_REQUEST always fall back to the base library.

**Content ownership**: email starter wording lives in backend classpath resources (`resources/email-templates/preset/…`), same as built-ins and tone presets. Gallery names/descriptions are frontend `$localize` strings keyed by the stable preset key, so all UI wording follows the app's i18n rule while server-rendered email wording stays server-side.

## 2. Architecture & data flow

### Backend (no new collections, no migration)

1. **`InterviewTemplatePresetCatalogue`** — code-shipped constants class (type-safe, no parsing) holding each preset's structural values, panel hints, and declared starter-email types. **`PresetEmailStarterCatalogue`** loads `{subject, body}` per `(presetKey, messageType)` from classpath resources, mirroring `BuiltInEmailTemplates` / `TonePresetCatalogue`.
2. **Two endpoints**, both `@PreAuthorize` ADMIN/RECRUITER (picked up by the endpoint-inventory test):
   - `GET /api/internal/interview-templates/presets` — read-only catalogue: keys + structural values + starter-email types. No workspace state involved.
   - `POST /api/internal/email-templates/{messageType}/apply-preset-starter` with `{stageKey, presetKey}` — materialises a per-stage variant override from the starter catalogue via the **existing** variant machinery (same insert/version/audit semantics as `apply-tone`). `stageKey` must be a workspace-owned interview-template id, else the oracle-free 404.

**Template creation is unchanged**: applying a preset happens client-side — the gallery pre-fills the existing editor form, the recruiter picks members, and saving goes through the existing create endpoint with full validation. No create-from-preset backdoor, so a preset can never produce a template the normal path would reject.

### Frontend

- Interview-templates screen: "New template" offers **Blank** or **Start from a preset**; the empty state showcases the gallery (cards: localized name, one-line description, key facts). Gallery data = `GET presets` merged with `$localize` labels by preset key.
- Choosing a preset opens the normal editor pre-filled, with a dismissible banner (e.g. "Preset: Panel loop — pick your panel members"). Panel hints render as pre-created empty seats/pool rows (e.g. a pool row with `n=2` and an empty member picker).
- After a successful save from a preset, a follow-up dialog lists that preset's starter-email types as checkboxes (default checked). Confirming calls `apply-preset-starter` once per checked type, then links to the email-templates screen for review. Skipping is allowed and final for that template (see non-goals).

## 3. Error handling

- Gallery fetch failure is non-blocking: blank creation still works; the gallery area shows a quiet retry.
- In the starter-email dialog each `apply-preset-starter` call is independent: failed types show an inline retry chip, succeeded types are marked done; closing the dialog never blocks the already-created template.
- Locked base template → existing lock semantics (same 403 as `apply-tone`). Concurrent edit → existing 409 version conflict. Re-applying a starter overwrites subject/body with `version++`, exactly like tone apply.

## 4. Security & compliance

- Both endpoints deny-by-default with `@PreAuthorize('ADMIN','RECRUITER')`.
- `apply-preset-starter` resolves `stageKey` workspace-scoped with the oracle-free 404 (no cross-workspace existence probe).
- Logs carry preset keys and ObjectIds only — never template names or email content (PII sentinel scan covers the new paths).
- No new runtime dependency, no new collection, no Mongock changeset.

## 5. Testing

**Backend** (JUnit 5, Testcontainers Mongo singleton, zero downloads):
- Catalogue-validity test: every preset's values pass template service validation (with a dummy member), and every declared starter type has non-empty resource content — a preset can never propose an invalid template.
- Endpoint tests: RBAC across all five roles; presets read shape; `apply-preset-starter` materialise / overwrite / locked-403 / foreign-`stageKey`-404.
- PII log-scan sentinel with a template-name canary through the new paths.

**Frontend** (Jasmine/Karma EdgeHeadless):
- Gallery renders from a stubbed presets response; preset selection pre-fills form fields and panel rows correctly.
- Post-save dialog issues one call per checked type and handles per-type failure.
- `$localize` on all new user-facing strings; axe-core on the gallery and dialog.
