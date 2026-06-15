import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { GdprService } from './gdpr.service';

/**
 * Admin/Recruiter erasure trigger + lawful-basis record/withdraw, keyed by a pasted candidate internal
 * id (no candidate browser — F51). Erasure is destructive and irreversible, so it requires an explicit
 * confirmation step. The server enforces the role; this surface is UX + defense-in-depth.
 */
@Component({
  selector: 'app-candidate-erasure-action',
  standalone: true,
  imports: [FormsModule],
  template: `
    <section class="gdpr">
      <h1 i18n="@@gdpr.action.title">Candidate data actions</h1>
      <label for="erase-cid" i18n="@@gdpr.action.candidateId">Candidate ID</label>
      <input id="erase-cid" name="cid" [(ngModel)]="candidateId" />

      <fieldset>
        <legend i18n="@@gdpr.basis.legend">Lawful basis (email)</legend>
        <select name="basis" [(ngModel)]="basis" aria-label="Lawful basis" i18n-aria-label="@@gdpr.basis.aria">
          <option value="LEGITIMATE_INTEREST" i18n="@@gdpr.basis.li">Legitimate interest</option>
          <option value="CONSENT" i18n="@@gdpr.basis.consent">Consent</option>
          <option value="CONTRACT" i18n="@@gdpr.basis.contract">Contract</option>
        </select>
        <button type="button" (click)="recordBasis()" i18n="@@gdpr.basis.record">Record basis</button>
        <button type="button" (click)="withdrawBasis()" i18n="@@gdpr.basis.withdraw">Withdraw basis</button>
      </fieldset>

      <fieldset>
        <legend i18n="@@gdpr.erase.legend">Erase personal data</legend>
        @if (!confirming()) {
          <button type="button" class="danger" (click)="confirming.set(true)" i18n="@@gdpr.erase.start">
            Erase candidate data
          </button>
        } @else {
          <p i18n="@@gdpr.erase.confirmPrompt">This permanently erases the candidate's personal data. Continue?</p>
          <button type="button" class="danger" (click)="erase()" i18n="@@gdpr.erase.confirm">Confirm erasure</button>
          <button type="button" (click)="confirming.set(false)" i18n="@@gdpr.erase.cancel">Cancel</button>
        }
      </fieldset>

      @if (message()) {
        <p role="alert" class="msg">{{ message() }}</p>
      }
    </section>
  `,
  styles: [`
    .gdpr { padding: 1rem; max-width: 32rem; }
    button { min-height: 44px; margin-right: 0.5rem; }
    .danger { color: #b00020; }
    .msg { margin-top: 1rem; }
    input, select { min-height: 44px; }
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
