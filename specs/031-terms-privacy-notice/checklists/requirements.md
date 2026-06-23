# Specification Quality Checklist: Terms & Conditions and Privacy Notice

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-06-23
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

- Resolved without [NEEDS CLARIFICATION] markers by applying informed defaults grounded in the existing codebase (deny-by-default SEO posture, candidate token-page hardening, the F61 static-content/SEO infrastructure, and the existing admin-side GDPR/lawful-basis model). The three potentially-ambiguous decisions — delivery (dedicated pages vs popups), acceptance-capture vs display-only, and content authorship (placeholder vs final) — are documented in **Assumptions** and **Out of Scope**.
- The feature deliberately introduces **no backend/database/dependency change**; "implementation detail" references to the design system and SEO infrastructure are kept in Assumptions/Dependencies (as integration context), not in requirements.

### Subagent review (3 reviewers, all findings reconciled)

- **GDPR/privacy reviewer** — 2 BLOCKERs folded in: FR-003 expanded to the full Article 13 **and** 14 mandatory element set (controller/DPO identity, international transfers, complaint right, withdraw-consent, automated-decision-making, indirect-source disclosure, statutory/contractual notice); new **FR-020** + **SC-010** require the Privacy Notice link in candidate-facing outbound emails so indirectly-collected candidates (CSV/ATS/sourced) are reached (Article 14). SHOULD-FIX folded in: recruiter-acceptance / consent-basis limitation noted in Assumptions; **FR-018** strengthened to a prominent draft notice; no-referrer named as primary control (FR-010); per-controller identity limitation noted.
- **Spec-quality reviewer** — no blockers. SHOULD-FIX folded in: FR-008/SC-002 closure rule ("planning-fixed surface inventory"); FR-005 version-id semantics; bounded supported-locale set + **SC-011**; Out-of-Scope lines for version-history and translated body text; de-leaked SC-001/SC-005; edge case for unknown legal URL.
- **Frontend/a11y/SEO reviewer** — 2 BLOCKERs folded in: legal pages MUST be published via the static-content build path, **not** indexable Angular routes (the route-inventory test locks exactly one indexable SPA route) — new **FR-021** + **FR-016** updated; address-pattern + coordinated-CI-edit constraint captured in Dependencies; sitemap via generator (not static file). SHOULD-FIX folded in: no-footer-exists noted (FR-007/US3); token pages get a single inline Privacy link; FR-010/FR-011 reworded around the existing global no-referrer + CI-locked CSP; system-font assumption added.

Re-validated after edits: all checklist items pass.

### Clarify session 2026-06-23

- **Decision recorded**: legal pages use the **conventional top-level URLs `/terms` and `/privacy`** (the `/resources/legal/*` content path was rejected). FR-001/FR-002 resolved to the fixed addresses; FR-021 reworded; new **FR-022** + **SC-012** + **SC-013** capture the coordinated SEO/AEO artifact updates and the static-emit/SPA-fallback correctness requirement; Dependencies marked "decided".
- **Reviewed with 2 subagents**: a consistency reviewer (verdict **CONSISTENT** — clean application, contiguous numbering, no stale "undecided"/fallback text) and a code-grounded SEO/AEO artifact reviewer. The SEO/AEO reviewer's three substantive findings were folded in: (B3) emit real static `/terms` + `/privacy` index documents so the host serves them ahead of the SPA catch-all (else `/terms` silently renders the noindex NotFound) + trailing-slash canonical → FR-021 + SC-013; (B2/S2/S3) the site-map/`llms.txt` generator and the structured-data emitter have no non-article slot and need new emit logic + a new CI assertion → FR-022(b)(c)(e)(f); (B1/N2) the robots allow-set CI guard is closed and the allow form must be `$`-anchored → FR-022(a)(e). Plan-level file:line specifics deferred to `/speckit.plan`.
