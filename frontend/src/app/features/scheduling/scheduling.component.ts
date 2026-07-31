import { Component, OnInit, inject, signal } from '@angular/core';
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
import { PageHeaderComponent } from '../../shared/ui/page-header.component';
import { ConfirmDialogService } from '../../shared/ui/confirm-dialog.service';
import { ToastService } from '../../shared/ui/toast.service';
import { PickerOption, SearchPickerComponent } from '../../shared/ui/search-picker.component';
import { UpgradePromptComponent } from '../../shared/ui/upgrade-prompt.component';
import { PipelineService } from '../pipeline/pipeline.service';
import { InterviewTemplatesService } from '../interview-templates/interview-templates.service';
import { BillingService } from '../admin/billing/billing.service';

/**
 * F13 recruiter "Send scheduling link" surface (§II demonstrable leg). Minimal by design — the full
 * pipeline view is F51. Initiates scheduling for a candidate + template and shows the returned status;
 * a separate status lookup reflects "Link sent" / "Scheduled" / "Link expired". All strings $localize'd.
 */
@Component({
  selector: 'app-scheduling',
  standalone: true,
  imports: [CommonModule, FormsModule, PageHeaderComponent, SearchPickerComponent, UpgradePromptComponent],
  template: `
    <section class="scheduling">
      <app-page-header
        eyebrow="Your work" i18n-eyebrow="@@scheduling.eyebrow"
        heading="Send scheduling link" i18n-heading="@@scheduling.title"
        subtitle="Invite a candidate to self-schedule an interview." i18n-subtitle="@@scheduling.subtitle">
      </app-page-header>

      <form (ngSubmit)="send()" #f="ngForm">
        <div class="field">
          <app-search-picker [options]="candidateOpts()" [value]="candidateId"
            (valueChange)="candidateId = $event ?? ''"
            label="Candidate" i18n-label="@@scheduling.candidate.picker.label"
            placeholder="Search candidates…" i18n-placeholder="@@scheduling.candidate.picker.placeholder">
          </app-search-picker>
        </div>
        <div class="field">
          <app-search-picker [options]="templateOpts()" [value]="templateId"
            (valueChange)="templateId = $event ?? ''"
            label="Interview template" i18n-label="@@scheduling.template.picker.label"
            placeholder="Search interview templates…" i18n-placeholder="@@scheduling.template.picker.placeholder">
          </app-search-picker>
        </div>
        <label class="field" i18n="@@scheduling.location">Location / dial-in (optional)
          <input class="input" name="locationText" [(ngModel)]="locationText" />
        </label>
        <label class="field" i18n="@@scheduling.rangeStart">From (optional)
          <input class="input" name="rangeStart" type="date" [(ngModel)]="rangeStart" />
        </label>
        <label class="field" i18n="@@scheduling.rangeEnd">To (optional)
          <input class="input" name="rangeEnd" type="date" [(ngModel)]="rangeEnd" />
        </label>
        <button type="submit" class="btn btn--primary" [disabled]="busy() || !candidateId || !templateId"
                i18n="@@scheduling.send">Send scheduling link</button>
      </form>

      <p class="ok alert alert--ok" *ngIf="result() as r" i18n="@@scheduling.sent">
        Link sent — {{ r.offeredSlotCount }} slots offered, expires {{ r.expiresAt }}.
      </p>

      <div class="status toolbar" *ngIf="candidateId">
        <button type="button" class="btn btn--ghost btn--sm" (click)="refreshStatus()" i18n="@@scheduling.refresh">Refresh status</button>
        <span class="chip badge badge--accent" *ngIf="statusView() as s">{{ statusLabel(s) }}</span>
        <ng-container *ngIf="statusView() as s">
          <!-- F23: confirmation-cascade indicator — only meaningful while there is a live BOOKED interview. -->
          <span class="chip confirmation badge" *ngIf="canManage(s)">{{ confirmationLabel(s) }}</span>
          <button type="button" class="btn btn--outline btn--sm" *ngIf="canManage(s)" [disabled]="busy()" (click)="reschedule()"
                  i18n="@@scheduling.reschedule">Reschedule</button>
          <button type="button" class="btn btn--danger-soft btn--sm" *ngIf="canManage(s)" [disabled]="busy()" (click)="cancel()"
                  i18n="@@scheduling.cancel">Cancel interview</button>
          <!-- F23: one-tap release of an unconfirmed slot — emphasised once the interview has escalated. -->
          <button type="button" class="btn btn--ghost btn--sm" *ngIf="canManage(s)" [disabled]="busy()" (click)="release()"
                  i18n="@@scheduling.release">Release slot</button>
        </ng-container>
      </div>

      <!-- F30: candidate Status panel (US2). Recruiter/Admin publishes the honest status the candidate sees
           on /status, mirroring the server validation (in-progress requires stage + next step + date;
           terminal requires a next-step message). Copy / rotate the candidate's status link. -->
      <div class="status-panel" *ngIf="candidateId">
        <h2 i18n="@@status.panel.title">Candidate status</h2>

        <label class="field">
          <span i18n="@@status.panel.outcome">Outcome</span>
          <select class="input" name="statusOutcome" [(ngModel)]="statusOutcome">
            <option value="IN_PROGRESS" i18n="@@status.panel.outcome.inProgress">In progress</option>
            <option value="COMPLETE_OFFER" i18n="@@status.panel.outcome.offer">Offer</option>
            <option value="COMPLETE_REJECTED" i18n="@@status.panel.outcome.rejected">Not progressing</option>
          </select>
        </label>

        <label class="field" *ngIf="statusOutcome === 'IN_PROGRESS'">
          <span i18n="@@status.panel.stage">Current stage</span>
          <input class="input" name="statusStage" [(ngModel)]="statusStage" />
        </label>

        <label class="field">
          <span i18n="@@status.panel.next">What happens next</span>
          <textarea class="input" name="statusNextStep" [(ngModel)]="statusNextStep" rows="2"></textarea>
        </label>

        <label class="field" *ngIf="statusOutcome === 'IN_PROGRESS'">
          <span i18n="@@status.panel.date">Expected by</span>
          <input class="input" name="statusExpectedDate" type="date" [(ngModel)]="statusExpectedDate" />
        </label>

        <button type="button" class="status-publish btn btn--primary" [disabled]="busy() || !statusValid()"
                (click)="publishStatus()" i18n="@@status.panel.publish">Publish status</button>

        <p class="err alert alert--danger" *ngIf="!statusValid() && statusTouched()" role="status" i18n="@@status.panel.invalid">
          An in-progress status needs a stage, a next step, and an expected date. A concluded status needs a closing message.
        </p>

        <div class="status-link toolbar" *ngIf="statusLink()">
          <span class="link-value">{{ statusLink() }}</span>
          <button type="button" class="btn btn--ghost btn--sm" (click)="copyStatusLink()" i18n="@@status.panel.copy">Copy status link</button>
          <button type="button" class="btn btn--outline btn--sm" [disabled]="busy()" (click)="rotateStatusLink()"
                  i18n="@@status.panel.rotate">Rotate link</button>
        </div>
        <button type="button" class="btn btn--ghost btn--sm" *ngIf="!statusLink()" (click)="loadStatus()"
                i18n="@@status.panel.load">Load status</button>
      </div>

      <!-- F31: SLA nudge panel (US2/US3). The candidate's green/amber/red communication-health badge plus the
           queued holding-message draft; the recruiter previews and approves (one consent-gated send) or dismisses.
           Internal screen (Lighthouse/WCAG N/A — F50/F51 precedent). No auto-send. -->
      <div class="sla-nudge-panel" *ngIf="candidateId">
        @if (plan() === 'FREE') {
          <app-upgrade-prompt featureLabel="SLA nudges" i18n-featureLabel="@@upgrade.sla" />
        }
        <h2 i18n="@@sla.panel.title">Communication SLA</h2>
        <button type="button" class="btn btn--ghost btn--sm" (click)="loadSla()" i18n="@@sla.panel.load">Check SLA status</button>
        <ng-container *ngIf="sla() as s">
          <span class="sla-badge badge" [class.green]="s.slaState === 'GREEN'" [class.amber]="s.slaState === 'AMBER'"
                [class.red]="s.slaState === 'RED'"
                [class.badge--ok]="s.slaState === 'GREEN'" [class.badge--warn]="s.slaState === 'AMBER'"
                [class.badge--danger]="s.slaState === 'RED'">{{ slaLabel(s.slaState) }}</span>

          <div class="sla-draft" *ngIf="s.openDraftId">
            <p i18n="@@sla.panel.draftPending">A holding message is queued for your approval.</p>
            <button type="button" class="btn btn--ghost btn--sm" [disabled]="slaBusy()" (click)="previewDraft()"
                    i18n="@@sla.panel.preview">Preview draft</button>
            <div class="sla-preview card" *ngIf="draftPreview() as p">
              <p class="subject">{{ p.subject }}</p>
              <p class="body" style="white-space: pre-wrap">{{ p.body }}</p>
              <p class="warn alert alert--warn" *ngIf="p.missingFields.length" role="status" i18n="@@sla.panel.missing">
                Some details are missing and shown as placeholders — fill in the candidate's status first.
              </p>
            </div>
            <button type="button" class="sla-approve btn btn--primary" [disabled]="slaBusy()" (click)="approveDraft(s.openDraftId)"
                    i18n="@@sla.panel.approve">Approve and send</button>
            <button type="button" class="sla-dismiss btn btn--danger-soft" [disabled]="slaBusy()" (click)="dismissDraft(s.openDraftId)"
                    i18n="@@sla.panel.dismiss">Dismiss</button>
          </div>
        </ng-container>
      </div>
    </section>
  `
})
export class SchedulingComponent implements OnInit {
  private readonly api = inject(SchedulingService);
  private readonly slaApi = inject(SlaNudgeService);
  private readonly confirm = inject(ConfirmDialogService);
  private readonly toast = inject(ToastService);
  private readonly pipelineApi = inject(PipelineService);
  private readonly templatesApi = inject(InterviewTemplatesService);
  private readonly billing = inject(BillingService);

