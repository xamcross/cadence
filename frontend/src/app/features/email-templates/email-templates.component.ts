import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { EmailTemplate, EmailTemplatesService, RenderedMessage } from './email-templates.service';

/**
 * Admin/Recruiter "Email templates" surface (F21, the §II demonstrable leg): list the message types,
 * edit subject/body, apply a tone preset, lock/unlock (Admin), reset to default, and preview a rendered
 * message with sample merge values. A LOCKED template disables the edit controls for a Recruiter (the
 * server is the real boundary — 403). All strings via $localize. Sending is F22 (not here).
 */
@Component({
  selector: 'app-email-templates',
  standalone: true,
  imports: [FormsModule],
  template: `
    <h1 i18n="@@et.title">Email templates</h1>

    @if (error(); as e) {
      <p role="alert" class="error">{{ e }}</p>
    }

    <section class="list">
      @if (templates().length === 0) {
        <p i18n="@@et.empty">No templates.</p>
      }
      <ul>
        @for (t of templates(); track t.messageType) {
          <li class="row">
            <span class="type">{{ t.messageType }}</span>
            <span class="source">{{ t.source }}</span>
            @if (t.locked) { <span class="locked" i18n="@@et.locked">Locked</span> }
            <button type="button" (click)="edit(t)" [disabled]="!canEdit(t)" i18n="@@et.edit">Edit</button>
            <button type="button" (click)="preview(t)" i18n="@@et.preview">Preview</button>
            @if (isAdmin) {
              @if (t.locked) {
                <button type="button" (click)="setLock(t, false)" i18n="@@et.unlock">Unlock</button>
              } @else {
                <button type="button" (click)="setLock(t, true)" i18n="@@et.lockbtn">Lock</button>
              }
            }
          </li>
        }
      </ul>
    </section>

    @if (editing(); as t) {
      <section class="form">
        <h2 i18n="@@et.editing">Editing {{ t.messageType }}</h2>
        <form (ngSubmit)="save()">
          <label i18n="@@et.subject">Subject <input name="subject" [(ngModel)]="subject" required /></label>
          <label i18n="@@et.body">Body <textarea name="body" [(ngModel)]="body" required></textarea></label>
          <p class="tokens" i18n="@@et.tokens">Available tokens: {{ t.permittedTokens.join(', ') }}</p>
          <button type="submit" [disabled]="saving()" i18n="@@et.save">Save</button>
          <button type="button" (click)="applyTone(t, 'FORMAL')" i18n="@@et.tone">Apply formal tone</button>
          <button type="button" (click)="reset(t)" i18n="@@et.reset">Reset to default</button>
          <button type="button" (click)="cancel()" i18n="@@et.cancel">Cancel</button>
        </form>
      </section>
    }

    @if (rendered(); as r) {
      <section class="preview">
        <h2 i18n="@@et.previewTitle">Preview</h2>
        <p class="psubject"><strong i18n="@@et.psubject">Subject:</strong> {{ r.subject }}</p>
        <pre class="pbody">{{ r.bodyText }}</pre>
        @if (r.missingFields.length > 0) {
          <p role="alert" class="warning" i18n="@@et.missing">
            Some fields had no value: {{ r.missingFields.join(', ') }}
          </p>
        }
      </section>
    }
  `,
  styles: [
    `.error { color: #b00020; } .warning { color: #8a6d00; } .locked { color: #b00020; font-weight: 600; }
     .row { display: flex; gap: .75rem; align-items: center; } .tokens { font-size: .85rem; color: #555; }`
  ]
})
export class EmailTemplatesComponent implements OnInit {
  private readonly service = inject(EmailTemplatesService);

  /** Set by the host/shell; defaults true so an Admin sees lock controls. The server is the boundary. */
  isAdmin = true;

  readonly templates = signal<EmailTemplate[]>([]);
  readonly editing = signal<EmailTemplate | null>(null);
  readonly rendered = signal<RenderedMessage | null>(null);
  readonly saving = signal(false);
  readonly error = signal<string | null>(null);

  subject = '';
  body = '';

  ngOnInit(): void {
    this.load();
  }

  private load(): void {
    this.service.list('BASE').subscribe({
      next: (l) => this.templates.set(l.templates),
      error: () => this.error.set($localize`:@@et.loadErr:Could not load templates.`)
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
        next: () => { this.saving.set(false); this.editing.set(null); this.load(); },
        error: () => { this.saving.set(false); this.error.set($localize`:@@et.saveErr:Could not save the template.`); }
      });
  }

  applyTone(t: EmailTemplate, tone: string): void {
    this.service.applyTone(t.messageType, { stageKey: t.stageKey, tone, expectedVersion: t.version }).subscribe({
      next: (u) => { this.subject = u.subject; this.body = u.body; this.editing.set(u); this.load(); },
      error: () => this.error.set($localize`:@@et.toneErr:Could not apply the tone preset.`)
    });
  }

  reset(t: EmailTemplate): void {
    this.service.reset(t.messageType, { stageKey: t.stageKey, expectedVersion: t.version }).subscribe({
      next: () => { this.editing.set(null); this.load(); },
      error: () => this.error.set($localize`:@@et.resetErr:Could not reset the template.`)
    });
  }

  setLock(t: EmailTemplate, lock: boolean): void {
    const call = lock
      ? this.service.lock(t.messageType, { stageKey: t.stageKey, expectedVersion: t.version })
      : this.service.unlock(t.messageType, { stageKey: t.stageKey, expectedVersion: t.version });
    call.subscribe({ next: () => this.load(), error: () => this.error.set($localize`:@@et.lockErr:Could not change the lock.`) });
  }

  preview(t: EmailTemplate): void {
    this.service.preview(t.messageType, { stageKey: t.stageKey, sampleValues: this.sampleValues() }).subscribe({
      next: (r) => this.rendered.set(r),
      error: () => this.error.set($localize`:@@et.previewErr:Could not render the preview.`)
    });
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
