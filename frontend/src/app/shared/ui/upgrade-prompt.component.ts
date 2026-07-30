import { Component, Input, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { AuthService } from '../../core/auth/auth.service';

/**
 * 032 shared paywall prompt (FR-016). Rendered on gated surfaces when the workspace is FREE:
 * Admins get the path to Billing; everyone else gets the contact-your-admin notice. Presentational;
 * the consumer decides WHEN to show it (entitlement lookup stays in the feature component).
 */
@Component({
  selector: 'app-upgrade-prompt',
  standalone: true,
  imports: [CommonModule, RouterLink],
  template: `
    <div class="card upgrade-prompt">
      <h2 i18n="@@upgrade.title">{{ featureLabel }} is part of the Team plan</h2>
      @if (isAdmin()) {
        <p i18n="@@upgrade.admin.body">
          Upgrade your workspace to turn this on. Everything configured here is kept and resumes on upgrade.
        </p>
        <a class="btn btn--primary" data-test="upgrade-link" routerLink="/admin/billing"
           i18n="@@upgrade.admin.cta">View plans</a>
      } @else {
        <p i18n="@@upgrade.member.body">
          This feature needs the Team plan. Ask your workspace admin about upgrading.
        </p>
      }
    </div>
  `,
  styles: [`
    .upgrade-prompt { text-align: center; padding: var(--space-6); }
    .upgrade-prompt h2 { margin-top: 0; font-size: var(--step-1); }
  `]
})
export class UpgradePromptComponent {
  @Input({ required: true }) featureLabel!: string;

  private readonly auth = inject(AuthService);
  private readonly member = toSignal(this.auth.member$, { initialValue: null });

  isAdmin(): boolean {
    return this.member()?.role === 'ADMIN';
  }
}
