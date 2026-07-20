import { Component, OnInit, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { WorkspaceConfig, WorkspaceService } from './workspace.service';
import { PageHeaderComponent } from '../../../shared/ui/page-header.component';
import { SkeletonComponent } from '../../../shared/ui/skeleton.component';
import { ConfirmDialogService } from '../../../shared/ui/confirm-dialog.service';
import { ToastService } from '../../../shared/ui/toast.service';

/**
 * Admin workspace settings (F03 US2-US5). Operational settings, branding (colour + logo upload),
 * email domain + provider credential (write-only), and template-lock governance. Admin-only route;
 * the server is the security boundary (this screen is convenience + defense-in-depth).
 */
@Component({
  selector: 'app-workspace-settings',
  standalone: true,
  imports: [ReactiveFormsModule, PageHeaderComponent, SkeletonComponent],
  template: `
    <main class="settings">
      <app-page-header
        eyebrow="Administration" i18n-eyebrow="@@workspace.settings.eyebrow"
        heading="Workspace settings" i18n-heading="@@workspace.settings.title"
        subtitle="Branding, time zone, retention, and SLAs." i18n-subtitle="@@workspace.settings.subtitle">
      </app-page-header>
      @if (config() === null) {
        <app-skeleton variant="form" />
      } @else {

      <section>
        <h2 i18n="@@workspace.settings.operational">Operational</h2>
        <form [formGroup]="ops" (ngSubmit)="saveOps()">
          <div class="field">
            <label for="name" i18n="@@workspace.settings.name">Name</label>
            <input class="input" id="name" type="text" formControlName="name" />
          </div>
          <div class="field">
            <label for="tz" i18n="@@workspace.settings.tz">Time zone</label>
            <input class="input" id="tz" type="text" formControlName="timeZone" />
          </div>
          <div class="field">
            <label for="start" i18n="@@workspace.settings.start">Working hours start</label>
            <input class="input" id="start" type="time" formControlName="start" />
          </div>
          <div class="field">
            <label for="end" i18n="@@workspace.settings.end">Working hours end</label>
            <input class="input" id="end" type="time" formControlName="end" />
          </div>
          <div class="field">
            <label for="sla" i18n="@@workspace.settings.sla">SLA silence window (days)</label>
            <input class="input" id="sla" type="number" formControlName="slaSilenceWindowDays" min="1" max="30" />
          </div>
          <div class="field">
            <label for="ret" i18n="@@workspace.settings.retention">Data-retention period (days)</label>
            <input class="input" id="ret" type="number" formControlName="retentionPeriodDays" min="30" max="3650" />
          </div>
          <button type="submit" class="btn btn--primary" i18n="@@workspace.settings.save">Save</button>
        </form>
      </section>

      <section>
        <h2 i18n="@@workspace.settings.branding">Branding</h2>
        <form [formGroup]="branding" (ngSubmit)="saveBranding()">
          <div class="field">
            <label for="color" i18n="@@workspace.settings.color">Brand colour (#RRGGBB)</label>
            <input class="input" id="color" type="text" formControlName="brandColor" placeholder="#1F2937" />
          </div>
          <button type="submit" class="btn btn--primary" i18n="@@workspace.settings.saveColor">Save colour</button>
        </form>
        <div class="field">
          <label for="logo" i18n="@@workspace.settings.logo">Logo (PNG or JPEG, max 1 MB)</label>
          <input id="logo" type="file" accept="image/png,image/jpeg" (change)="onLogo($event)"
                 aria-describedby="logo-err" />
        </div>
        <span id="logo-err" class="field-error" role="alert">{{ logoError() }}</span>
        <button type="button" class="btn btn--danger-soft" (click)="removeLogo()" i18n="@@workspace.settings.removeLogo">Remove logo</button>
      </section>

      <section>
        <h2 i18n="@@workspace.settings.email">Email sending</h2>
        @if (config()?.credentialSet) {
          <p i18n="@@workspace.settings.credSet">Provider credential: set</p>
        } @else {
          <p i18n="@@workspace.settings.credNotSet">Provider credential: not set</p>
        }
        <form [formGroup]="email" (ngSubmit)="saveEmail()">
          <div class="field">
            <label for="domain" i18n="@@workspace.settings.domain">Sending domain</label>
            <input class="input" id="domain" type="text" formControlName="sendingDomain" placeholder="careers.example.com" />
          </div>
          <div class="field">
            <label for="cred" i18n="@@workspace.settings.cred">Provider credential (write-only)</label>
            <input class="input" id="cred" type="password" formControlName="credential" autocomplete="off" />
          </div>
          <button type="submit" class="btn btn--primary" i18n="@@workspace.settings.saveEmail">Save email config</button>
        </form>
        <button type="button" class="btn btn--danger-soft" (click)="removeCredential()" i18n="@@workspace.settings.removeCred">Remove credential</button>
      </section>

      <section>
        <h2 i18n="@@workspace.settings.templates">Template governance</h2>
        @for (k of templateKeys(); track k) {
          <div class="lock-row">
            <span>{{ k }}</span>
            <button type="button" class="btn btn--ghost btn--sm" (click)="toggleLock(k)">
              {{ config()?.templateLocks?.[k] ? unlockLabel : lockLabel }}
            </button>
          </div>
        }
        <form [formGroup]="lockForm" (ngSubmit)="addLock()">
          <div class="field">
            <label for="tkey" i18n="@@workspace.settings.templateKey">Template key</label>
            <input class="input" id="tkey" type="text" formControlName="key" />
          </div>
          <button type="submit" class="btn btn--primary" i18n="@@workspace.settings.lock">Lock</button>
        </form>
      </section>
      }
    </main>
  `,
  styles: [`
    .settings { max-width: 640px; margin: var(--space-6) auto; padding: 0 var(--space-4); }
    section { margin-top: var(--space-8); border-top: 1px solid var(--line); padding-top: var(--space-4); }
    .lock-row { display: flex; gap: var(--space-4); align-items: center; margin: var(--space-2) 0; }
    .field-error { color: var(--danger); display: block; }
  `]
})
export class WorkspaceSettingsComponent implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly workspace = inject(WorkspaceService);
  private readonly confirm = inject(ConfirmDialogService);
  private readonly toast = inject(ToastService);

  readonly config = signal<WorkspaceConfig | null>(null);
  readonly logoError = signal<string>('');

  readonly lockLabel = $localize`:@@workspace.settings.lockBtn:Lock`;
  readonly unlockLabel = $localize`:@@workspace.settings.unlockBtn:Unlock`;

  readonly ops = this.fb.nonNullable.group({
    name: [''], timeZone: [''], start: ['09:00'], end: ['17:00'],
    slaSilenceWindowDays: [5], retentionPeriodDays: [365]
  });
  readonly branding = this.fb.nonNullable.group({ brandColor: [''] });
  readonly email = this.fb.nonNullable.group({ sendingDomain: [''], credential: [''] });
  readonly lockForm = this.fb.nonNullable.group({ key: ['', Validators.required] });

  ngOnInit(): void {
    this.workspace.getConfig().subscribe({
      next: (c) => this.apply(c),
      error: () => this.toast.error($localize`:@@toast.workspace.loadFailed:Could not load workspace settings. Please try again.`)
    });
  }

  templateKeys(): string[] {
    return Object.keys(this.config()?.templateLocks ?? {});
  }

  saveOps(): void {
    const v = this.ops.getRawValue();
    this.workspace.patchConfig({
      name: v.name, timeZone: v.timeZone,
      workingHours: { start: v.start, end: v.end },
      slaSilenceWindowDays: v.slaSilenceWindowDays, retentionPeriodDays: v.retentionPeriodDays
    }).subscribe({
      next: (c) => { this.config.set(c); this.toast.success($localize`:@@toast.workspace.opsSaved:Operational settings saved.`); },
      error: (e: HttpErrorResponse) => this.toast.error(this.errorMessage(e,
        $localize`:@@toast.workspace.opsSaveFailed:Could not save the operational settings. Please try again.`))
    });
  }

  saveBranding(): void {
    this.workspace.putBranding(this.branding.getRawValue().brandColor).subscribe({
      next: (c) => { this.config.set(c); this.toast.success($localize`:@@toast.workspace.brandingSaved:Branding saved.`); },
      error: (e: HttpErrorResponse) => this.toast.error(this.errorMessage(e,
        $localize`:@@toast.workspace.brandingSaveFailed:Could not save the branding. Please try again.`))
    });
  }

  onLogo(event: Event): void {
    this.logoError.set('');
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    // Clear the input so re-selecting the SAME file after an error fires change again.
    input.value = '';
    if (!file) { return; }
    if (file.size > 1024 * 1024) {
      this.logoError.set($localize`:@@workspace.settings.logoTooBig:Logo must be 1 MB or smaller.`);
      return;
    }
    if (file.type !== 'image/png' && file.type !== 'image/jpeg') {
      this.logoError.set($localize`:@@workspace.settings.logoType:Only PNG or JPEG images are accepted.`);
      return;
    }
    this.workspace.uploadLogo(file).subscribe({
      next: () => { this.toast.success($localize`:@@toast.workspace.logoSaved:Logo updated.`); this.reload(); },
      error: () => this.logoError.set($localize`:@@workspace.settings.logoRejected:The logo was rejected.`)
    });
  }

  async removeLogo(): Promise<void> {
    const ok = await this.confirm.confirm({
      title: $localize`:@@confirm.workspace.removeLogo.title:Remove the logo?`,
      body: $localize`:@@confirm.workspace.removeLogo.body:You can upload a new one anytime.`,
      confirmLabel: $localize`:@@confirm.workspace.removeLogo.cta:Remove logo`
    });
    if (!ok) { return; }
    this.workspace.deleteLogo().subscribe({
      next: () => { this.toast.success($localize`:@@toast.workspace.logoRemoved:Logo removed.`); this.reload(); },
      error: () => this.toast.error($localize`:@@toast.workspace.logoRemoveFailed:Could not remove the logo. Please try again.`)
    });
  }

  saveEmail(): void {
    const v = this.email.getRawValue();
    this.workspace.putEmail(v.sendingDomain, v.credential).subscribe({
      next: (c) => {
        this.email.controls.credential.reset('');
        this.config.set(c);
        this.toast.success($localize`:@@toast.workspace.emailSaved:Email settings saved.`);
      },
      error: (e: HttpErrorResponse) => this.toast.error(this.errorMessage(e,
        $localize`:@@toast.workspace.emailSaveFailed:Could not save the email settings. Please try again.`))
    });
  }

  async removeCredential(): Promise<void> {
    const ok = await this.confirm.confirm({
      title: $localize`:@@confirm.workspace.removeCred.title:Remove the email credential?`,
      body: $localize`:@@confirm.workspace.removeCred.body:Cadence will stop sending candidate emails until a new credential is added.`,
      confirmLabel: $localize`:@@confirm.workspace.removeCred.cta:Remove credential`,
      danger: true
    });
    if (!ok) { return; }
    this.workspace.deleteCredential().subscribe({
      next: () => { this.toast.success($localize`:@@toast.workspace.credRemoved:Credential removed.`); this.reload(); },
      error: () => this.toast.error($localize`:@@toast.workspace.credRemoveFailed:Could not remove the credential. Please try again.`)
    });
  }

  /** Confirm-gated only when LOCKING (the consequential direction); unlocking proceeds immediately. */
  async toggleLock(key: string): Promise<void> {
    const locked = !this.config()?.templateLocks?.[key];
    if (locked) {
      const ok = await this.confirm.confirm({
        title: $localize`:@@confirm.workspace.lockTemplate.title:Lock this template?`,
        body: $localize`:@@confirm.workspace.lockTemplate.body:Recruiters will no longer be able to edit it.`,
        confirmLabel: $localize`:@@confirm.workspace.lockTemplate.cta:Lock`
      });
      if (!ok) { return; }
    }
    this.workspace.putTemplateLock(key, locked).subscribe({
      next: (c) => {
        this.config.set(c);
        this.toast.success(locked
          ? $localize`:@@toast.workspace.templateLocked:Template locked.`
          : $localize`:@@toast.workspace.templateUnlocked:Template unlocked.`);
      },
      error: () => this.toast.error($localize`:@@toast.workspace.lockFailed:Could not update the template lock. Please try again.`)
    });
  }

  addLock(): void {
    const key = this.lockForm.getRawValue().key;
    if (!key) { return; }
    this.workspace.putTemplateLock(key, true).subscribe({
      next: (c) => {
        this.lockForm.reset({ key: '' });
        this.config.set(c);
        this.toast.success($localize`:@@toast.workspace.lockAdded:Template lock added.`);
      },
      error: () => this.toast.error($localize`:@@toast.workspace.lockAddFailed:Could not add the template lock. Please try again.`)
    });
  }

  private reload(): void {
    this.workspace.getConfig().subscribe({ next: (c) => this.config.set(c) });
  }

  private apply(c: WorkspaceConfig): void {
    this.config.set(c);
    this.ops.patchValue({
      name: c.name ?? '', timeZone: c.timeZone ?? '',
      start: c.workingHours?.start ?? '09:00', end: c.workingHours?.end ?? '17:00',
      slaSilenceWindowDays: c.slaSilenceWindowDays ?? 5, retentionPeriodDays: c.retentionPeriodDays ?? 365
    });
    this.branding.patchValue({ brandColor: c.brandColor ?? '' });
    this.email.patchValue({ sendingDomain: c.emailSendingDomain ?? '' });
  }

  private errorMessage(e: HttpErrorResponse | undefined, generic: string): string {
    if (e?.status === 400) {
      return $localize`:@@toast.workspace.invalid:One or more values are invalid.`;
    }
    return generic;
  }
}
