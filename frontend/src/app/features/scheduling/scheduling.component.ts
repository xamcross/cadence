import { Component, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import {
  InitiateResponse,
  PublishStatusRequest,
  RecruiterStatusResponse,
  SchedulingService,
  StatusOutcome,
  StatusResponse
} from './scheduling.service';
import { CandidateSla, DraftPreview, SlaNudgeService, SlaState } from './sla-nudge.service';

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

      <!-- F30: candidate Status panel (US2). Recruiter/Admin publishes the honest status the candidate sees
           on /status, mirroring the server validation (in-progress requires stage + next step + date;
           terminal requires a next-step message). Copy / rotate the candidate's status link. -->
      <div class="status-panel" *ngIf="candidateId">
        <h2 i18n="@@status.panel.title">Candidate status</h2>

        <label>
          <span i18n="@@status.panel.outcome">Outcome</span>
          <select name="statusOutcome" [(ngModel)]="statusOutcome">
            <option value="IN_PROGRESS" i18n="@@status.panel.outcome.inProgress">In progress</option>
            <option value="COMPLETE_OFFER" i18n="@@status.panel.outcome.offer">Offer</option>
            <option value="COMPLETE_REJECTED" i18n="@@status.panel.outcome.rejected">Not progressing</option>
          </select>
        </label>

        <label *ngIf="statusOutcome === 'IN_PROGRESS'">
          <span i18n="@@status.panel.stage">Current stage</span>
          <input name="statusStage" [(ngModel)]="statusStage" />
        </label>

        <label>
          <span i18n="@@status.panel.next">What happens next</span>
          <textarea name="statusNextStep" [(ngModel)]="statusNextStep" rows="2"></textarea>
        </label>

        <label *ngIf="statusOutcome === 'IN_PROGRESS'">
          <span i18n="@@status.panel.date">Expected by</span>
          <input name="statusExpectedDate" type="date" [(ngModel)]="statusExpectedDate" />
        </label>

        <button type="button" class="status-publish" [disabled]="busy() || !statusValid()"
                (click)="publishStatus()" i18n="@@status.panel.publish">Publish status</button>

        <p class="err" *ngIf="!statusValid() && statusTouched()" role="status" i18n="@@status.panel.invalid">
          An in-progress status needs a stage, a next step, and an expected date. A concluded status needs a closing message.
        </p>
        <p class="ok" *ngIf="statusMsg()" role="status">{{ statusMsg() }}</p>

        <div class="status-link" *ngIf="statusLink()">
          <span class="link-value">{{ statusLink() }}</span>
          <button type="button" (click)="copyStatusLink()" i18n="@@status.panel.copy">Copy status link</button>
          <button type="button" [disabled]="busy()" (click)="rotateStatusLink()"
                  i18n="@@status.panel.rotate">Rotate link</button>
        </div>
        <button type="button" *ngIf="!statusLink()" (click)="loadStatus()"
                i18n="@@status.panel.load">Load status</button>
      </div>

      <!-- F31: SLA nudge panel (US2/US3). The candidate's green/amber/red communication-health badge plus the
           queued holding-message draft; the recruiter previews and approves (one consent-gated send) or dismisses.
           Internal screen (Lighthouse/WCAG N/A — F50/F51 precedent). No auto-send. -->
      <div class="sla-nudge-panel" *ngIf="candidateId">
        <h2 i18n="@@sla.panel.title">Communication SLA</h2>
        <button type="button" (click)="loadSla()" i18n="@@sla.panel.load">Check SLA status</button>
        <ng-container *ngIf="sla() as s">
          <span class="sla-badge" [class.green]="s.slaState === 'GREEN'" [class.amber]="s.slaState === 'AMBER'"
                [class.red]="s.slaState === 'RED'">{{ slaLabel(s.slaState) }}</span>

          <div class="sla-draft" *ngIf="s.openDraftId">
            <p i18n="@@sla.panel.draftPending">A holding message is queued for your approval.</p>
            <button type="button" [disabled]="slaBusy()" (click)="previewDraft()"
                    i18n="@@sla.panel.preview">Preview draft</button>
            <div class="sla-preview" *ngIf="draftPreview() as p">
              <p class="subject">{{ p.subject }}</p>
              <p class="body" style="white-space: pre-wrap">{{ p.body }}</p>
              <p class="warn" *ngIf="p.missingFields.length" role="status" i18n="@@sla.panel.missing">
                Some details are missing and shown as placeholders — fill in the candidate's status first.
              </p>
            </div>
            <button type="button" class="sla-approve" [disabled]="slaBusy()" (click)="approveDraft(s.openDraftId)"
                    i18n="@@sla.panel.approve">Approve and send</button>
            <button type="button" class="sla-dismiss" [disabled]="slaBusy()" (click)="dismissDraft(s.openDraftId)"
                    i18n="@@sla.panel.dismiss">Dismiss</button>
          </div>
        </ng-container>
        <p class="ok" *ngIf="slaMsg()" role="status">{{ slaMsg() }}</p>
      </div>
    </section>
  `
})
export class SchedulingComponent {
  private readonly api = inject(SchedulingService);
  private readonly slaApi = inject(SlaNudgeService);

  // F31 SLA nudge panel state.
  readonly sla = signal<CandidateSla | null>(null);
  readonly draftPreview = signal<DraftPreview | null>(null);
  readonly slaBusy = signal(false);
  readonly slaMsg = signal<string | null>(null);

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

  // F30 candidate-status panel state.
  statusOutcome: StatusOutcome = 'IN_PROGRESS';
  statusStage = '';
  statusNextStep = '';
  statusExpectedDate = '';
  readonly statusLink = signal<string | null>(null);
  readonly statusMsg = signal<string | null>(null);
  readonly statusTouched = signal(false);

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

  // ---- F30 candidate status panel ----

  /** Client validation mirroring the server rules (data-model §4): IN_PROGRESS requires stage + next step
   *  + expected date; a terminal outcome requires a non-blank next-step (closing) message. */
  statusValid(): boolean {
    const next = this.statusNextStep.trim();
    if (this.statusOutcome === 'IN_PROGRESS') {
      return this.statusStage.trim().length > 0 && next.length > 0 && this.statusExpectedDate.trim().length > 0;
    }
    return next.length > 0;
  }

  publishStatus(): void {
    this.statusTouched.set(true);
    if (!this.candidateId || !this.statusValid()) { return; }
    this.busy.set(true);
    this.statusMsg.set(null);
    const req: PublishStatusRequest = {
      outcome: this.statusOutcome,
      stage: this.statusOutcome === 'IN_PROGRESS' ? this.statusStage.trim() : null,
      nextStep: this.statusNextStep.trim(),
      expectedDate: this.statusOutcome === 'IN_PROGRESS' ? (this.statusExpectedDate || null) : null
    };
    this.api.publishStatus(this.candidateId, req).subscribe({
      next: (r: RecruiterStatusResponse) => {
        this.applyStatus(r);
        this.statusMsg.set($localize`:@@status.panel.published:Status published — the candidate can now see it.`);
        this.busy.set(false);
      },
      error: (e: HttpErrorResponse) => { this.statusMsg.set(this.statusError(e)); this.busy.set(false); }
    });
  }

  loadStatus(): void {
    if (!this.candidateId) { return; }
    this.api.readStatus(this.candidateId).subscribe({
      next: (r: RecruiterStatusResponse) => this.applyStatus(r),
      error: (e: HttpErrorResponse) => this.statusMsg.set(this.statusError(e))
    });
  }

  rotateStatusLink(): void {
    if (!this.candidateId) { return; }
    this.busy.set(true);
    this.statusMsg.set(null);
    this.api.rotateStatusLink(this.candidateId).subscribe({
      next: (r) => {
        this.statusLink.set(r.statusLink);
        this.statusMsg.set($localize`:@@status.panel.rotated:Link rotated — the previous link no longer works.`);
        this.busy.set(false);
      },
      error: (e: HttpErrorResponse) => { this.statusMsg.set(this.statusError(e)); this.busy.set(false); }
    });
  }

  copyStatusLink(): void {
    const link = this.statusLink();
    if (!link) { return; }
    // Best-effort clipboard copy; never throws into the UI.
    navigator.clipboard?.writeText(link).then(
      () => this.statusMsg.set($localize`:@@status.panel.copied:Status link copied to the clipboard.`),
      () => this.statusMsg.set($localize`:@@status.panel.copyFailed:Could not copy — please copy the link manually.`)
    );
  }

  private applyStatus(r: RecruiterStatusResponse): void {
    this.statusLink.set(r.statusLink ?? null);
    if (r.outcome) { this.statusOutcome = r.outcome; }
    this.statusStage = r.stage ?? '';
    this.statusNextStep = r.nextStep ?? '';
    this.statusExpectedDate = r.expectedDate ?? '';
  }

  private statusError(e: HttpErrorResponse): string {
    const code = e.error?.error;
    if (code === 'invalid_status') {
      return $localize`:@@status.panel.err.invalid:That status is incomplete — check the required fields.`;
    }
    if (e.status === 404) {
      return $localize`:@@status.panel.err.notFound:This candidate could not be found.`;
    }
    if (e.status === 403) {
      return $localize`:@@status.panel.err.forbidden:You do not have permission to change this candidate's status.`;
    }
    return $localize`:@@status.panel.err.generic:Could not complete that action — please try again.`;
  }

  private messageFor(e: HttpErrorResponse): string {
    const code = e.error?.error;
    if (code === 'no_slots') { return $localize`:@@scheduling.err.noSlots:No available slots — widen the window or adjust the template.`; }
    if (code === 'not_contactable') { return $localize`:@@scheduling.err.notContactable:This candidate cannot currently be contacted.`; }
    if (code === 'unschedulable_required_member') { return $localize`:@@scheduling.err.unschedulable:A required interviewer's calendar is not connected.`; }
    return $localize`:@@scheduling.err.generic:Could not send the scheduling link.`;
  }

  // ---- F31 SLA nudge panel ----

  slaLabel(state: SlaState): string {
    switch (state) {
      case 'GREEN': return $localize`:@@sla.badge.green:Within SLA`;
      case 'AMBER': return $localize`:@@sla.badge.amber:Nearing SLA breach`;
      case 'RED': return $localize`:@@sla.badge.red:Overdue — in silence`;
      default: return state;
    }
  }

  loadSla(): void {
    if (!this.candidateId) { return; }
    this.slaMsg.set(null);
    this.draftPreview.set(null);
    this.slaApi.getSla(this.candidateId).subscribe({
      next: (s) => this.sla.set(s),
      error: (e: HttpErrorResponse) => { this.sla.set(null); this.slaMsg.set(this.slaError(e)); }
    });
  }

  previewDraft(): void {
    if (!this.candidateId) { return; }
    this.slaBusy.set(true);
    this.slaApi.previewDraft(this.candidateId).subscribe({
      next: (p) => { this.draftPreview.set(p); this.slaBusy.set(false); },
      error: (e: HttpErrorResponse) => { this.slaMsg.set(this.slaError(e)); this.slaBusy.set(false); }
    });
  }

  approveDraft(draftId: string): void {
    this.slaBusy.set(true);
    this.slaMsg.set(null);
    this.slaApi.approve(draftId).subscribe({
      next: () => {
        this.slaBusy.set(false);
        this.draftPreview.set(null);
        this.loadSla(); // refresh the badge first (it clears slaMsg) ...
        this.slaMsg.set($localize`:@@sla.panel.approved:Holding message sent — the candidate is no longer in silence.`); // ... then set the result
      },
      error: (e: HttpErrorResponse) => { this.slaMsg.set(this.slaError(e)); this.slaBusy.set(false); }
    });
  }

  dismissDraft(draftId: string): void {
    this.slaBusy.set(true);
    this.slaMsg.set(null);
    this.slaApi.dismiss(draftId).subscribe({
      next: () => {
        this.slaBusy.set(false);
        this.draftPreview.set(null);
        this.loadSla(); // refresh the badge first (it clears slaMsg) ...
        this.slaMsg.set($localize`:@@sla.panel.dismissed:Draft dismissed — nothing was sent.`); // ... then set the result
      },
      error: (e: HttpErrorResponse) => { this.slaMsg.set(this.slaError(e)); this.slaBusy.set(false); }
    });
  }

  private slaError(e: HttpErrorResponse): string {
    if (e.status === 404) { return $localize`:@@sla.panel.err.notFound:No SLA information for this candidate.`; }
    if (e.status === 403) { return $localize`:@@sla.panel.err.forbidden:You do not have permission to view this.`; }
    return $localize`:@@sla.panel.err.generic:Could not complete that action — please try again.`;
  }
}
