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
        <ng-container *ngIf="statusView() as s">
          <!-- F23: confirmation-cascade indicator — only meaningful while there is a live BOOKED interview. -->
          <span class="chip confirmation" *ngIf="canManage(s)">{{ confirmationLabel(s) }}</span>
          <button type="button" *ngIf="canManage(s)" [disabled]="busy()" (click)="reschedule()"
                  i18n="@@scheduling.reschedule">Reschedule</button>
          <button type="button" *ngIf="canManage(s)" [disabled]="busy()" (click)="cancel()"
                  i18n="@@scheduling.cancel">Cancel interview</button>
          <!-- F23: one-tap release of an unconfirmed slot — emphasised once the interview has escalated. -->
          <button type="button" *ngIf="canManage(s)" [disabled]="busy()" (click)="release()"
                  i18n="@@scheduling.release">Release slot</button>
        </ng-container>
      </div>
      <p class="ok" *ngIf="manageMsg()" role="status">{{ manageMsg() }}</p>
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
  readonly manageMsg = signal<string | null>(null);

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

  /** F20: reschedule/cancel are offered only while there is a live booking. */
  canManage(s: StatusResponse): boolean {
    return s.status === 'BOOKED' || s.status === 'RESCHEDULE_IN_PROGRESS';
  }

  reschedule(): void {
    this.busy.set(true);
    this.manageMsg.set(null);
    this.api.reschedule(this.candidateId).subscribe({
      next: () => {
        this.manageMsg.set($localize`:@@scheduling.rescheduled:Reschedule invitation sent — the booking stays until the candidate picks a new time.`);
        this.busy.set(false);
        this.refreshStatus();
      },
      error: (e: HttpErrorResponse) => { this.manageMsg.set(this.manageError(e)); this.busy.set(false); }
    });
  }

  cancel(): void {
    this.busy.set(true);
    this.manageMsg.set(null);
    this.api.cancel(this.candidateId).subscribe({
      next: () => {
        this.manageMsg.set($localize`:@@scheduling.cancelled:Interview cancelled — the candidate has been notified.`);
        this.busy.set(false);
        this.refreshStatus();
      },
      error: (e: HttpErrorResponse) => { this.manageMsg.set(this.manageError(e)); this.busy.set(false); }
    });
  }

  /** F23 US2: one-tap release of an unconfirmed slot — frees the slot and notifies the candidate. */
  release(): void {
    this.busy.set(true);
    this.manageMsg.set(null);
    this.api.release(this.candidateId).subscribe({
      next: (r) => {
        this.manageMsg.set(r.cleanupIncomplete
          ? $localize`:@@scheduling.released.cleanup:Slot released — one calendar event could not be removed and has been flagged for follow-up.`
          : $localize`:@@scheduling.released:Slot released — the events were removed and the candidate has been notified.`);
        this.busy.set(false);
        this.refreshStatus();
      },
      error: (e: HttpErrorResponse) => { this.manageMsg.set(this.manageError(e)); this.busy.set(false); }
    });
  }

  /** F23: a coarse confirmation-status chip for a live booking (no oracle — derived from the status view). */
  confirmationLabel(s: StatusResponse): string {
    if (s.candidateConfirmed) { return $localize`:@@scheduling.confirm.confirmed:Attendance confirmed`; }
    if (s.escalated) { return $localize`:@@scheduling.confirm.unconfirmed:Unconfirmed`; }
    if (s.confirmationRequested) { return $localize`:@@scheduling.confirm.requested:Confirmation requested`; }
    return $localize`:@@scheduling.confirm.awaiting:Awaiting confirmation request`;
  }

  statusLabel(s: StatusResponse): string {
    switch (s.status) {
      case 'PENDING_SELECTION': return $localize`:@@scheduling.status.sent:Link sent`;
      case 'BOOKED': return $localize`:@@scheduling.status.booked:Scheduled`;
      case 'EXPIRED': return $localize`:@@scheduling.status.expired:Link expired`;
      case 'RESCHEDULE_IN_PROGRESS': return $localize`:@@scheduling.status.rescheduling:Reschedule in progress`;
      case 'RESCHEDULED': return $localize`:@@scheduling.status.rescheduled:Rescheduled`;
      case 'CANCELLED': return $localize`:@@scheduling.status.cancelled:Cancelled`;
      default: return s.status;
    }
  }

  private manageError(e: HttpErrorResponse): string {
    const code = e.error?.error;
    if (code === 'no_slots') { return $localize`:@@scheduling.manage.noSlots:No alternative times available — the original booking was kept.`; }
    if (code === 'not_contactable') { return $localize`:@@scheduling.manage.notContactable:This candidate cannot currently be contacted.`; }
    if (code === 'no_active_booking') { return $localize`:@@scheduling.manage.noBooking:This candidate has no booked interview.`; }
    if (code === 'ineligible') { return $localize`:@@scheduling.manage.ineligible:This interview can no longer be changed — its start time has already passed.`; }
    return $localize`:@@scheduling.manage.generic:Could not complete that action — please try again.`;
  }

  private messageFor(e: HttpErrorResponse): string {
    const code = e.error?.error;
    if (code === 'no_slots') { return $localize`:@@scheduling.err.noSlots:No available slots — widen the window or adjust the template.`; }
    if (code === 'not_contactable') { return $localize`:@@scheduling.err.notContactable:This candidate cannot currently be contacted.`; }
    if (code === 'unschedulable_required_member') { return $localize`:@@scheduling.err.unschedulable:A required interviewer's calendar is not connected.`; }
    return $localize`:@@scheduling.err.generic:Could not send the scheduling link.`;
  }
}
