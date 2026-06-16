import { Component, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { InitiateResponse, SchedulingService, StatusResponse } from './scheduling.service';

/**
 * F13 recruiter "Send scheduling link" surface (§II demonstrable leg). Minimal by design — the full
 * pipeline view is F51. Initiates scheduling for a candidate + template and shows the returned status;
 * a separate status lookup reflects "Link sent" / "Scheduled" / "Link expired". All strings $localize'd.
 */
@Component({
  selector: 'app-scheduling',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <section class="scheduling">
      <h1 i18n="@@scheduling.title">Send scheduling link</h1>

      <form (ngSubmit)="send()" #f="ngForm">
        <label i18n="@@scheduling.candidate">Candidate ID
          <input name="candidateId" [(ngModel)]="candidateId" required />
        </label>
        <label i18n="@@scheduling.template">Interview template ID
          <input name="templateId" [(ngModel)]="templateId" required />
        </label>
        <label i18n="@@scheduling.location">Location / dial-in (optional)
          <input name="locationText" [(ngModel)]="locationText" />
        </label>
        <label i18n="@@scheduling.rangeStart">From (optional)
          <input name="rangeStart" type="date" [(ngModel)]="rangeStart" />
        </label>
        <label i18n="@@scheduling.rangeEnd">To (optional)
          <input name="rangeEnd" type="date" [(ngModel)]="rangeEnd" />
        </label>
        <button type="submit" [disabled]="busy() || !candidateId || !templateId"
                i18n="@@scheduling.send">Send scheduling link</button>
      </form>

      <p class="ok" *ngIf="result() as r" i18n="@@scheduling.sent">
        Link sent — {{ r.offeredSlotCount }} slots offered, expires {{ r.expiresAt }}.
      </p>
      <p class="err" *ngIf="error()">{{ error() }}</p>

      <div class="status" *ngIf="candidateId">
        <button type="button" (click)="refreshStatus()" i18n="@@scheduling.refresh">Refresh status</button>
        <span class="chip" *ngIf="statusView() as s">{{ statusLabel(s) }}</span>
      </div>
    </section>
  `
})
export class SchedulingComponent {
  private readonly api = inject(SchedulingService);

  candidateId = '';
  templateId = '';
  locationText = '';
  rangeStart = '';
  rangeEnd = '';

  readonly busy = signal(false);
  readonly result = signal<InitiateResponse | null>(null);
  readonly error = signal<string | null>(null);
  readonly statusView = signal<StatusResponse | null>(null);

  send(): void {
    this.busy.set(true);
    this.error.set(null);
    this.result.set(null);
    this.api.initiate(this.candidateId, {
      templateId: this.templateId,
      locationText: this.locationText || null,
      rangeStart: this.rangeStart || null,
      rangeEnd: this.rangeEnd || null
    }).subscribe({
      next: (r) => { this.result.set(r); this.busy.set(false); this.refreshStatus(); },
      error: (e: HttpErrorResponse) => { this.error.set(this.messageFor(e)); this.busy.set(false); }
    });
  }

  refreshStatus(): void {
    if (!this.candidateId) { return; }
    this.api.status(this.candidateId).subscribe({
      next: (s) => this.statusView.set(s),
      error: () => this.statusView.set(null)
    });
  }

  statusLabel(s: StatusResponse): string {
    switch (s.status) {
      case 'PENDING_SELECTION': return $localize`:@@scheduling.status.sent:Link sent`;
      case 'BOOKED': return $localize`:@@scheduling.status.booked:Scheduled`;
      case 'EXPIRED': return $localize`:@@scheduling.status.expired:Link expired`;
      default: return s.status;
    }
  }

  private messageFor(e: HttpErrorResponse): string {
    const code = e.error?.error;
    if (code === 'no_slots') { return $localize`:@@scheduling.err.noSlots:No available slots — widen the window or adjust the template.`; }
    if (code === 'not_contactable') { return $localize`:@@scheduling.err.notContactable:This candidate cannot currently be contacted.`; }
    if (code === 'unschedulable_required_member') { return $localize`:@@scheduling.err.unschedulable:A required interviewer's calendar is not connected.`; }
    return $localize`:@@scheduling.err.generic:Could not send the scheduling link.`;
  }
}
