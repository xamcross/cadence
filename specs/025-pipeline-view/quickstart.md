# Quickstart: F51 Pipeline View

End-to-end demonstration (browser → Spring Boot → MongoDB) proving the §II "no stubs" bar. Assumes the existing local dev setup (Docker `mongo:7`, backend `bootRun`, frontend `ng serve` with the proxy), seeded workspace with members in each role, and some candidates from prior features (scheduling, status, SLA, feedback, ATS/CSV).

## Prerequisites

- Backend running with a configured workspace (F03) and candidates (F04/F13/F42).
- One member of each role: Admin, Recruiter, Read-only, Hiring Manager, Interviewer.
- At least a few candidates with varied state: some with a sent scheduling link, some booked, some past their SLA window, some erased.

## Scenario A — Recruiter sees the whole pipeline (US1, P1)

1. Sign in as **Recruiter**. Navigate to **Pipeline**.
2. The list shows every active candidate with name, stage, an SLA colour chip (green/amber/red), and a scheduling-status chip.
3. Filter `SLA = red` → only breaching candidates remain. Sort by `Scheduling` → rows reorder by scheduling progress with a stable tie-break.
4. Confirm an **erased** candidate does **not** appear (SC-009).
5. Leave the page open ~60 s after breaching a candidate's SLA window (advance the test clock in an integration run) → the row's chip flips to red on the next poll (FR-006/SC-003).

✅ Pass: all active candidates listed with correct SLA + scheduling; filters/sort work; erased excluded; live refresh.

## Scenario B — Hiring Manager scoping (US2, P2) — the load-bearing privacy gate

1. As **Admin**, go to **Requisitions** → create `R1` and `R2`. Assign the Hiring Manager to **R1 only**.
2. As **Recruiter**, link candidate C1 to R1 and C2 to R2 (Pipeline row action or Requisitions surface).
3. Sign in as the **Hiring Manager** → Pipeline shows **only C1** (R1). C2 and unassigned candidates are absent.
4. Hit the API directly as the HM for R2's scope (`?requisitionId=<R2>`): the response discloses nothing (empty rows / no-oracle 404), never C2.
5. As Recruiter, move C1 from R1 to R2. As HM (R1) reload → C1 is gone. (SC-004, US2-6/7.)

✅ Pass: HM sees only assigned-requisition candidates; out-of-scope refused with no oracle; link-move flips visibility; empty assignment → empty pipeline.

## Scenario C — Bulk actions (US3, P2)

1. As **Recruiter**, select 8 candidates (include 2 you know are non-contactable — e.g. one withdrawn, one erased).
2. Bulk → **Send update email** (HOLD_UPDATE).
3. The result lists all 8: 6 `ENQUEUED`, the 2 non-contactable `SKIPPED / not_contactable` — **the same coarse reason for both** (you cannot tell withdrawn from erased). No email is sent to the 2 (verify the outbox).
4. Re-click the bulk action immediately (or fire two concurrent requests in an integration test) → no candidate receives a duplicate send (FR-019).
5. Select more than the configured max → rejected with `selection_too_large` before anything is sent.
6. As **Read-only** / **Hiring Manager** / **Interviewer** → the bulk action is unavailable / 403.

✅ Pass: per-candidate outcomes; coarse non-disclosing skip; no sends to non-contactable; idempotent; max enforced; role-gated.

## Scenario D — Candidate timeline (US4, P3)

1. From any Pipeline row (as Recruiter), open the candidate → **Timeline**.
2. Events appear in chronological order with readable labels (email sent, booked, status updated, feedback submitted, requisition linked).
3. A candidate with feedback requested-but-not-submitted shows a **pending** feedback indicator.
4. A brand-new candidate shows an **empty** timeline (not an error).
5. As the **Hiring Manager**, open the timeline of a candidate **not** on their requisition → no-oracle 404 (no disclosure).

✅ Pass: chronological PII-free timeline; feedback-pending; empty state; HM scoping with no oracle.

## Automated acceptance coverage (maps to spec)

| Spec | Test |
|---|---|
| US1 / FR-001..007, SC-001/002/003 | `PipelineListContractTest`, `PipelineComposeIT`, `PipelinePerfIT` |
| US2 / FR-008..014, SC-004 | `PipelineHmScopingIT`, `RequisitionContractIT` |
| US3 / FR-015..020, SC-005/006 | `PipelineBulkIT`, `PipelineBulkConcurrencyIT` |
| US4 / FR-021..023, SC-007 | `PipelineTimelineIT` |
| FR-005 mapping | `PipelineSchedulingStatusTest` (pure unit) |
| FR-024 / SC-008 | `PipelineLogPiiScanTest` + CI sentinel scan |
| SC-009 (erasure/regression) | `PipelineErasureRegressionIT` |
| Indexes (D8) | `PipelineIndexTest` |
| Frontend US1/US3/US4 | `pipeline-list.component.spec.ts`, `candidate-timeline.component.spec.ts`, `requisitions.component.spec.ts` |

## Constitution Definition-of-Done checklist (this feature)

- [ ] E2E: Angular Pipeline → Spring Boot → MongoDB works for all four scenarios above.
- [ ] Unit + integration + contract tests green (incl. the 5-role matrix, gated bulk concurrency, no-oracle 404s).
- [ ] Internal screens — Lighthouse/WCAG gates N/A (FR-025; documented, the F50 precedent).
- [ ] No plaintext PII in logs (CI sentinel scan extended with F51 name/stage sentinels).
- [ ] New Java sources scanned for NUL/non-ASCII (the F30 lesson); no new `.ps1`.
- [ ] Multi-role sub-agent review (≥ 3 roles) completed at task close; findings applied or reported.
- [ ] PR reviewed; no direct push to `main`.
