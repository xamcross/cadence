import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  InterviewTemplatesService,
  SlotComputationResponse,
  TemplateRequest,
  TemplateResponse
} from './interview-templates.service';
import { PageHeaderComponent } from '../../shared/ui/page-header.component';
import { EmptyStateComponent } from '../../shared/ui/empty-state.component';
import { SkeletonComponent } from '../../shared/ui/skeleton.component';
import { ConfirmDialogService } from '../../shared/ui/confirm-dialog.service';
import { ToastService } from '../../shared/ui/toast.service';

/**
 * Recruiter/Admin "Interview templates" surface (F12, the §II demonstrable leg): list / create / edit /
 * retire a template, and preview the rule engine's computed slots for a date range. The route is guarded
 * to ADMIN/RECRUITER (defense-in-depth); the server is the real boundary. All strings via $localize.
 *
 * Phase 3b (workbench overhaul): `retire` is gated behind the shared `ConfirmDialogService`. Outcomes
 * for `submit` (create/edit), `retire`, and slot `preview` are surfaced via `ToastService`; the old
 * shared `error` signal (action-outcome usage only) is removed.
 */
@Component({
  selector: 'app-interview-templates',
  standalone: true,
  imports: [FormsModule, PageHeaderComponent, EmptyStateComponent, SkeletonComponent],
  template: `
    <app-page-header
      eyebrow="Templates" i18n-eyebrow="@@tmpl.eyebrow"
      heading="Interview templates" i18n-heading="@@tmpl.title"
      subtitle="Panels, durations, and slot rules." i18n-subtitle="@@tmpl.subtitle">
    </app-page-header>

    <section class="list">
      <h2 i18n="@@tmpl.list.title">Active templates</h2>
      @if (loading()) {
        <app-skeleton variant="lines" />
      } @else if (templates().length === 0) {
        <app-empty-state
          heading="No templates yet" i18n-heading="@@tmpl.empty.heading"
          body="Create your first interview template using the form below." i18n-body="@@tmpl.empty.body">
        </app-empty-state>
      } @else {
        <ul>
          @for (t of templates(); track t.id) {
            <li class="row">
              <span class="name">{{ t.name }}</span>
              <span class="meta" i18n="@@tmpl.list.meta">{{ t.durationMinutes }} min, max {{ t.dailyCapPerInterviewer }}/day</span>
              <button type="button" class="btn btn--outline btn--sm" (click)="edit(t)" i18n="@@tmpl.list.edit">Edit</button>
              <button type="button" class="btn btn--danger-soft btn--sm" (click)="retire(t)" i18n="@@tmpl.list.retire">Retire</button>
              <button type="button" class="btn btn--ghost btn--sm" (click)="preview(t)" i18n="@@tmpl.list.preview">Preview slots</button>
            </li>
          }
        </ul>
      }
    </section>

    <section class="form">
      <h2>{{ editingId() ? editTitle : newTitle }}</h2>
      <form (ngSubmit)="submit()">
        <label class="field" i18n="@@tmpl.form.name">Name <input class="input" name="name" [(ngModel)]="name" required /></label>
        <label class="field" i18n="@@tmpl.form.duration">Duration (min) <input class="input" name="duration" type="number" [(ngModel)]="durationMinutes" required /></label>
        <label class="field" i18n="@@tmpl.form.cadence">Slot cadence (min) <input class="input" name="cadence" type="number" [(ngModel)]="slotCadenceMinutes" /></label>
        <label class="field" i18n="@@tmpl.form.bufferBefore">Buffer before (min) <input class="input" name="bb" type="number" [(ngModel)]="bufferBeforeMinutes" /></label>
        <label class="field" i18n="@@tmpl.form.bufferAfter">Buffer after (min) <input class="input" name="ba" type="number" [(ngModel)]="bufferAfterMinutes" /></label>
        <label class="field" i18n="@@tmpl.form.cap">Daily cap per interviewer <input class="input" name="cap" type="number" [(ngModel)]="dailyCapPerInterviewer" required /></label>
        <label class="field" i18n="@@tmpl.form.required">Required member IDs (comma-separated) <input class="input" name="req" [(ngModel)]="requiredCsv" /></label>
        <div class="actions">
          <button type="submit" class="btn btn--primary" [disabled]="saving()">{{ editingId() ? saveEdit : saveNew }}</button>
          @if (editingId()) {
            <button type="button" class="btn btn--link" (click)="resetForm()" i18n="@@tmpl.form.cancel">Cancel</button>
          }
        </div>
      </form>
    </section>

    @if (slotResult(); as sr) {
      <section class="preview">
        <h2 i18n="@@tmpl.preview.title">Computed slots</h2>
        @if (sr.slots.length === 0) {
          <p i18n="@@tmpl.preview.none">No compliant slots in this range.</p>
        } @else {
          <ul class="slots">
            @for (s of sr.slots; track s.start) {
              <li>{{ s.start }} &ndash; {{ s.end }} ({{ s.zoneId }})</li>
            }
          </ul>
        }
        @if (sr.unschedulable.length > 0) {
          <h3 i18n="@@tmpl.preview.unschedulable">Members who could not be scheduled</h3>
          <ul class="unschedulable">
            @for (u of sr.unschedulable; track u.memberId) {
              <li>{{ u.memberId }}: {{ u.reason }}</li>
            }
          </ul>
        }
      </section>
    }
  `,
  styles: [`
    .row { display: flex; flex-wrap: wrap; align-items: center; gap: var(--space-4); padding: var(--space-1) 0; }
    .name { font-weight: 600; min-width: 10rem; }
    form { display: flex; flex-direction: column; gap: var(--space-2); max-width: 28rem; }
    .actions { display: flex; flex-wrap: wrap; gap: var(--space-2); }
  `]
})
export class InterviewTemplatesComponent implements OnInit {
  private readonly api = inject(InterviewTemplatesService);
  private readonly confirm = inject(ConfirmDialogService);
  private readonly toast = inject(ToastService);

