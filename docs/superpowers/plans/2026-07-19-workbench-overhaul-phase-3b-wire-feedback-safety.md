# Workbench Overhaul — Phase 3b: Wire Feedback & Safety — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Gate every destructive action behind the confirm dialog and surface every action outcome as a toast, across the internal screens — using the Phase 3a `ConfirmDialogService` and `ToastService`.

**Architecture:** Per-screen wiring of two patterns (below). 17 confirm-gates (3 of which replace an existing hand-rolled inline 2-step confirm) and ~24 toast sites across 13 screens. Frontend-only; no service/API/business-logic changes beyond making handlers `async` to await the confirm and calling the toast service.

**Tech Stack:** Angular 17.3 standalone, Jasmine + TestBed (EdgeHeadless). Consumes `frontend/src/app/shared/ui/{confirm-dialog.service,toast.service}.ts` (Phase 3a).

## Global Constraints

- **Kit API (verbatim):** `ConfirmDialogService.confirm({ title, body?, confirmLabel?, cancelLabel?, danger? }): Promise<boolean>` (await; proceed only if `true`). `ToastService.success(msg)`, `.error(msg)`, `.info(msg)`.
- **Inject depth:** `features/<x>/` → `../../shared/ui/…`; `features/admin/<x>/` and `features/admin/gdpr/` → `../../../shared/ui/…`.
- **Presentational/UX only.** No change to the underlying service calls or their payloads. Handlers may become `async` and gain `if (!ok) return;` + toast calls; nothing else in their logic changes.
- **i18n:** every confirm title/body/label and every toast message is user-facing — wrap in `$localize` with a new `@@` id (e.g. `@@confirm.req.close.title`, `@@toast.req.closed`). Interpolated values use `$localize` template placeholders. Use `&amp;`/"and" as needed.
- **Replace the 3 existing inline 2-step confirms** (remove their `confirming`/`confirmingId`/`confirmingDisconnect` signal + the inline second-button markup) with `confirm()`.
- **Testing:** run from `frontend/` (`ng test --watch=false --include='**/<spec>'`). Every touched screen's spec gains: (a) confirm-gate tests — spy `ConfirmDialogService.confirm` to resolve `false` (assert the underlying action did NOT run) and `true` (assert it ran); (b) toast tests — spy `ToastService.success`/`error` and assert it fired on the outcome. Keep existing axe assertions. Screens keep their existing specs (all 13 have one after Phase 2).
- **Git:** stage only each screen's files (`git add <paths>`, never `-A`; leave `CLAUDE.md`/`environment.prod.ts`). Commit per screen. Trailer convention. No push.

---

## Pattern C — Confirm-gate

Inject once per component:
```ts
private readonly confirm = inject(ConfirmDialogService);
private readonly toast = inject(ToastService);
```
Convert each destructive handler to await the gate:
```ts
async close(r: Requisition): Promise<void> {
  const ok = await this.confirm.confirm({
    title: $localize`:@@confirm.req.close.title:Close this requisition?`,
    body: $localize`:@@confirm.req.close.body:"${r.title}:title:" will be closed. You can reopen it later.`,
    confirmLabel: $localize`:@@confirm.req.close.cta:Close requisition`
  });
  if (!ok) return;
  // …existing action body, unchanged…
}
```
Add `danger: true` for irreversible/destructive actions (per the table).

**Select-triggered gate (members `onRoleChange`):** capture the previous value first; if declined, revert the model so the `<select>` snaps back, then return:
```ts
async onRoleChange(member: MemberRow, newRole: Role): Promise<void> {
  const previous = member.role;
  const ok = await this.confirm.confirm({ /* danger: true, copy from table */ });
  if (!ok) { member.role = previous; return; }   // revert the optimistic select
  // …existing optimistic update + server call…
}
```
(Read the actual field/signal the select binds to and revert exactly that.)

**Replacing an inline 2-step confirm:** delete the `confirming*`-signal declaration, the template's second-button/prompt block, and the toggle method; call `confirm()` directly from the primary button's handler. Update the spec that referenced the old signal.

## Pattern T — Toast on outcome

