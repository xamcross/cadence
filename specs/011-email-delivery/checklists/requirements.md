# Specification Quality Checklist: Email Delivery Channel (F22)

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-06-16
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Success criteria are technology-agnostic (no implementation details)
- [x] All acceptance scenarios are defined
- [x] Edge cases are identified
- [x] Scope is clearly bounded
- [x] Dependencies and assumptions identified

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria
- [x] User scenarios cover primary flows
- [x] Feature meets measurable outcomes defined in Success Criteria
- [x] No implementation details leak into specification

## Notes

- Transport/provider choice (SMTP vs provider HTTP API) is deliberately deferred to `plan.md` per the constitution's dependency policy; the spec stays provider-agnostic. This is a documented assumption, not an unresolved clarification.
- Scope boundary is explicit: F22 delivers the channel + the reusable scheduled-dispatch pattern; concrete reminder business rules remain owned by F13/F23/F31/F32.
- Items marked complete; spec is ready for `/speckit.plan`.

## Multi-role spec review (2026-06-16) — verdict: APPROVE-WITH-NITS (Security/GDPR, QA, Backend/DevOps)

Spec-level findings were folded in (FR-006 fail-closed, FR-009 immediate-send key, FR-010 honest-bound, FR-011 cap, FR-012 async/single-mechanism, FR-013 candidate-ID-only, FR-014 audit refusals/bounces, FR-016 workspace-bind + secret-at-rest, FR-017 erasure+clear, SC-005 transport-scoped, SC-003/SC-001 measured at sink, SC-009 idempotent callbacks, SC-010 soft-bounce, multi-instance safety net, EmailSender widening note, backlog supersession note).

**Carried to `plan.md` (decisions to lock before tasks):**
1. **Transport fork** — choose SMTP (`spring-boot-starter-mail`, C4-clean, no async webhook) vs a webhook-capable provider HTTP API (via `RestClient`). This determines whether SC-005's hard-bounce path is async-webhook or synchronous-rejection-only. If any provider SDK is added, file the backlog-required one-line dependency justification.
2. **`EmailSender` interface widening** — add workspace/eventType/scheduledAt (or a dispatch-request value object); enforce the consent gate in the dispatch service, not the legacy 3-arg method.
3. **Outbox claim ordering + in-flight state** — claim/CAS the outbox row to `SENDING` before send, `SENT` after; define replay/reconciliation policy for a stuck `SENDING`/`pending` row (the crash-window guarantee, FR-010/US2-AC2).
4. **New collection + indexes** — outbox collection with unique `{workspaceId, idempotencyKey}` and a `providerMessageRef` lookup index for webhook correlation; declare per F00.1.
5. **New candidate field** — `undeliverableFlag` + bounce metadata on the F04 `candidates` document; confirm erasure purges it and a clear-path owner (F22 vs F51).
6. **Inbound webhook security chain** — if webhook chosen, an `@Order` permitAll chain entry authenticated by provider signature (the F01.1 calendar-callback precedent).
7. **§II real trigger** — name the concrete non-stub trigger F22 wires for the end-to-end demo (the calendar-confirmation send is the lowest-coupling choice; F13 cannot be leaned on — its close depends on F22).
