import { Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { GdprService } from './gdpr.service';
import { PageHeaderComponent } from '../../../shared/ui/page-header.component';
import { ConfirmDialogService } from '../../../shared/ui/confirm-dialog.service';
import { ToastService } from '../../../shared/ui/toast.service';

/**
 * Admin/Recruiter erasure trigger + lawful-basis record/withdraw, keyed by a pasted candidate internal
 * id (no candidate browser — F51). Erasure is destructive and irreversible, so it requires an explicit
 * confirmation step. The server enforces the role; this surface is UX + defense-in-depth.
 *
 * Phase 3b (workbench overhaul): `erase` is gated behind the shared `ConfirmDialogService` (⚠ danger),
 * replacing the old hand-rolled `confirming`-signal inline two-step prompt. `withdrawBasis` gets a
 * light (non-danger) confirm gate. Outcomes are surfaced via `ToastService` instead of a shared
 * `message` signal.
 */
@Component({
  selector: 'app-candidate-erasure-action',
  standalone: true,
  imports: [FormsModule, PageHeaderComponent],
  template: `
    <section class="gdpr">
      <app-page-header
        eyebrow="Data &amp; privacy" i18n-eyebrow="@@gdpr.action.eyebrow"
        heading="Candidate data actions" i18n-heading="@@gdpr.action.title"
        subtitle="Lawful basis, withdrawal, and erasure." i18n-subtitle="@@gdpr.action.subtitle">
      </app-page-header>
      <div class="field">
        <label for="erase-cid" class="field__label" i18n="@@gdpr.action.candidateId">Candidate ID</label>
        <input id="erase-cid" name="cid" class="input" [(ngModel)]="candidateId" />
      </div>

      <fieldset>
        <legend i18n="@@gdpr.basis.legend">Lawful basis (email)</legend>
        <select name="basis" class="input" [(ngModel)]="basis" aria-label="Lawful basis" i18n-aria-label="@@gdpr.basis.aria">
          <option value="LEGITIMATE_INTEREST" i18n="@@gdpr.basis.li">Legitimate interest</option>
          <option value="CONSENT" i18n="@@gdpr.basis.consent">Consent</option>
          <option value="CONTRACT" i18n="@@gdpr.basis.contract">Contract</option>
        </select>
        <button type="button" class="btn btn--primary" (click)="recordBasis()" i18n="@@gdpr.basis.record">Record basis</button>
        <button type="button" class="btn btn--ghost" (click)="withdrawBasis()" i18n="@@gdpr.basis.withdraw">Withdraw basis</button>
      </fieldset>

      <fieldset>
        <legend i18n="@@gdpr.erase.legend">Erase personal data</legend>
        <button type="button" class="danger btn btn--danger-soft" (click)="erase()" i18n="@@gdpr.erase.start">
          Erase candidate data
        </button>
      </fieldset>
    </section>
  `,
  styles: [`
    .gdpr { padding: var(--space-4); max-width: 32rem; }
    fieldset { margin-bottom: var(--space-4); }
    button { margin-right: var(--space-2); }
  `]
})
export class CandidateErasureActionComponent {
  private readonly gdpr = inject(GdprService);
  private readonly confirm = inject(ConfirmDialogService);
  private readonly toast = inject(ToastService);

  candidateId = '';
  basis = 'LEGITIMATE_INTEREST';

  recordBasis(): void {
    this.gdpr.recordBasis(this.candidateId, this.basis).subscribe({
      next: () => this.toast.success($localize`:@@toast.gdpr.basisRecorded:Lawful basis recorded.`),
      error: () => this.toast.error($localize`:@@toast.gdpr.basisRecordFailed:Could not record the lawful basis.`)
    });
  }

  async withdrawBasis(): Promise<void> {
    const ok = await this.confirm.confirm({
      title: $localize`:@@confirm.gdpr.withdrawBasis.title:Withdraw lawful basis?`,
      body: $localize`:@@confirm.gdpr.withdrawBasis.body:The recorded lawful basis for contacting candidate ${this.candidateId}:candidateId: will be withdrawn.`,
      confirmLabel: $localize`:@@confirm.gdpr.withdrawBasis.cta:Withdraw`
    });
    if (!ok) { return; }
    this.gdpr.withdrawBasis(this.candidateId).subscribe({
      next: () => this.toast.success($localize`:@@toast.gdpr.basisWithdrawn:Lawful basis withdrawn.`),
      error: () => this.toast.error($localize`:@@toast.gdpr.basisWithdrawFailed:Could not withdraw the lawful basis.`)
    });
  }

  async erase(): Promise<void> {
    const ok = await this.confirm.confirm({
      title: $localize`:@@confirm.gdpr.erase.title:Erase candidate data?`,
      body: $localize`:@@confirm.gdpr.erase.body:This permanently erases ${this.candidateId}:candidateId:'s personal data. This cannot be undone.`,
      confirmLabel: $localize`:@@confirm.gdpr.erase.cta:Erase data`,
      danger: true
    });
    if (!ok) { return; }
    this.gdpr.erase(this.candidateId).subscribe({
      next: () => this.toast.success($localize`:@@toast.gdpr.erased:Candidate data erased.`),
      error: () => this.toast.error($localize`:@@toast.gdpr.eraseFailed:Could not erase the candidate.`)
    });
  }
}
