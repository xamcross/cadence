import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { A11yModule } from '@angular/cdk/a11y';
import { RouterLink } from '@angular/router';
import {
  InterviewTemplatePreset,
  InterviewTemplatesService,
  SlotComputationResponse,
  TemplateRequest,
  TemplateResponse
} from './interview-templates.service';
import { EmailTemplatesService } from '../email-templates/email-templates.service';
import { PageHeaderComponent } from '../../shared/ui/page-header.component';
import { EmptyStateComponent } from '../../shared/ui/empty-state.component';
import { SkeletonComponent } from '../../shared/ui/skeleton.component';
import { ConfirmDialogService } from '../../shared/ui/confirm-dialog.service';
import { ToastService } from '../../shared/ui/toast.service';

interface StarterRow {
  type: string;
  checked: boolean;
  status: 'idle' | 'applying' | 'done' | 'failed';
}

interface StarterPrompt {
  templateId: string;
  presetKey: string;
  rows: StarterRow[];
}

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
  imports: [FormsModule, A11yModule, RouterLink, PageHeaderComponent, EmptyStateComponent, SkeletonComponent],
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
          body="Start from a preset below, or build one from scratch with the form." i18n-body="@@tmpl.empty.body">
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

    <section class="presets">
      <h2 i18n="@@tmpl.presets.title">Start from a preset</h2>
      @if (presetsFailed()) {
        <p class="muted">
          <span i18n="@@tmpl.presets.loadErr">Presets could not be loaded.</span>
          <button type="button" class="btn btn--link" (click)="loadPresets()"
            i18n="@@tmpl.presets.retry">Try again</button>
        </p>
      } @else {
        <div class="preset-grid">
          @for (p of presetList(); track p.key) {
            <button type="button" class="card lift-card preset-card" (click)="applyPreset(p)">
              <span class="preset-card__name">{{ presetLabels[p.key]?.name || p.key }}</span>
              <span class="preset-card__desc muted">{{ presetLabels[p.key]?.desc || '' }}</span>
              <span class="preset-card__meta muted" i18n="@@tmpl.presets.meta">{{ p.durationMinutes }} min, max {{ p.dailyCapPerInterviewer }}/day</span>
            </button>
          }
        </div>
      }
    </section>

    <section class="form">
      @if (activePresetKey()) {
        <p class="alert alert--accent preset-banner">
          <span i18n="@@tmpl.form.presetBanner">Preset applied - pick your interviewers, then save.</span>
          <button type="button" class="btn btn--link" (click)="resetForm()"
            i18n="@@tmpl.form.presetClear">Clear</button>
        </p>
      }
      <h2>{{ editingId() ? editTitle : newTitle }}</h2>
      <form (ngSubmit)="submit()">
        <label class="field" i18n="@@tmpl.form.name">Name <input class="input" name="name" [(ngModel)]="name" required /></label>
        <label class="field" i18n="@@tmpl.form.duration">Duration (min) <input class="input" name="duration" type="number" [(ngModel)]="durationMinutes" required /></label>
        <label class="field" i18n="@@tmpl.form.cadence">Slot cadence (min) <input class="input" name="cadence" type="number" [(ngModel)]="slotCadenceMinutes" /></label>
        <label class="field" i18n="@@tmpl.form.bufferBefore">Buffer before (min) <input class="input" name="bb" type="number" [(ngModel)]="bufferBeforeMinutes" /></label>
        <label class="field" i18n="@@tmpl.form.bufferAfter">Buffer after (min) <input class="input" name="ba" type="number" [(ngModel)]="bufferAfterMinutes" /></label>
        <label class="field" i18n="@@tmpl.form.cap">Daily cap per interviewer <input class="input" name="cap" type="number" [(ngModel)]="dailyCapPerInterviewer" required /></label>
        <label class="field" i18n="@@tmpl.form.required">Required member IDs (comma-separated) <input class="input" name="req" [(ngModel)]="requiredCsv" /></label>
        <label class="field" i18n="@@tmpl.form.optional">Optional member IDs (comma-separated)
          <input class="input" name="opt" [(ngModel)]="optionalCsv" /></label>
        @for (pool of pools; track $index) {
          <div class="pool-row">
            <label class="field" i18n="@@tmpl.form.poolMembers">Pool member IDs (comma-separated)
              <input class="input" name="pool-m-{{ $index }}" [(ngModel)]="pool.membersCsv" /></label>
            <label class="field" i18n="@@tmpl.form.poolN">Need any
              <input class="input" name="pool-n-{{ $index }}" type="number" min="1" [(ngModel)]="pool.n" /></label>
            <button type="button" class="btn btn--danger-soft btn--sm" (click)="removePool($index)"
              i18n="@@tmpl.form.poolRemove">Remove pool</button>
          </div>
        }
        <button type="button" class="btn btn--outline btn--sm" (click)="addPool()"
          i18n="@@tmpl.form.poolAdd">Add interviewer pool</button>
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

    @if (starterPrompt(); as sp) {
      <div class="ps-backdrop" (click)="closeStarterPrompt()" (keydown.escape)="closeStarterPrompt()" tabindex="-1">
        <div class="ps-panel" role="dialog" aria-modal="true" aria-labelledby="starter-title"
             aria-describedby="starter-body"
             cdkTrapFocus [cdkTrapFocusAutoCapture]="true" (click)="$event.stopPropagation()">
          <h2 class="ps-title" id="starter-title" i18n="@@tmpl.starter.title">Add starter emails for this stage?</h2>
          <p class="ps-body" id="starter-body" i18n="@@tmpl.starter.body">Pre-written wording for this interview type. Everything stays editable in Email templates.</p>
          <ul class="ps-list">
            @for (row of sp.rows; track row.type) {
              <li class="ps-row">
                <label>
                  <input type="checkbox" [checked]="row.checked" (change)="toggleStarterRow(row.type)"
                         [disabled]="row.status === 'applying' || row.status === 'done'" />
                  {{ starterTypeLabels[row.type] || row.type }}
                </label>
                @if (row.status === 'done') {
                  <span class="badge badge--ok" i18n="@@tmpl.starter.done">Added</span>
                }
                @if (row.status === 'failed') {
                  <button type="button" class="btn btn--outline btn--sm" (click)="applyStarterRow(sp, row.type)"
                    i18n="@@tmpl.starter.retry">Retry</button>
                }
              </li>
            }
          </ul>
          <div class="ps-actions">
            <button type="button" class="btn btn--ghost" (click)="closeStarterPrompt()"
              i18n="@@tmpl.starter.skip">Skip</button>
            <a class="btn btn--link" routerLink="/email-templates" (click)="closeStarterPrompt()"
              i18n="@@tmpl.starter.review">Review in Email templates</a>
            <button type="button" class="btn btn--primary" (click)="applyStarters()"
              i18n="@@tmpl.starter.apply">Add selected</button>
          </div>
        </div>
      </div>
    }
  `,
  styles: [`
    .row { display: flex; flex-wrap: wrap; align-items: center; gap: var(--space-4); padding: var(--space-1) 0; }
    .name { font-weight: 600; min-width: 10rem; }
    form { display: flex; flex-direction: column; gap: var(--space-2); max-width: 28rem; }
    .actions { display: flex; flex-wrap: wrap; gap: var(--space-2); }
    .preset-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(15rem, 1fr)); gap: var(--space-4); }
    .preset-card { display: flex; flex-direction: column; gap: var(--space-2); text-align: left; cursor: pointer; }
    .preset-banner { display: flex; align-items: center; gap: var(--space-2); justify-content: space-between; }
    .ps-backdrop { position: fixed; inset: 0; background: rgb(40 33 24 / 0.45); display: flex;
      align-items: center; justify-content: center; z-index: calc(var(--z-overlay) + 10); }
    .ps-panel { background: var(--surface-raised); border: 1px solid var(--line); border-radius: var(--radius-lg);
      box-shadow: var(--shadow-lg); padding: var(--space-5); width: min(30rem, calc(100% - 2rem)); }
    .ps-list { list-style: none; padding: 0; margin: var(--space-3) 0; display: flex;
      flex-direction: column; gap: var(--space-2); }
    .ps-row { display: flex; align-items: center; justify-content: space-between; gap: var(--space-2); }
    .ps-actions { display: flex; justify-content: flex-end; gap: var(--space-2); flex-wrap: wrap; }
    @media (prefers-reduced-motion: no-preference) {
      .ps-panel { animation: cad-ps-in 0.16s ease; }
      @keyframes cad-ps-in { from { opacity: 0; transform: translateY(0.5rem) scale(0.98); } }
    }
  `]
})
export class InterviewTemplatesComponent implements OnInit {
  private readonly api = inject(InterviewTemplatesService);
  private readonly confirm = inject(ConfirmDialogService);
  private readonly toast = inject(ToastService);
  private readonly emailApi = inject(EmailTemplatesService);

  readonly newTitle = $localize`:@@tmpl.form.newTitle:New template`;
  readonly editTitle = $localize`:@@tmpl.form.editTitle:Edit template`;
  readonly saveNew = $localize`:@@tmpl.form.saveNew:Create template`;
  readonly saveEdit = $localize`:@@tmpl.form.saveEdit:Save changes`;

  readonly templates = signal<TemplateResponse[]>([]);
  readonly loading = signal(true);
  readonly editingId = signal<string | null>(null);
  readonly saving = signal(false);
  readonly slotResult = signal<SlotComputationResponse | null>(null);
  readonly presetList = signal<InterviewTemplatePreset[]>([]);
  readonly presetsFailed = signal(false);
  readonly activePresetKey = signal<string | null>(null);
  readonly starterPrompt = signal<StarterPrompt | null>(null);

  readonly starterTypeLabels: Record<string, string> = {
    INVITATION: $localize`:@@tmpl.starter.type.invitation:Invitation`,
    CONFIRMATION: $localize`:@@tmpl.starter.type.confirmation:Confirmation`,
    REMINDER_24H: $localize`:@@tmpl.starter.type.reminder24h:24-hour reminder`
  };

  readonly presetLabels: Record<string, { name: string; desc: string }> = {
    PHONE_SCREEN: {
      name: $localize`:@@tmpl.preset.phoneScreen.name:Phone screen`,
      desc: $localize`:@@tmpl.preset.phoneScreen.desc:A short introductory call - one interviewer, quick turnaround.`
    },
    HM_INTRO: {
      name: $localize`:@@tmpl.preset.hmIntro.name:Hiring-manager intro`,
      desc: $localize`:@@tmpl.preset.hmIntro.desc:Role and team conversation with the hiring manager.`
    },
    TECH_DEEP_DIVE: {
      name: $localize`:@@tmpl.preset.techDeepDive.name:Technical deep-dive`,
      desc: $localize`:@@tmpl.preset.techDeepDive.desc:Hands-on technical session with screen sharing and an optional shadow.`
    },
    PANEL_LOOP: {
      name: $localize`:@@tmpl.preset.panelLoop.name:Panel / onsite loop`,
      desc: $localize`:@@tmpl.preset.panelLoop.desc:A longer session with a required host plus an interviewer pool.`
    },
    HR_CULTURE: {
      name: $localize`:@@tmpl.preset.hrCulture.name:HR / culture interview`,
      desc: $localize`:@@tmpl.preset.hrCulture.desc:Ways of working, values, and expectations.`
    },
    FINAL_ROUND: {
      name: $localize`:@@tmpl.preset.finalRound.name:Final round`,
      desc: $localize`:@@tmpl.preset.finalRound.desc:Closing conversation with a senior interviewer from a pool.`
    }
  };

  // form model
  name = '';
  durationMinutes: number | null = 45;
  slotCadenceMinutes: number | null = 15;
  bufferBeforeMinutes = 15;
  bufferAfterMinutes = 15;
  dailyCapPerInterviewer: number | null = 2;
  requiredCsv = '';
  optionalCsv = '';
  pools: { membersCsv: string; n: number | null }[] = [];

  ngOnInit(): void {
    this.load();
    this.loadPresets();
  }

  loadPresets(): void {
    this.presetsFailed.set(false);
    this.api.presets().subscribe({
      next: (r) => this.presetList.set(r.presets),
      error: () => this.presetsFailed.set(true)
    });
  }

  applyPreset(p: InterviewTemplatePreset): void {
    this.resetForm();
    this.activePresetKey.set(p.key);
    this.name = this.presetLabels[p.key]?.name ?? p.key;
    this.durationMinutes = p.durationMinutes;
    this.slotCadenceMinutes = p.slotCadenceMinutes;
    this.bufferBeforeMinutes = p.bufferBeforeMinutes;
    this.bufferAfterMinutes = p.bufferAfterMinutes;
    this.dailyCapPerInterviewer = p.dailyCapPerInterviewer;
    this.pools = p.poolN != null ? [{ membersCsv: '', n: p.poolN }] : [];
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
      requiredMemberIds: this.csvToIds(this.requiredCsv),
      optionalMemberIds: this.csvToIds(this.optionalCsv),
      pools: this.pools
        .map((p) => ({ memberIds: this.csvToIds(p.membersCsv), n: Number(p.n) }))
        .filter((p) => p.memberIds.length > 0)
    };
    const id = this.editingId();
    const isEdit = id !== null;
    const call = id ? this.api.update(id, body) : this.api.create(body);
    call.subscribe({
      next: (saved) => {
        const presetKey = this.activePresetKey();
        this.saving.set(false);
        this.resetForm();
        this.load();
        this.toast.success(isEdit
          ? $localize`:@@toast.tmpl.updated:Template saved.`
          : $localize`:@@toast.tmpl.created:Template created.`);
        if (!isEdit && presetKey) {
          const types = this.presetList().find((p) => p.key === presetKey)?.starterEmailTypes ?? [];
          if (types.length > 0) {
            this.starterPrompt.set({
              templateId: saved.id,
              presetKey,
              rows: types.map((t) => ({ type: t, checked: true, status: 'idle' as const }))
            });
          }
        }
      },
      error: () => {
        this.saving.set(false);
        this.toast.error($localize`:@@toast.tmpl.saveErr:The template could not be saved. Please check the fields and try again.`);
      }
    });
  }

  edit(t: TemplateResponse): void {
    this.editingId.set(t.id);
    this.activePresetKey.set(null);
    this.name = t.name;
    this.durationMinutes = t.durationMinutes;
    this.slotCadenceMinutes = t.slotCadenceMinutes;
    this.bufferBeforeMinutes = t.bufferBeforeMinutes;
    this.bufferAfterMinutes = t.bufferAfterMinutes;
    this.dailyCapPerInterviewer = t.dailyCapPerInterviewer;
    this.requiredCsv = t.requiredMemberIds.join(', ');
    this.optionalCsv = (t.optionalMemberIds ?? []).join(', ');
    this.pools = t.pools.map((p) => ({ membersCsv: p.memberIds.join(', '), n: p.n }));
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
    this.activePresetKey.set(null);
    this.name = '';
    this.durationMinutes = 45;
    this.slotCadenceMinutes = 15;
    this.bufferBeforeMinutes = 15;
    this.bufferAfterMinutes = 15;
    this.dailyCapPerInterviewer = 2;
    this.requiredCsv = '';
    this.optionalCsv = '';
    this.pools = [];
  }

  addPool(): void {
    this.pools.push({ membersCsv: '', n: 1 });
  }

  removePool(i: number): void {
    this.pools.splice(i, 1);
  }

  applyStarters(): void {
    const prompt = this.starterPrompt();
    if (!prompt) { return; }
    for (const row of prompt.rows) {
      if (row.checked && row.status !== 'done' && row.status !== 'applying') {
        this.applyStarterRow(prompt, row.type);
      }
    }
  }

  applyStarterRow(prompt: StarterPrompt, type: string): void {
    this.setStarterStatus(type, 'applying');
    this.emailApi.applyPresetStarter(type,
      { stageKey: prompt.templateId, presetKey: prompt.presetKey, expectedVersion: null }).subscribe({
      next: () => this.setStarterStatus(type, 'done'),
      error: () => this.setStarterStatus(type, 'failed')
    });
  }

  toggleStarterRow(type: string): void {
    this.starterPrompt.update((p) => p
      ? { ...p, rows: p.rows.map((r) => r.type === type ? { ...r, checked: !r.checked } : r) }
      : p);
  }

  closeStarterPrompt(): void {
    this.starterPrompt.set(null);
  }

  private setStarterStatus(type: string, status: StarterRow['status']): void {
    this.starterPrompt.update((p) => p
      ? { ...p, rows: p.rows.map((r) => r.type === type ? { ...r, status } : r) }
      : p);
  }

  private csvToIds(csv: string): string[] {
    return csv.split(',').map((s) => s.trim()).filter((s) => s.length > 0);
  }

  private load(): void {
    this.api.list('ACTIVE').subscribe({
      next: (r) => { this.templates.set(r.templates); this.loading.set(false); },
      error: () => this.loading.set(false)
    });
  }
}
