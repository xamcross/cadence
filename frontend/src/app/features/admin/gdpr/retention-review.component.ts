import { Component, OnInit, inject, signal } from '@angular/core';
import { GdprService, FlaggedView } from './gdpr.service';

/** Admin-only review of retention-flagged candidates with confirm-delete (F04 US5). */
@Component({
  selector: 'app-retention-review',
  standalone: true,
  template: `
    <section class="gdpr">
      <h1 i18n="@@gdpr.retention.title">Records over the retention period</h1>
      @if (loaded() && flagged().length === 0) {
        <p class="empty" i18n="@@gdpr.retention.empty">No candidate records are over the retention period.</p>
      }
      @for (f of flagged(); track f.candidateId) {
        <div class="row">
          <span>{{ f.candidateId }}</span>
          <span i18n="@@gdpr.retention.lastContact">Last activity: {{ f.lastContactAt }}</span>
          <span i18n="@@gdpr.retention.flaggedAt">Flagged: {{ f.retentionFlaggedAt }}</span>
          @if (confirmingId() !== f.candidateId) {
            <button type="button" class="danger btn btn--danger-soft btn--sm" (click)="confirmingId.set(f.candidateId)"
                    i18n="@@gdpr.retention.delete">Delete record</button>
          } @else {
            <span i18n="@@gdpr.retention.confirmPrompt">Permanently delete?</span>
            <button type="button" class="danger btn btn--danger btn--sm" (click)="del(f.candidateId)"
                    i18n="@@gdpr.retention.confirm">Confirm</button>
            <button type="button" class="btn btn--ghost btn--sm" (click)="confirmingId.set(null)" i18n="@@gdpr.retention.cancel">Cancel</button>
          }
        </div>
      }
      @if (message()) {
        <p role="alert" class="alert alert--accent msg">{{ message() }}</p>
      }
    </section>
  `,
  styles: [`
    .gdpr { padding: var(--space-4); }
    .row { display: flex; gap: var(--space-4); align-items: center; padding: var(--space-2) 0; border-bottom: 1px solid var(--line); }
    .msg { margin-top: var(--space-4); }
  `]
})
export class RetentionReviewComponent implements OnInit {
  private readonly gdpr = inject(GdprService);

  readonly flagged = signal<FlaggedView[]>([]);
  readonly loaded = signal(false);
  readonly message = signal('');
  readonly confirmingId = signal<string | null>(null);

  ngOnInit(): void {
    this.refresh();
  }

  refresh(): void {
    this.gdpr.listFlagged().subscribe({
      next: (v) => {
        this.flagged.set(v.flagged);
        this.loaded.set(true);
      },
      error: () => this.message.set($localize`:@@gdpr.retention.loadError:Could not load flagged records.`)
    });
  }

  del(candidateId: string): void {
    this.confirmingId.set(null);
    this.gdpr.deleteFlagged(candidateId).subscribe({
      next: () => {
        this.message.set($localize`:@@gdpr.retention.deleted:Record deleted.`);
        this.refresh();
      },
      error: () => this.message.set($localize`:@@gdpr.retention.deleteError:Could not delete the record.`)
    });
  }
}