  readonly newTitle = $localize`:@@tmpl.form.newTitle:New template`;
  readonly editTitle = $localize`:@@tmpl.form.editTitle:Edit template`;
  readonly saveNew = $localize`:@@tmpl.form.saveNew:Create template`;
  readonly saveEdit = $localize`:@@tmpl.form.saveEdit:Save changes`;

  readonly templates = signal<TemplateResponse[]>([]);
  readonly loading = signal(true);
  readonly editingId = signal<string | null>(null);
  readonly saving = signal(false);
  readonly slotResult = signal<SlotComputationResponse | null>(null);

  // form model
  name = '';
  durationMinutes: number | null = 45;
  slotCadenceMinutes: number | null = 15;
  bufferBeforeMinutes = 15;
  bufferAfterMinutes = 15;
  dailyCapPerInterviewer: number | null = 2;
  requiredCsv = '';

  ngOnInit(): void {
    this.load();
  }

  submit(): void {
    this.saving.set(true);
    const body: TemplateRequest = {
      name: this.name,
      durationMinutes: Number(this.durationMinutes),
      slotCadenceMinutes: this.slotCadenceMinutes == null ? null : Number(this.slotCadenceMinutes),
      bufferBeforeMinutes: Number(this.bufferBeforeMinutes),
      bufferAfterMinutes: Number(this.bufferAfterMinutes),
      dailyCapPerInterviewer: Number(this.dailyCapPerInterviewer),
      requiredMemberIds: this.requiredCsv.split(',').map((s) => s.trim()).filter((s) => s.length > 0)
    };
    const id = this.editingId();
    const isEdit = id !== null;
    const call = id ? this.api.update(id, body) : this.api.create(body);
    call.subscribe({
      next: () => {
        this.saving.set(false);
        this.resetForm();
        this.load();
        this.toast.success(isEdit
          ? $localize`:@@toast.tmpl.updated:Template saved.`
          : $localize`:@@toast.tmpl.created:Template created.`);
      },
      error: () => {
        this.saving.set(false);
        this.toast.error($localize`:@@toast.tmpl.saveErr:The template could not be saved. Please check the fields and try again.`);
      }
    });
  }

  edit(t: TemplateResponse): void {
    this.editingId.set(t.id);
    this.name = t.name;
    this.durationMinutes = t.durationMinutes;
    this.slotCadenceMinutes = t.slotCadenceMinutes;
    this.bufferBeforeMinutes = t.bufferBeforeMinutes;
    this.bufferAfterMinutes = t.bufferAfterMinutes;
    this.dailyCapPerInterviewer = t.dailyCapPerInterviewer;
    this.requiredCsv = t.requiredMemberIds.join(', ');
  }

  async retire(t: TemplateResponse): Promise<void> {
    const ok = await this.confirm.confirm({
      title: $localize`:@@confirm.tmpl.retire.title:Retire this template?`,
      body: $localize`:@@confirm.tmpl.retire.body:"${t.name}:name:" will no longer be available for scheduling new interviews.`,
      confirmLabel: $localize`:@@confirm.tmpl.retire.cta:Retire template`
    });
    if (!ok) { return; }
    this.api.retire(t.id).subscribe({
      next: () => {
        this.load();
        this.toast.success($localize`:@@toast.tmpl.retired:Template retired.`);
      },
      error: () => this.toast.error($localize`:@@toast.tmpl.retireErr:The template could not be retired. Please try again.`)
    });
  }

  preview(t: TemplateResponse): void {
    const today = new Date();
    const start = today.toISOString().slice(0, 10);
    const end = new Date(today.getTime() + 13 * 86400000).toISOString().slice(0, 10);
    this.api.computeSlots(t.id, start, end).subscribe({
      next: (r) => this.slotResult.set(r),
      error: () => this.toast.error($localize`:@@toast.tmpl.previewErr:The slots could not be computed. Please try again.`)
    });
  }

  resetForm(): void {
    this.editingId.set(null);
    this.name = '';
    this.durationMinutes = 45;
    this.slotCadenceMinutes = 15;
    this.bufferBeforeMinutes = 15;
    this.bufferAfterMinutes = 15;
    this.dailyCapPerInterviewer = 2;
    this.requiredCsv = '';
  }

  private load(): void {
    this.api.list('ACTIVE').subscribe({
      next: (r) => { this.templates.set(r.templates); this.loading.set(false); },
      error: () => this.loading.set(false)
    });
  }
}
