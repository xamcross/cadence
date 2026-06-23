import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { GdprService, ErasureRequestView } from './gdpr.service';

/** Admin-only pending erasure-request queue (F04 US4). Confirm runs the shared wipe; reject records a
 *  chosen non-PII reason code. */
@Component({
  selector: 'app-erasure-queue',
  standalone: true,
  imports: [FormsModule],
  template: `
    <section class="gdpr">
      <h1 i18n="@@gdpr.queue.title">Pending erasure requests</h1>
      @if (loaded() && requests().length === 0) {
        <p class="empty" i18n="@@gdpr.queue.empty">There are no pending erasure requests.</p>
      }
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
      @if (message()) {
        <p role="alert" class="alert alert--accent msg">{{ message() }}</p>
      }
    </section>
  `,
  styles: [`
    .gdpr { padding: var(--space-4); }
    .row { display: flex; gap: var(--space-4); align-items: center; padding: var(--space-2) 0; border-bottom: 1px solid var(--line); }
    .row select.input { width: auto; }
    .msg { margin-top: var(--space-4); }
  `]
})
export class ErasureQueueComponent implements OnInit {
  private readonly gdpr = inject(GdprService);

  readonly requests = signal<ErasureRequestView[]>([]);
  readonly loaded = signal(false);
  readonly message = signal('');
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
      },
      error: () => this.message.set($localize`:@@gdpr.queue.loadError:Could not load erasure requests.`)
    });
  }

  confirm(id: string): void {
    this.gdpr.confirmRequest(id).subscribe({
      next: () => {
        this.message.set($localize`:@@gdpr.queue.confirmed:Erasure confirmed.`);
        this.refresh();
      },
      error: () => this.message.set($localize`:@@gdpr.queue.confirmError:Could not confirm the request.`)
    });
  }

  reject(id: string): void {
    this.gdpr.rejectRequest(id, this.reasonFor(id)).subscribe({
      next: () => {
        this.message.set($localize`:@@gdpr.queue.rejected:Erasure request rejected.`);
        this.refresh();
      },
      error: () => this.message.set($localize`:@@gdpr.queue.rejectError:Could not reject the request.`)
    });
  }
}
