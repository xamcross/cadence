import { Component, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';
import { WorkspaceService } from './workspace.service';

/**
 * First-run setup wizard (F03 US1). Admin-only route. Captures name, time zone, working hours, SLA
 * silence window, retention period, and the MANDATORY retention acknowledgment (GDPR gate). On
 * success it refreshes the session (auth.me) so the shell stops routing here, then navigates home.
 */
@Component({
  selector: 'app-workspace-setup-wizard',
  standalone: true,
  imports: [ReactiveFormsModule],
  template: `
    <main class="wizard">
      <h1 i18n="@@workspace.setup.title">Set up your workspace</h1>
      <p i18n="@@workspace.setup.intro">A few details to get your workspace ready.</p>

      @if (generalError()) {
        <p class="error" role="alert">{{ generalError() }}</p>
      }

      <form [formGroup]="form" (ngSubmit)="submit()">
        <label for="name" i18n="@@workspace.setup.name">Workspace name</label>
        <input id="name" type="text" formControlName="name" />
        @if (fieldError('name')) { <span class="field-error" role="alert">{{ fieldError('name') }}</span> }

        <label for="timeZone" i18n="@@workspace.setup.timezone">Time zone (IANA, e.g. Europe/London)</label>
        <input id="timeZone" type="text" formControlName="timeZone" placeholder="Europe/London" />
        @if (fieldError('timeZone')) { <span class="field-error" role="alert">{{ fieldError('timeZone') }}</span> }

        <label for="start" i18n="@@workspace.setup.start">Working hours start</label>
        <input id="start" type="time" formControlName="start" />
        <label for="end" i18n="@@workspace.setup.end">Working hours end</label>
        <input id="end" type="time" formControlName="end" />
        @if (fieldError('workingHours')) { <span class="field-error" role="alert">{{ fieldError('workingHours') }}</span> }

        <label for="sla" i18n="@@workspace.setup.sla">Default SLA silence window (days)</label>
        <input id="sla" type="number" formControlName="slaSilenceWindowDays" min="1" max="30" />
        @if (fieldError('slaSilenceWindowDays')) { <span class="field-error" role="alert">{{ fieldError('slaSilenceWindowDays') }}</span> }

        <label for="retention" i18n="@@workspace.setup.retention">Data-retention period (days)</label>
        <input id="retention" type="number" formControlName="retentionPeriodDays" min="30" max="3650" />
        @if (fieldError('retentionPeriodDays')) { <span class="field-error" role="alert">{{ fieldError('retentionPeriodDays') }}</span> }

        <div class="ack">
          <input id="ack" type="checkbox" formControlName="retentionAcknowledged" aria-describedby="ack-help" />
          <label for="ack" i18n="@@workspace.setup.ack">I acknowledge the data-retention period for this workspace.</label>
        </div>
        <p id="ack-help" class="help" i18n="@@workspace.setup.ackHelp">
          Candidate data older than the retention period will be flagged for deletion.
        </p>

        <button type="submit" [disabled]="submitting()" i18n="@@workspace.setup.finish">Finish setup</button>
      </form>
    </main>
  `,
  styles: [`
    .wizard { max-width: 520px; margin: 2rem auto; padding: 0 1rem; }
    label { display: block; margin-top: 1rem; font-weight: 600; }
    input[type=text], input[type=number], input[type=time] { width: 100%; min-height: 44px; box-sizing: border-box; }
    .ack { display: flex; align-items: center; gap: 0.5rem; margin-top: 1rem; }
    .ack label { margin-top: 0; font-weight: 400; }
    .ack input { min-width: 24px; min-height: 24px; }
    .help { color: var(--ink-faint); font-size: 0.9rem; }
    .field-error, .error { color: var(--danger); display: block; }
    button { min-height: 44px; margin-top: 1.5rem; }
  `]
})
export class WorkspaceSetupWizardComponent {
  private readonly fb = inject(FormBuilder);
  private readonly workspace = inject(WorkspaceService);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  readonly submitting = signal(false);
  readonly generalError = signal<string | null>(null);
  private readonly errors = signal<Record<string, string>>({});

  readonly form = this.fb.nonNullable.group({
    name: ['', Validators.required],
    timeZone: ['', Validators.required],
    start: ['09:00', Validators.required],
    end: ['17:00', Validators.required],
    slaSilenceWindowDays: [5, Validators.required],
    retentionPeriodDays: [365, Validators.required],
    retentionAcknowledged: [false, Validators.requiredTrue]
  });

  fieldError(key: string): string | undefined {
    return this.errors()[key];
  }

  submit(): void {
    this.generalError.set(null);
    this.errors.set({});
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      if (!this.form.controls.retentionAcknowledged.value) {
        this.generalError.set($localize`:@@workspace.setup.ackRequired:You must acknowledge the data-retention period to finish setup.`);
      }
      return;
    }
    const v = this.form.getRawValue();
    this.submitting.set(true);
    this.workspace
      .completeSetup({
        name: v.name,
        timeZone: v.timeZone,
        workingHours: { start: v.start, end: v.end },
        slaSilenceWindowDays: v.slaSilenceWindowDays,
        retentionPeriodDays: v.retentionPeriodDays,
        retentionAcknowledged: v.retentionAcknowledged
      })
      .subscribe({
        next: () => {
          // Refresh the cached member so workspaceConfigured flips, then navigate. The authGuard
          // re-probes me() on navigation, so even if this refresh fails the shell sees fresh state.
          this.auth.me().subscribe({
            next: () => { this.submitting.set(false); this.router.navigate(['/app']); },
            error: () => { this.submitting.set(false); this.router.navigate(['/app']); }
          });
        },
        error: (e: HttpErrorResponse) => {
          this.submitting.set(false);
          if (e.status === 400 && e.error?.fields) {
            this.errors.set(e.error.fields as Record<string, string>);
          } else if (e.status === 400 && e.error?.error === 'retention_not_acknowledged') {
            this.generalError.set($localize`:@@workspace.setup.ackRequired2:You must acknowledge the data-retention period to finish setup.`);
          } else if (e.status === 409) {
            this.generalError.set($localize`:@@workspace.setup.already:This workspace has already been set up.`);
          } else {
            this.generalError.set($localize`:@@workspace.setup.failed:Setup could not be completed. Please try again.`);
          }
        }
      });
  }
}
