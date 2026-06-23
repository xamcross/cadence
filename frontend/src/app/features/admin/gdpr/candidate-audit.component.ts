import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { GdprService, AuditEntry } from './gdpr.service';

/** Admin-only candidate audit-log view, keyed by a pasted candidate internal id (F04 US3). Non-PII. */
@Component({
  selector: 'app-candidate-audit',
  standalone: true,
  imports: [FormsModule],
  template: `
    <section class="gdpr">
      <h1 i18n="@@gdpr.audit.title">Candidate audit log</h1>
      <div class="field">
        <label for="audit-cid" class="field__label" i18n="@@gdpr.audit.candidateId">Candidate ID</label>
        <input id="audit-cid" name="cid" class="input" [(ngModel)]="candidateId" />
      </div>
      <button type="button" class="btn btn--primary" (click)="load()" i18n="@@gdpr.audit.load">Load log</button>

      @if (loaded()) {
        @if (entries().length === 0) {
          <p class="empty" i18n="@@gdpr.audit.empty">No audit entries for this candidate.</p>
        } @else {
          <table class="table">
            <thead>
              <tr>
                <th i18n="@@gdpr.audit.when">When</th>
                <th i18n="@@gdpr.audit.event">Event</th>
                <th i18n="@@gdpr.audit.outcome">Outcome</th>
                <th i18n="@@gdpr.audit.actor">Actor</th>
              </tr>
            </thead>
            <tbody>
              @for (e of entries(); track $index) {
                <tr>
                  <td>{{ e.occurredAt }}</td>
                  <td>{{ e.eventType }}</td>
                  <td>{{ e.outcome }}</td>
                  <td>{{ e.actorMemberId ?? 'system' }}</td>
                </tr>
              }
            </tbody>
          </table>
        }
      }
      @if (error()) {
        <p role="alert" class="alert alert--danger msg">{{ error() }}</p>
      }
    </section>
  `,
  styles: [`
    .gdpr { padding: var(--space-4); }
    .table { margin-top: var(--space-4); }
    .msg { margin-top: var(--space-4); }
  `]
})
export class CandidateAuditComponent {
  private readonly gdpr = inject(GdprService);

  candidateId = '';
  readonly entries = signal<AuditEntry[]>([]);
  readonly loaded = signal(false);
  readonly error = signal('');

  load(): void {
    this.error.set('');
    this.gdpr.audit(this.candidateId).subscribe({
      next: (log) => {
        this.entries.set(log.entries);
        this.loaded.set(true);
      },
      error: () => this.error.set($localize`:@@gdpr.audit.error:Could not load the audit log.`)
    });
  }
}