At each action's success and error branch:
```ts
… .subscribe({
  next: () => { /* existing */ this.toast.success($localize`:@@toast.req.closed:Requisition closed.`); },
  error: (e) => { /* existing */ this.toast.error($localize`:@@toast.req.closeFailed:Couldn't close requisition.`); }
});
```
- **Default:** ADD toast calls at outcomes.
- Where a screen has a **shared/ambiguous/mis-styled inline message signal** (`workspace-settings` `message`/`error` shared by 8 actions; `scheduling` `manageMsg`/`statusMsg`/`slaMsg` that render errors in success-styled `.alert--ok` boxes), REPLACE the inline rendering with per-action toasts and remove the now-dead signal + markup (fixing the mis-styling). Update the spec.
- Where inline feedback is already clean/per-action (`email-templates` `sendStatus`/`sendError`; `calendar` `banner`), prefer replacing with a toast for consistency and remove the dead signal.
- Keep contextual/validation inline alerts (form field errors) as-is.

## Test pattern (per screen)
```ts
it('does not <act> when the confirm is declined', async () => {
  const confirmSvc = TestBed.inject(ConfirmDialogService);
  spyOn(confirmSvc, 'confirm').and.resolveTo(false);
  const svcSpy = spyOn(theUnderlyingService, 'theMethod').and.callThrough();
  await component.close(row);            // the handler is async
  expect(svcSpy).not.toHaveBeenCalled();
});
it('<acts> and toasts on confirm', async () => {
  spyOn(TestBed.inject(ConfirmDialogService), 'confirm').and.resolveTo(true);
  const toastSpy = spyOn(TestBed.inject(ToastService), 'success');
  await component.close(row); /* flush the HTTP mock success */
  expect(toastSpy).toHaveBeenCalled();
});
```

---

## Per-screen work (batched)

Confirm copy is the catalog's; keep messages short. `danger:true` marked ⚠.

### Batch 1 — scheduling (`features/scheduling/scheduling.component.ts`)
Confirm-gates: `cancel()` ⚠ ("Cancel this interview?" / "The candidate will be notified that their interview is cancelled." / "Cancel interview"); `release()` ⚠ ("Release this slot?" / "The booked slot will be released, calendar events removed, and the candidate notified." / "Release slot"); `rotateStatusLink()` ("Rotate the status link?" / "The current status link will stop working immediately." / "Rotate link"); `dismissDraft(id)` ("Dismiss this draft?" / "The queued holding message will not be sent." / "Dismiss").
Toasts: replace `manageMsg`, `statusMsg`, `slaMsg`, `result`/`error` with per-action `toast.success`/`toast.error` (this fixes the error-in-`.alert--ok` mis-styling). Keep the `result` booking details if they're structured data shown in a panel; toast only the outcome.
- [ ] **Task 1 — scheduling.** Apply Pattern C to the 4 handlers, Pattern T to send/reschedule/cancel/release/status/sla outcomes. Extend spec.

### Batch 2 — workspace-settings (`features/admin/workspace/workspace-settings.component.ts`)
Confirm-gates: `removeLogo()` ("Remove the logo?" / "You can upload a new one anytime." / "Remove logo"); `removeCredential()` ⚠ ("Remove the email credential?" / "Cadence will stop sending candidate emails until a new credential is added." / "Remove credential"); `toggleLock(key)` **only when locking** ("Lock this template?" / "Recruiters will no longer be able to edit it." / "Lock").
Toasts: replace the `message`/`error` (`ok()`/`fail()` helpers shared by 8 actions) with per-action toasts ("Operational settings saved.", "Branding saved.", "Email settings saved.", "Logo removed.", "Credential removed.", "Template locked/unlocked.", etc. + matching error messages). Remove the now-dead `message`/`error` signals + their `.alert` markup; keep field-level validation display.
- [ ] **Task 2 — workspace-settings.** Extend spec.

### Batch 3 — GDPR (`features/admin/gdpr/`)
- [ ] **Task 3 — candidate-erasure-action.** Replace the `confirming` inline 2-step with `confirm()` on `erase()` ⚠ ("Erase candidate data?" / "This permanently erases {{candidateId}}'s personal data. This cannot be undone." / "Erase data"). Light gate on `withdrawBasis()` ("Withdraw lawful basis?" / … / "Withdraw"). Replace the shared `message` signal with `toast.success`/`error` per action. Extend spec.
- [ ] **Task 4 — erasure-queue.** Gate `confirm(id)` ⚠ ("Confirm erasure?" / "This permanently erases candidate {{id}}'s data. This cannot be undone." / "Erase permanently") and `reject(id)` ("Reject this erasure request?" / … / "Reject request"). Replace shared `message` with toasts. Extend spec. *(Note: the component method is named `confirm` — rename the injected service to avoid a clash, e.g. `inject(ConfirmDialogService)` as `dialog`.)*
- [ ] **Task 5 — retention-review.** Replace the `confirmingId` inline 2-step with `confirm()` on `del(id)` ⚠ ("Delete this record?" / "Candidate {{id}}'s record will be permanently deleted. This cannot be undone." / "Delete permanently"). Replace shared `message` with toasts. Extend spec.

### Batch 4 — templates
- [ ] **Task 6 — email-templates.** Gates: `reset(t)` ⚠ ("Reset to default?" / "Your customized subject and body will be discarded." / "Reset to default"); `setLock(t,true)` ("Lock this template?" / … / "Lock"); `send(t)` ⚠-ish ("Send this email?" / "This sends the previewed message to candidate {{id}} now." / "Send email"). Toasts on save/tone/reset/lock/send; reuse the existing `sendStatus`/`sendError` as the model but route through toasts (remove the dead signals). Extend spec.
- [ ] **Task 7 — interview-templates.** Gate `retire(t)` ("Retire this template?" / "\"{{name}}\" will no longer be available for scheduling new interviews." / "Retire template"). Toasts on submit(create/edit)/retire; replace the shared `error` signal usage for outcomes with `toast.error`. Extend spec.

### Batch 5 — members / requisitions / ats
- [ ] **Task 8 — members.** Gate `onRoleChange` ⚠ via the select-revert pattern ("Change role?" / "Change this member's role? They will immediately gain or lose access." / "Change role"). Toast success on change, `toast.error` on failure (surface the "last admin" 409 message). Extend spec (new file added in Phase 2).
- [ ] **Task 9 — requisitions.** Gate `close(r)` ("Close this requisition?" / … / "Close requisition"); leave `reopen` ungated. Per-action toasts for create/close/reopen/assign/link (success + error) replacing the single generic boolean-`error` alert for outcomes (keep the boolean error only for list-load failure if it also covers that). Extend spec.
- [ ] **Task 10 — ats-integration.** Gate `disconnect(provider)` ⚠ ("Disconnect {{provider}}?" / "Cadence will stop syncing with {{provider}}. You'll need to re-enter the API key to reconnect." / "Disconnect"). Toasts on connect success/error and disconnect success/error (disconnect has no feedback today). Extend spec.

### Batch 6 — csv-import / interest-requests / calendar / pipeline
- [ ] **Task 11 — csv-import.** Light gate on `resolveAll('SKIP')` ("Skip all duplicates?" / "{{n}} candidates will not be imported." / "Skip all"). Toast on upload success/error and resolve outcomes. Extend spec.
- [ ] **Task 12 — interest-requests.** Gates: `erase(r)` ⚠ ("Erase this request?" / "{{name}}'s access request and personal data will be permanently erased." / "Erase"); `dismiss(r)` ("Dismiss this request?" / … / "Dismiss"). Toasts on review/dismiss/invite/erase (replace the per-row `noteFor` map for outcomes, or keep the map and add a toast — prefer toast). Extend spec.
- [ ] **Task 13 — calendar-connections.** Replace the `confirmingDisconnect` inline 2-step with `confirm()` on `disconnect(p)` ("Disconnect {{label}}?" / "Cadence will stop reading your availability from {{label}} until you reconnect." / "Disconnect"). Replace the typed `banner` signal with toasts (`success`/`error`) and remove it. Extend spec.
- [ ] **Task 14 — pipeline-list.** Optional summary toast after a bulk `sendUpdateEmail()`/scheduling-link bulk action: `toast.success` / `toast.error` summarizing "{{n}} sent, {{m}} failed" (keep the existing per-candidate `bulkResults` detail list). No confirm-gate required (bulk send is the button's purpose). Extend spec.

---

## Verification (after all tasks)
- [ ] `ng test --watch=false` — full suite green.
- [ ] `git status --short` — only touched screen files committed; the 2 SEO files still unstaged.
- [ ] Manual spot: a destructive action opens the dialog (ESC/backdrop cancels, no action runs); a success shows a toast; the GDPR/retention/calendar screens no longer show their old inline 2-step prompts.

## Self-Review (completed at authoring time)
- **Coverage vs catalog:** all 17 confirm-gates and the 24 toast sites map to Tasks 1–14; the 3 inline 2-step confirms (candidate-erasure-action, retention-review, calendar-connections) are explicitly replaced. Non-destructive actions (reopen, invite, reschedule, review, filters) are intentionally left ungated.
- **API match:** confirm/toast signatures copied verbatim from the committed Phase 3a services (`confirm(): Promise<boolean>`, `success/error/info`). The `erasure-queue` method-name clash with `confirm` is flagged (inject as `dialog`).
- **Scope guard:** no change to underlying service calls; handlers only gain `async`/`await`/guard/toast. Contextual validation alerts retained; only transient action-outcome alerts convert to toasts. Import depths and the async test pattern are specified once and reused.
