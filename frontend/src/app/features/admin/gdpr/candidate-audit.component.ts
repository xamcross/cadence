import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { GdprService, AuditEntry } from './gdpr.service';
import { PageHeaderComponent } from '../../../shared/ui/page-header.component';
import { EmptyStateComponent } from '../../../shared/ui/empty-state.component';
import { SkeletonComponent } from '../../../shared/ui/skeleton.component';
import { TableScrollComponent } from '../../../shared/ui/table-scroll.component';

/** Admin-only candidate audit-log view, keyed by a pasted candidate internal id (F04 US3). Non-PII. */
@Component({
  selector: 'app-candidate-audit',
  standalone: true,
  imports: [FormsModule, PageHeaderComponent, EmptyStateComponent, SkeletonComponent, TableScrollComponent],
  template: `
    <section class="gdpr">
      <app-page-header
        eyebrow="Data &amp; privacy" i18n-eyebrow="@@gdpr.audit.eyebrow"
        heading="Candidate audit log" i18n-heading="@@gdpr.audit.title"
        subtitle="Per-candidate access and change history." i18n-subtitle="@@gdpr.audit.subtitle">
      </app-page-header>
      <div class="field">
        <label for="audit-cid" class="field__label" i18n="@@gdpr.audit.candidateId">Candidate ID</label>
        <input id="audit-cid" name="cid" class="input" [(ngModel)]="candidateId" />
      </div>
      <button type="button" class="btn btn--primary" (click)="load()" i18n="@@gdpr.audit.load">Load log</button>

      @if (loading()) {
        <app-skeleton variant="table" />
      } @else if (loaded() && entries().length === 0) {
        <app-empty-state
          heading="No audit entries" i18n-heading="@@gdpr.audit.empty.heading"
          body="This candidate has no recorded activity yet." i18n-body="@@gdpr.audit.empty.body">
        </app-empty-state>
      } @else if (loaded()) {
        <app-table-scroll ariaLabel="Audit entries" i18n-ariaLabel="@@gdpr.audit.tableLabel">
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
        </app-table-scroll>
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
  /** Only true while a lookup triggered by load() is in flight — this screen is search-first, not
   *  auto-fetched on init, so there is nothing to load until a candidate id is submitted. */
  readonly loading = signal(false);
  readonly error = signal('');

  load(): void {
    this.error.set('');
    this.loading.set(true);
    this.gdpr.audit(this.candidateId).subscribe({
      next: (log) => {
        this.entries.set(log.entries);
        this.loaded.set(true);
        this.loading.set(false);
      },
      error: () => {
        this.error.set($localize`:@@gdpr.audit.error:Could not load the audit log.`);
        this.loading.set(false);
      }
    });
  }
}
