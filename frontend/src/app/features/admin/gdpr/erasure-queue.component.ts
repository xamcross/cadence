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
        <p i18n="@@gdpr.queue.empty">There are no pending erasure requests.</p>
      }
      @for (r of requests(); track r.id) {
        <div class="row">
          <span>{{ r.candidateId }}</span>
          <span>{{ r.createdAt }}</span>
          <button type="button" (click)="confirm(r.id)" i18n="@@gdpr.queue.confirm">Confirm erasure</button>
          <select [ngModel]="reasonFor(r.id)" (ngModelChange)="setReason(r.id, $event)"
                  aria-label="Rejection reason" i18n-aria-label="@@gdpr.queue.reasonAria">
            <option value="OTHER" i18n="@@gdpr.queue.reason.other">Other</option>
            <option value="NOT_A_CANDIDATE" i18n="@@gdpr.queue.reason.notCandidate">Not a candidate</option>
          </select>
          <button type="button" (click)="reject(r.id)" i18n="@@gdpr.queue.reject">Reject</button>
        </div>
      }
      @if (message()) {
        <p role="alert" class="msg">{{ message() }}</p>
      }
    </section>
  `,
  styles: [`
    .gdpr { padding: 1rem; }
    .row { display: flex; gap: 1rem; align-items: center; padding: 0.5rem 0; border-bottom: 1px solid var(--line); }
    button, select { min-height: 44px; }
    .msg { margin-top: 1rem; }
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
