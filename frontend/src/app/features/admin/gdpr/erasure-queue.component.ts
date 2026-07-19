import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { GdprService, ErasureRequestView } from './gdpr.service';
import { PageHeaderComponent } from '../../../shared/ui/page-header.component';
import { EmptyStateComponent } from '../../../shared/ui/empty-state.component';
import { SkeletonComponent } from '../../../shared/ui/skeleton.component';
import { ConfirmDialogService } from '../../../shared/ui/confirm-dialog.service';
import { ToastService } from '../../../shared/ui/toast.service';

/**
 * Admin-only pending erasure-request queue (F04 US4). Confirm runs the shared wipe; reject records a
 * chosen non-PII reason code.
 *
 * Phase 3b (workbench overhaul): `confirm(id)` (⚠ danger) and `reject(id)` are gated behind the shared
 * `ConfirmDialogService` — injected as `dialog` since this component already has a method named
 * `confirm`. Outcomes are surfaced via `ToastService` instead of a shared `message` signal.
 */
@Component({
  selector: 'app-erasure-queue',
  standalone: true,
  imports: [FormsModule, PageHeaderComponent, EmptyStateComponent, SkeletonComponent],
  template: `
    <section class="gdpr">
      <app-page-header
        eyebrow="Data &amp; privacy" i18n-eyebrow="@@gdpr.queue.eyebrow"
        heading="Pending erasure requests" i18n-heading="@@gdpr.queue.title"
        subtitle="Pending right-to-be-forgotten queue." i18n-subtitle="@@gdpr.queue.subtitle">
      </app-page-header>

      @if (loading()) {
        <app-skeleton variant="lines" />
      } @else if (loaded() && requests().length === 0) {
        <app-empty-state
          heading="Queue is empty" i18n-heading="@@gdpr.queue.empty.heading"
          body="There are no pending erasure requests right now." i18n-body="@@gdpr.queue.empty.body">
        </app-empty-state>
      } @else {
        @for (r of requests(); track r.id) {
          <div class="row">
            <span>{{ r.candidateId }}</span>
            <span>{{ r.createdAt }}</span>
            <button type="button" class="btn btn--danger btn--sm" (click)="confirm(r.id)" i18n="@@gdpr.queue.confirm">Confirm erasure</button>
            <select class="input" [ngModel]="reasonFor(r.id)" (ngModelChange)="setReason(r.id, $event)"
                    aria-label="Rejection reason" i18n-aria-label="@@gdpr.queue.reasonAria">
              <option value="OTHER" i18n="@@gdpr.queue.reason.other">Other</option>
              <option value="NOT_A_CANDIDATE" i18n="@@gdpr.queue.reason.notCandidate">Not a candidate</option>
            </select>
            <button type="button" class="btn btn--ghost btn--sm" (click)="reject(r.id)" i18n="@@gdpr.queue.reject">Reject</button>
          </div>
        }
      }
    </section>
  `,
  styles: [`
    .gdpr { padding: var(--space-4); }
    .row { display: flex; gap: var(--space-4); align-items: center; padding: var(--space-2) 0; border-bottom: 1px solid var(--line); }
    .row select.input { width: auto; }
  `]
})
export class ErasureQueueComponent implements OnInit {
  private readonly gdpr = inject(GdprService);
  private readonly dialog = inject(ConfirmDialogService);
  private readonly toast = inject(ToastService);

  readonly requests = signal<ErasureRequestView[]>([]);
  readonly loaded = signal(false);
  readonly loading = signal(true);
  private readonly reasons: Record<string, string> = {};

  ngOnInit(): void {
    this.refresh();
  }

  reasonFor(id: string): string {
    return this.reasons[id] ?? 'OTHER';
  }

  setReason(id: string, value: string): void {
    this.reasons[id] = value;
  }

  refresh(): void {
    this.gdpr.listRequests().subscribe({
      next: (v) => {
        this.requests.set(v.requests);
        this.loaded.set(true);
        this.loading.set(false);
      },
      error: () => {
        this.toast.error($localize`:@@toast.gdpr.queue.loadError:Could not load erasure requests.`);
        this.loading.set(false);
      }
    });
  }

  async confirm(id: string): Promise<void> {
    const ok = await this.dialog.confirm({
      title: $localize`:@@confirm.gdpr.queue.confirm.title:Confirm erasure?`,
      body: $localize`:@@confirm.gdpr.queue.confirm.body:This permanently erases candidate ${id}:id:'s data. This cannot be undone.`,
      confirmLabel: $localize`:@@confirm.gdpr.queue.confirm.cta:Erase permanently`,
      danger: true
    });
    if (!ok) { return; }
    this.gdpr.confirmRequest(id).subscribe({
      next: () => {
        this.toast.success($localize`:@@toast.gdpr.queue.confirmed:Erasure confirmed.`);
        this.refresh();
      },
      error: () => this.toast.error($localize`:@@toast.gdpr.queue.confirmError:Could not confirm the request.`)
    });
  }

  async reject(id: string): Promise<void> {
    const ok = await this.dialog.confirm({
      title: $localize`:@@confirm.gdpr.queue.reject.title:Reject this erasure request?`,
      body: $localize`:@@confirm.gdpr.queue.reject.body:Candidate ${id}:id:'s erasure request will be rejected with the selected reason.`,
      confirmLabel: $localize`:@@confirm.gdpr.queue.reject.cta:Reject request`
    });
    if (!ok) { return; }
    this.gdpr.rejectRequest(id, this.reasonFor(id)).subscribe({
      next: () => {
        this.toast.success($localize`:@@toast.gdpr.queue.rejected:Erasure request rejected.`);
        this.refresh();
      },
      error: () => this.toast.error($localize`:@@toast.gdpr.queue.rejectError:Could not reject the request.`)
    });
  }
}
