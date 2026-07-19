import { Component, OnInit, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { EmailTemplate, EmailTemplatesService, RenderedMessage } from './email-templates.service';
import { PageHeaderComponent } from '../../shared/ui/page-header.component';
import { EmptyStateComponent } from '../../shared/ui/empty-state.component';
import { SkeletonComponent } from '../../shared/ui/skeleton.component';
import { ConfirmDialogService } from '../../shared/ui/confirm-dialog.service';
import { ToastService } from '../../shared/ui/toast.service';
import { PickerOption, SearchPickerComponent } from '../../shared/ui/search-picker.component';
import { PipelineService } from '../pipeline/pipeline.service';

/**
 * Admin/Recruiter "Email templates" surface (F21, the §II demonstrable leg): list the message types,
 * edit subject/body, apply a tone preset, lock/unlock (Admin), reset to default, and preview a rendered
 * message with sample merge values. A LOCKED template disables the edit controls for a Recruiter (the
 * server is the real boundary — 403). All strings via $localize. Sending is F22 (not here).
 *
 * Phase 3b (workbench overhaul): `reset` and `send` are gated behind the shared `ConfirmDialogService`
 * (⚠ danger); `setLock` is gated only when locking (unlocking proceeds immediately). Outcomes for
 * save/applyTone/reset/setLock/send are surfaced via `ToastService`; the old dedicated `sendStatus`/
 * `sendError` signals (and their markup) are removed in favour of toasts.
 */
@Component({
  selector: 'app-email-templates',
  standalone: true,
  imports: [FormsModule, PageHeaderComponent, EmptyStateComponent, SkeletonComponent, SearchPickerComponent],
  template: `
    <app-page-header
      eyebrow="Templates" i18n-eyebrow="@@et.eyebrow"
      heading="Email templates" i18n-heading="@@et.title"
      subtitle="Candidate message content and tone." i18n-subtitle="@@et.subtitle">
    </app-page-header>

    @if (error(); as e) {
      <p role="alert" class="error alert alert--danger">{{ e }}</p>
    }

    <section class="list">
      @if (loading()) {
        <app-skeleton variant="lines" />
      } @else if (templates().length === 0) {
        <app-empty-state
          heading="No templates yet" i18n-heading="@@et.empty.heading"
          body="Templates initialize automatically with your workspace." i18n-body="@@et.empty.body">
        </app-empty-state>
      } @else {
        <ul>
          @for (t of templates(); track t.messageType) {
            <li class="row">
              <span class="type">{{ t.messageType }}</span>
              <span class="source">{{ t.source }}</span>
              @if (t.locked) { <span class="locked badge badge--danger" i18n="@@et.locked">Locked</span> }
              <button type="button" class="btn btn--outline btn--sm" (click)="edit(t)" [disabled]="!canEdit(t)" i18n="@@et.edit">Edit</button>
              <button type="button" class="btn btn--ghost btn--sm" (click)="preview(t)" i18n="@@et.preview">Preview</button>
              @if (isAdmin) {
                @if (t.locked) {
                  <button type="button" class="btn btn--ghost btn--sm" (click)="setLock(t, false)" i18n="@@et.unlock">Unlock</button>
                } @else {
                  <button type="button" class="btn btn--ghost btn--sm" (click)="setLock(t, true)" i18n="@@et.lockbtn">Lock</button>
                }
              }
            </li>
          }
        </ul>
      }
    </section>

    @if (editing(); as t) {
      <section class="form">
        <h2 i18n="@@et.editing">Editing {{ t.messageType }}</h2>
        <form (ngSubmit)="save()">
          <label class="field" i18n="@@et.subject">Subject <input class="input" name="subject" [(ngModel)]="subject" required /></label>
          <label class="field" i18n="@@et.body">Body <textarea class="input" name="body" [(ngModel)]="body" required></textarea></label>
          <p class="tokens" i18n="@@et.tokens">Available tokens: {{ t.permittedTokens.join(', ') }}</p>
          <div class="actions">
            <button type="submit" class="btn btn--primary" [disabled]="saving()" i18n="@@et.save">Save</button>
            <button type="button" class="btn btn--ghost" (click)="applyTone(t, 'FORMAL')" i18n="@@et.tone">Apply formal tone</button>
            <button type="button" class="btn btn--ghost" (click)="reset(t)" i18n="@@et.reset">Reset to default</button>
            <button type="button" class="btn btn--link" (click)="cancel()" i18n="@@et.cancel">Cancel</button>
          </div>
        </form>
      </section>
    }

    @if (rendered(); as r) {
      <section class="preview">
        <h2 i18n="@@et.previewTitle">Preview</h2>
        <p class="psubject"><strong i18n="@@et.psubject">Subject:</strong> {{ r.subject }}</p>
        <pre class="pbody">{{ r.bodyText }}</pre>
        @if (r.missingFields.length > 0) {
          <p role="alert" class="warning alert alert--warn" i18n="@@et.missing">
            Some fields had no value: {{ r.missingFields.join(', ') }}
          </p>
        }

        @if (previewing(); as p) {
          <div class="send">
            <h3 i18n="@@et.sendTitle">Send to candidate</h3>
            <div class="field">
              <app-search-picker [options]="candidateOpts()" [value]="sendCandidateId"
                (valueChange)="sendCandidateId = $event ?? ''"
                label="Candidate" i18n-label="@@et.candidate.picker.label"
                placeholder="Search candidates…" i18n-placeholder="@@et.candidate.picker.placeholder">
              </app-search-picker>
            </div>
            <button type="button" class="btn btn--primary" (click)="send(p)" [disabled]="sending() || !sendCandidateId.trim()"
                    i18n="@@et.sendbtn">Send to candidate</button>
          </div>
        }
      </section>
    }
  `,
  styles: [
    `.send { margin-top: var(--space-4); border-top: 1px solid var(--line); padding-top: var(--space-3); }
     .row { display: flex; flex-wrap: wrap; gap: var(--space-3); align-items: center; }
     .actions { display: flex; flex-wrap: wrap; gap: var(--space-2); margin-top: var(--space-2); }
     .tokens { font-size: 0.85rem; color: var(--ink-faint); }`
  ]
})
export class EmailTemplatesComponent implements OnInit {
  private readonly service = inject(EmailTemplatesService);
  private readonly confirm = inject(ConfirmDialogService);
  private readonly toast = inject(ToastService);
  private readonly pipelineApi = inject(PipelineService);

  /** Set by the host/shell; defaults true so an Admin sees lock controls. The server is the boundary. */
  isAdmin = true;

  readonly templates = signal<EmailTemplate[]>([]);
  readonly loading = signal(true);
  readonly editing = signal<EmailTemplate | null>(null);
  readonly rendered = signal<RenderedMessage | null>(null);
  /** The template the current preview belongs to — the "Send to candidate" action targets it. */
  readonly previewing = signal<EmailTemplate | null>(null);
  readonly saving = signal(false);
  readonly error = signal<string | null>(null);
  readonly sending = signal(false);

  // Workbench overhaul phase 5: picker options for the "Send to candidate" candidate combobox field.
  readonly candidateOpts = signal<readonly PickerOption[]>([]);

  subject = '';
  body = '';
  sendCandidateId = '';

  ngOnInit(): void {
    this.load();
    this.pipelineApi.list({ status: 'ACTIVE', size: 1000 }).subscribe({
      next: (p) => this.candidateOpts.set(p.rows.map((r) => ({ id: r.candidateId, label: r.name, hint: r.stage }))),
      error: () => this.candidateOpts.set([])
    });
  }

  private load(): void {
    this.service.list('BASE').subscribe({
      next: (l) => { this.templates.set(l.templates); this.loading.set(false); },
      error: () => { this.error.set($localize`:@@et.loadErr:Could not load templates.`); this.loading.set(false); }
    });
  }

  canEdit(t: EmailTemplate): boolean {
    return this.isAdmin || !t.locked;
  }

  edit(t: EmailTemplate): void {
    this.error.set(null);
    this.editing.set(t);
    this.subject = t.subject;
    this.body = t.body;
  }

  cancel(): void {
    this.editing.set(null);
  }

  save(): void {
    const t = this.editing();
    if (!t) return;
    this.saving.set(true);
    this.error.set(null);
    this.service.edit(t.messageType, { stageKey: t.stageKey, subject: this.subject, body: this.body, expectedVersion: t.version })
      .subscribe({
        next: () => {
          this.saving.set(false);
          this.editing.set(null);
          this.load();
          this.toast.success($localize`:@@toast.et.saved:Template saved.`);
        },
        error: () => {
          this.saving.set(false);
          this.toast.error($localize`:@@toast.et.saveErr:Could not save the template.`);
        }
      });
  }

  applyTone(t: EmailTemplate, tone: string): void {
    this.service.applyTone(t.messageType, { stageKey: t.stageKey, tone, expectedVersion: t.version }).subscribe({
      next: (u) => {
        this.subject = u.subject;
        this.body = u.body;
        this.editing.set(u);
        this.load();
        this.toast.success($localize`:@@toast.et.toneApplied:Tone applied.`);
      },
      error: () => this.toast.error($localize`:@@toast.et.toneErr:Could not apply the tone preset.`)
    });
  }

  async reset(t: EmailTemplate): Promise<void> {
    const ok = await this.confirm.confirm({
      title: $localize`:@@confirm.et.reset.title:Reset to default?`,
      body: $localize`:@@confirm.et.reset.body:Your customized subject and body will be discarded.`,
      confirmLabel: $localize`:@@confirm.et.reset.cta:Reset to default`,
      danger: true
    });
    if (!ok) { return; }
    this.service.reset(t.messageType, { stageKey: t.stageKey, expectedVersion: t.version }).subscribe({
      next: () => {
        this.editing.set(null);
        this.load();
        this.toast.success($localize`:@@toast.et.reset:Template reset to default.`);
      },
      error: () => this.toast.error($localize`:@@toast.et.resetErr:Could not reset the template.`)
    });
  }

  /** Confirm-gated only when LOCKING (the consequential direction); unlocking proceeds immediately. */
  async setLock(t: EmailTemplate, lock: boolean): Promise<void> {
    if (lock) {
      const ok = await this.confirm.confirm({
        title: $localize`:@@confirm.et.lock.title:Lock this template?`,
        body: $localize`:@@confirm.et.lock.body:Recruiters will no longer be able to edit it.`,
        confirmLabel: $localize`:@@confirm.et.lock.cta:Lock`
      });
      if (!ok) { return; }
    }
    const call = lock
      ? this.service.lock(t.messageType, { stageKey: t.stageKey, expectedVersion: t.version })
      : this.service.unlock(t.messageType, { stageKey: t.stageKey, expectedVersion: t.version });
    call.subscribe({
      next: () => {
        this.load();
        this.toast.success(lock
          ? $localize`:@@toast.et.locked:Template locked.`
          : $localize`:@@toast.et.unlocked:Template unlocked.`);
      },
      error: () => this.toast.error($localize`:@@toast.et.lockErr:Could not change the lock.`)
    });
  }

  preview(t: EmailTemplate): void {
    this.service.preview(t.messageType, { stageKey: t.stageKey, sampleValues: this.sampleValues() }).subscribe({
      next: (r) => { this.rendered.set(r); this.previewing.set(t); },
      error: () => this.error.set($localize`:@@et.previewErr:Could not render the preview.`)
    });
  }

  /**
   * Send the previewed template to a candidate (F22). The server resolves the candidate name and runs the
   * consent gate; a 409 not_contactable shows the value-free reason, a 404 a not-found message. The server
   * is the boundary — this is the recruiter trigger only.
   */
  async send(t: EmailTemplate): Promise<void> {
    const candidateId = this.sendCandidateId.trim();
    if (!candidateId) return;
    const ok = await this.confirm.confirm({
      title: $localize`:@@confirm.et.send.title:Send this email?`,
      body: $localize`:@@confirm.et.send.body:This sends the previewed message to candidate ${candidateId}:candidateId: now.`,
      confirmLabel: $localize`:@@confirm.et.send.cta:Send email`,
      danger: true
    });
    if (!ok) { return; }
    this.sending.set(true);
    this.service.sendToCandidate(candidateId,
      { messageType: t.messageType, stageKey: t.stageKey, sampleValues: this.sampleValues() }).subscribe({
        next: (r) => {
          this.sending.set(false);
          this.toast.success($localize`:@@toast.et.sent:Email ${r.status}:status: for candidate.`);
        },
        error: (e: HttpErrorResponse) => { this.sending.set(false); this.toast.error(this.sendErrorMessage(e)); }
      });
  }

  /** Map a send failure to a value-free, localised message; 409 surfaces the contactability reason. */
  private sendErrorMessage(e: HttpErrorResponse): string {
    if (e.status === 409) {
      const reason = (e.error?.reason as string) ?? '';
      return $localize`:@@et.sendNotContactable:This candidate cannot be contacted (${reason}:reason:).`;
    }
    if (e.status === 404) {
      return $localize`:@@et.sendNotFound:Candidate not found.`;
    }
    return $localize`:@@et.sendErr:Could not send the email.`;
  }

  /** Friendly sample data for every catalogue token, so a preview shows a realistic message. */
  private sampleValues(): Record<string, string> {
    return {
      candidate_name: 'Dana Lee',
      recruiter_name: 'Sam Carter',
      workspace_name: 'Acme',
      stage_name: 'Onsite',
      interview_date: '2026-07-01',
      interview_time: '10:00',
      time_zone: 'Europe/London',
      location: 'Room 4',
      scheduling_link: 'https://cadence.app/s/sample',
      status_link: 'https://cadence.app/p/sample',
      reschedule_link: 'https://cadence.app/r/sample',
      feedback_link: 'https://cadence.app/f/sample',
      expected_date: '2026-07-05'
    };
  }
}