  // F31 SLA nudge panel state.
  readonly sla = signal<CandidateSla | null>(null);
  readonly draftPreview = signal<DraftPreview | null>(null);
  readonly slaBusy = signal(false);

  /** 032: null until the entitlement load resolves; a load failure leaves it null (never blocks the screen). */
  readonly plan = signal<'FREE' | 'TEAM' | null>(null);

  // Workbench overhaul phase 5: picker options for the candidate + interview-template combobox fields.
  readonly candidateOpts = signal<readonly PickerOption[]>([]);
  readonly templateOpts = signal<readonly PickerOption[]>([]);

  candidateId = '';
  templateId = '';
  locationText = '';
  rangeStart = '';
  rangeEnd = '';

  readonly busy = signal(false);
  readonly result = signal<InitiateResponse | null>(null);
  readonly statusView = signal<StatusResponse | null>(null);

  // F30 candidate-status panel state.
  statusOutcome: StatusOutcome = 'IN_PROGRESS';
  statusStage = '';
  statusNextStep = '';
  statusExpectedDate = '';
  readonly statusLink = signal<string | null>(null);
  readonly statusTouched = signal(false);

  ngOnInit(): void {
    this.pipelineApi.list({ status: 'ACTIVE', size: 1000 }).subscribe({
      next: (p) => this.candidateOpts.set(p.rows.map((r) => ({ id: r.candidateId, label: r.name, hint: r.stage }))),
      error: () => this.candidateOpts.set([])
    });
    this.templatesApi.list().subscribe({
      next: (l) => this.templateOpts.set(l.templates.map((t) => ({ id: t.id, label: t.name }))),
      error: () => this.templateOpts.set([])
    });
    this.billing.getEntitlement().subscribe({
      next: (e) => this.plan.set(e.plan),
      error: () => this.plan.set(null)
    });
  }

