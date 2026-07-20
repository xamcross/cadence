import { Component, OnInit, inject, signal } from '@angular/core';
import { GdprService, FlaggedView } from './gdpr.service';
import { PageHeaderComponent } from '../../../shared/ui/page-header.component';
import { EmptyStateComponent } from '../../../shared/ui/empty-state.component';
import { SkeletonComponent } from '../../../shared/ui/skeleton.component';
import { ConfirmDialogService } from '../../../shared/ui/confirm-dialog.service';
import { ToastService } from '../../../shared/ui/toast.service';

/**
 * Admin-only review of retention-flagged candidates with confirm-delete (F04 US5).
 *
 * Phase 3b (workbench overhaul): `del(id)` is gated behind the shared `ConfirmDialogService` (⚠
 * danger), replacing the old hand-rolled `confirmingId`-signal inline two-step prompt. Outcomes are
 * surfaced via `ToastService` instead of a shared `message` signal.
 */
@Component({
  selector: 'app-retention-review',
  standalone: true,
  imports: [PageHeaderComponent, EmptyStateComponent, SkeletonComponent],
  template: `
    <section class="gdpr">
      <app-page-header
        eyebrow="Data &amp; privacy" i18n-eyebrow="@@gdpr.retention.eyebrow"
        heading="Records over the retention period" i18n-heading="@@gdpr.retention.title"
        subtitle="Review and confirm scheduled deletions." i18n-subtitle="@@gdpr.retention.subtitle">
      </app-page-header>

      @if (loading()) {
        <app-skeleton variant="lines" />
      } @else if (loaded() && flagged().length === 0) {
        <app-empty-state
          heading="Nothing flagged" i18n-heading="@@gdpr.retention.empty.heading"
          body="No candidate records are currently over the retention period." i18n-body="@@gdpr.retention.empty.body">
        </app-empty-state>
      } @else {
        @for (f of flagged(); track f.candidateId) {
          <div class="row">
            <span>{{ f.candidateId }}</span>
            <span i18n="@@gdpr.retention.lastContact">Last activity: {{ f.lastContactAt }}</span>
            <span i18n="@@gdpr.retention.flaggedAt">Flagged: {{ f.retentionFlaggedAt }}</span>
            <button type="button" class="danger btn btn--danger-soft btn--sm" (click)="del(f.candidateId)"
                    i18n="@@gdpr.retention.delete">Delete record</button>
          </div>
        }
      }
    </section>
  `,
  styles: [`
    .gdpr { padding: var(--space-4); }
    .row { display: flex; gap: var(--space-4); align-items: center; padding: var(--space-2) 0; border-bottom: 1px solid var(--line); }
  `]
})
export class RetentionReviewComponent implements OnInit {
  private readonly gdpr = inject(GdprService);
  private readonly confirm = inject(ConfirmDialogService);
  private readonly toast = inject(ToastService);

  readonly flagged = signal<FlaggedView[]>([]);
  readonly loaded = signal(false);
  readonly loading = signal(true);

  ngOnInit(): void {
    this.refresh();
  }

  refresh(): void {
    this.gdpr.listFlagged().subscribe({
      next: (v) => {
        this.flagged.set(v.flagged);
        this.loaded.set(true);
        this.loading.set(false);
      },
      error: () => {
        this.toast.error($localize`:@@toast.gdpr.retention.loadError:Could not load flagged records.`);
        this.loading.set(false);
      }
    });
  }

  async del(candidateId: string): Promise<void> {
    const ok = await this.confirm.confirm({
      title: $localize`:@@confirm.gdpr.retention.del.title:Delete this record?`,
      body: $localize`:@@confirm.gdpr.retention.del.body:Candidate ${candidateId}:candidateId:'s record will be permanently deleted. This cannot be undone.`,
      confirmLabel: $localize`:@@confirm.gdpr.retention.del.cta:Delete permanently`,
      danger: true
    });
    if (!ok) { return; }
    this.gdpr.deleteFlagged(candidateId).subscribe({
      next: () => {
        this.toast.success($localize`:@@toast.gdpr.retention.deleted:Record deleted.`);
        this.refresh();
      },
      error: () => this.toast.error($localize`:@@toast.gdpr.retention.deleteError:Could not delete the record.`)
    });
  }
}
