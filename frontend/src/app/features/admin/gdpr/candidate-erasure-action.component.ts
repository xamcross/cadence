import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { GdprService } from './gdpr.service';
import { PageHeaderComponent } from '../../../shared/ui/page-header.component';

/**
 * Admin/Recruiter erasure trigger + lawful-basis record/withdraw, keyed by a pasted candidate internal
 * id (no candidate browser — F51). Erasure is destructive and irreversible, so it requires an explicit
 * confirmation step. The server enforces the role; this surface is UX + defense-in-depth.
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
        @if (!confirming()) {
          <button type="button" class="danger btn btn--danger-soft" (click)="confirming.set(true)" i18n="@@gdpr.erase.start">
            Erase candidate data
          </button>
        } @else {
          <p i18n="@@gdpr.erase.confirmPrompt">This permanently erases the candidate's personal data. Continue?</p>
          <button type="button" class="danger btn btn--danger" (click)="erase()" i18n="@@gdpr.erase.confirm">Confirm erasure</button>
          <button type="button" class="btn btn--ghost" (click)="confirming.set(false)" i18n="@@gdpr.erase.cancel">Cancel</button>
        }
      </fieldset>

      @if (message()) {
        <p role="alert" class="alert alert--accent msg">{{ message() }}</p>
      }
    </section>
  `,
  styles: [`
    .gdpr { padding: var(--space-4); max-width: 32rem; }
    fieldset { margin-bottom: var(--space-4); }
    button { margin-right: var(--space-2); }
    .msg { margin-top: var(--space-4); }
  `]
})
export class CandidateErasureActionComponent {
  private readonly gdpr = inject(GdprService);

  candidateId = '';
  basis = 'LEGITIMATE_INTEREST';
  readonly confirming = signal(false);
  readonly message = signal('');

  recordBasis(): void {
    this.gdpr.recordBasis(this.candidateId, this.basis).subscribe({
      next: () => this.message.set($localize`:@@gdpr.basis.recorded:Lawful basis recorded.`),
      error: () => this.message.set($localize`:@@gdpr.basis.error:Could not record the lawful basis.`)
    });
  }

  withdrawBasis(): void {
    this.gdpr.withdrawBasis(this.candidateId).subscribe({
      next: () => this.message.set($localize`:@@gdpr.basis.withdrawn:Lawful basis withdrawn.`),
      error: () => this.message.set($localize`:@@gdpr.basis.withdrawError:Could not withdraw the lawful basis.`)
    });
  }

  erase(): void {
    this.confirming.set(false);
    this.gdpr.erase(this.candidateId).subscribe({
      next: () => this.message.set($localize`:@@gdpr.erase.done:Candidate data erased.`),
      error: () => this.message.set($localize`:@@gdpr.erase.error:Could not erase the candidate.`)
    });
  }
}