  send(): void {
    this.busy.set(true);
    this.result.set(null);
    this.api.initiate(this.candidateId, {
      templateId: this.templateId,
      locationText: this.locationText || null,
      rangeStart: this.rangeStart || null,
      rangeEnd: this.rangeEnd || null
    }).subscribe({
      next: (r) => {
        this.result.set(r);
        this.toast.success($localize`:@@toast.scheduling.sent:Scheduling link sent.`);
        this.busy.set(false);
        this.refreshStatus();
      },
      error: (e: HttpErrorResponse) => { this.toast.error(this.messageFor(e)); this.busy.set(false); }
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
    this.api.reschedule(this.candidateId).subscribe({
      next: () => {
        this.toast.success($localize`:@@toast.scheduling.rescheduled:Reschedule invitation sent — the booking stays until the candidate picks a new time.`);
        this.busy.set(false);
        this.refreshStatus();
      },
      error: (e: HttpErrorResponse) => { this.toast.error(this.manageError(e)); this.busy.set(false); }
    });
  }

  async cancel(): Promise<void> {
    const ok = await this.confirm.confirm({
      title: $localize`:@@confirm.scheduling.cancel.title:Cancel this interview?`,
      body: $localize`:@@confirm.scheduling.cancel.body:The candidate will be notified that their interview is cancelled.`,
      confirmLabel: $localize`:@@confirm.scheduling.cancel.cta:Cancel interview`,
      danger: true
    });
    if (!ok) { return; }
    this.busy.set(true);
    this.api.cancel(this.candidateId).subscribe({
      next: () => {
        this.toast.success($localize`:@@toast.scheduling.cancelled:Interview cancelled — the candidate has been notified.`);
        this.busy.set(false);
        this.refreshStatus();
      },
      error: (e: HttpErrorResponse) => { this.toast.error(this.manageError(e)); this.busy.set(false); }
    });
  }

  /** F23 US2: one-tap release of an unconfirmed slot — frees the slot and notifies the candidate. */
  async release(): Promise<void> {
    const ok = await this.confirm.confirm({
      title: $localize`:@@confirm.scheduling.release.title:Release this slot?`,
      body: $localize`:@@confirm.scheduling.release.body:The booked slot will be released, calendar events removed, and the candidate notified.`,
      confirmLabel: $localize`:@@confirm.scheduling.release.cta:Release slot`,
      danger: true
    });
    if (!ok) { return; }
    this.busy.set(true);
    this.api.release(this.candidateId).subscribe({
      next: (r) => {
        this.toast.success(r.cleanupIncomplete
          ? $localize`:@@toast.scheduling.released.cleanup:Slot released — one calendar event could not be removed and has been flagged for follow-up.`
          : $localize`:@@toast.scheduling.released:Slot released — the events were removed and the candidate has been notified.`);
        this.busy.set(false);
        this.refreshStatus();
      },
      error: (e: HttpErrorResponse) => { this.toast.error(this.manageError(e)); this.busy.set(false); }
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
    const req: PublishStatusRequest = {
      outcome: this.statusOutcome,
      stage: this.statusOutcome === 'IN_PROGRESS' ? this.statusStage.trim() : null,
      nextStep: this.statusNextStep.trim(),
      expectedDate: this.statusOutcome === 'IN_PROGRESS' ? (this.statusExpectedDate || null) : null
    };
    this.api.publishStatus(this.candidateId, req).subscribe({
      next: (r: RecruiterStatusResponse) => {
        this.applyStatus(r);
        this.toast.success($localize`:@@toast.status.published:Status published — the candidate can now see it.`);
        this.busy.set(false);
      },
      error: (e: HttpErrorResponse) => { this.toast.error(this.statusError(e)); this.busy.set(false); }
    });
  }

  loadStatus(): void {
    if (!this.candidateId) { return; }
    this.api.readStatus(this.candidateId).subscribe({
      next: (r: RecruiterStatusResponse) => this.applyStatus(r),
      error: (e: HttpErrorResponse) => this.toast.error(this.statusError(e))
    });
  }

  async rotateStatusLink(): Promise<void> {
    if (!this.candidateId) { return; }
    const ok = await this.confirm.confirm({
      title: $localize`:@@confirm.status.rotateLink.title:Rotate the status link?`,
      body: $localize`:@@confirm.status.rotateLink.body:The current status link will stop working immediately.`,
      confirmLabel: $localize`:@@confirm.status.rotateLink.cta:Rotate link`
    });
    if (!ok) { return; }
    this.busy.set(true);
    this.api.rotateStatusLink(this.candidateId).subscribe({
      next: (r) => {
        this.statusLink.set(r.statusLink);
        this.toast.success($localize`:@@toast.status.rotated:Link rotated — the previous link no longer works.`);
        this.busy.set(false);
      },
      error: (e: HttpErrorResponse) => { this.toast.error(this.statusError(e)); this.busy.set(false); }
    });
  }

  copyStatusLink(): void {
    const link = this.statusLink();
    if (!link) { return; }
    // Best-effort clipboard copy; never throws into the UI.
    navigator.clipboard?.writeText(link).then(
      () => this.toast.success($localize`:@@toast.status.copied:Status link copied to the clipboard.`),
      () => this.toast.error($localize`:@@toast.status.copyFailed:Could not copy — please copy the link manually.`)
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
    this.draftPreview.set(null);
    this.slaApi.getSla(this.candidateId).subscribe({
      next: (s) => this.sla.set(s),
      error: (e: HttpErrorResponse) => { this.sla.set(null); this.toast.error(this.slaError(e)); }
    });
  }

  previewDraft(): void {
    if (!this.candidateId) { return; }
    this.slaBusy.set(true);
    this.slaApi.previewDraft(this.candidateId).subscribe({
      next: (p) => { this.draftPreview.set(p); this.slaBusy.set(false); },
      error: (e: HttpErrorResponse) => { this.toast.error(this.slaError(e)); this.slaBusy.set(false); }
    });
  }

  approveDraft(draftId: string): void {
    this.slaBusy.set(true);
    this.slaApi.approve(draftId).subscribe({
      next: () => {
        this.slaBusy.set(false);
        this.draftPreview.set(null);
        this.loadSla();
        this.toast.success($localize`:@@toast.sla.approved:Holding message sent — the candidate is no longer in silence.`);
      },
      error: (e: HttpErrorResponse) => { this.toast.error(this.slaError(e)); this.slaBusy.set(false); }
    });
  }

  async dismissDraft(draftId: string): Promise<void> {
    const ok = await this.confirm.confirm({
      title: $localize`:@@confirm.sla.dismiss.title:Dismiss this draft?`,
      body: $localize`:@@confirm.sla.dismiss.body:The queued holding message will not be sent.`,
      confirmLabel: $localize`:@@confirm.sla.dismiss.cta:Dismiss`
    });
    if (!ok) { return; }
    this.slaBusy.set(true);
    this.slaApi.dismiss(draftId).subscribe({
      next: () => {
        this.slaBusy.set(false);
        this.draftPreview.set(null);
        this.loadSla();
        this.toast.success($localize`:@@toast.sla.dismissed:Draft dismissed — nothing was sent.`);
      },
      error: (e: HttpErrorResponse) => { this.toast.error(this.slaError(e)); this.slaBusy.set(false); }
    });
  }

  private slaError(e: HttpErrorResponse): string {
    if (e.status === 404) { return $localize`:@@sla.panel.err.notFound:No SLA information for this candidate.`; }
    if (e.status === 403) { return $localize`:@@sla.panel.err.forbidden:You do not have permission to view this.`; }
    return $localize`:@@sla.panel.err.generic:Could not complete that action — please try again.`;
  }
}
