import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { BillingService, EntitlementView } from './billing.service';
import { ToastService } from '../../../shared/ui/toast.service';

/**
 * 032 Billing page (US1/US4). Admin-only internal screen (roleGuard; the F50/F51 internal-screen
 * precedent). Upgrade redirects to the Freemius HOSTED checkout -- no third-party script ever loads
 * (FR-005/SC-005). Returning from checkout lands here with ?license_id=..., which is claimed
 * server-side; the recovery field feeds the same claim for buyers who closed the tab (FR-007).
 * Payment methods / invoices / cancellation live in the Freemius customer portal (FR-015).
 */
@Component({
  selector: 'app-billing',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div class="page">
      <header class="page__head">
        <h1 i18n="@@billing.title">Billing &amp; plan</h1>
      </header>

      <p class="error alert alert--danger" role="alert" *ngIf="error()">{{ error() }}</p>

      <section class="card" *ngIf="entitlement() as e">
        <ng-container *ngIf="e.plan === 'FREE'; else team">
          <h2 i18n="@@billing.free.title">Free plan</h2>
          <p i18n="@@billing.free.body">
            Core scheduling, calendar sync, candidate links, dashboards, and GDPR tooling are always
            free. The Team plan adds ATS integrations, no-show defense, and SLA nudges.
          </p>
          <button class="btn btn--primary" data-test="upgrade" (click)="upgrade()" [disabled]="busy()"
                  i18n="@@billing.upgrade">Upgrade to Team</button>

          <h3 i18n="@@billing.recover.title">Already purchased?</h3>
          <p i18n="@@billing.recover.body">
            Paste the license ID from your Freemius receipt email to finish linking your purchase.
          </p>
          <div class="field">
            <label for="license" i18n="@@billing.recover.label">License ID</label>
            <input id="license" name="license" [(ngModel)]="recoveryLicenseId" />
          </div>
          <button class="btn btn--outline" data-test="recover" (click)="claim(recoveryLicenseId)"
                  [disabled]="busy() || !recoveryLicenseId" i18n="@@billing.recover.submit">Link license</button>
        </ng-container>

        <ng-template #team>
          <h2 i18n="@@billing.team.title">Team plan</h2>
          <p>
            <span class="badge badge--ok" i18n="@@billing.team.status">Status</span>
            {{ e.status }}
            <ng-container *ngIf="e.expiresAt">
              <span i18n="@@billing.team.renews">- current period ends</span>
              {{ e.expiresAt | date: 'mediumDate' }}
            </ng-container>
          </p>
          <p i18n="@@billing.team.portal.body">
            Invoices, payment methods, and cancellation are managed in the Freemius customer portal.
          </p>
          <a class="btn btn--link" data-test="portal-link" href="https://users.freemius.com"
             target="_blank" rel="noopener" i18n="@@billing.team.portal">Open customer portal</a>
        </ng-template>
      </section>
    </div>
  `,
  styleUrls: ['./billing.component.scss']
})
export class BillingComponent implements OnInit {
  private readonly billing = inject(BillingService);
  private readonly toast = inject(ToastService);
  private readonly route = inject(ActivatedRoute);

  readonly entitlement = signal<EntitlementView | null>(null);
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  recoveryLicenseId = '';

  ngOnInit(): void {
    const licenseId = this.route.snapshot.queryParamMap.get('license_id');
    if (licenseId) {
      this.claim(licenseId);
    }
    this.load();
  }

  load(): void {
    this.billing.getEntitlement().subscribe({
      next: (e) => this.entitlement.set(e),
      error: () => this.error.set($localize`:@@billing.load.error:Could not load your plan. Retry shortly.`)
    });
  }

  upgrade(): void {
    this.busy.set(true);
    this.billing.createCheckoutSession().subscribe({
      next: (r) => this.navigateExternal(r.checkoutUrl),
      error: () => {
        this.busy.set(false);
        this.error.set($localize`:@@billing.checkout.error:Could not start checkout. Retry shortly.`);
      }
    });
  }

  claim(licenseId: string): void {
    this.busy.set(true);
    this.error.set(null);
    this.billing.claim(licenseId).subscribe({
      next: (e) => {
        this.busy.set(false);
        this.entitlement.set(e);
        this.toast.success($localize`:@@billing.claim.ok:Your workspace is now on the Team plan.`);
      },
      error: (err) => {
        this.busy.set(false);
        this.error.set(this.claimErrorText(err?.error?.error));
      }
    });
  }

  /** Extracted for testability -- full-page redirect to the hosted checkout. */
  navigateExternal(url: string): void {
    window.location.assign(url);
  }

  private claimErrorText(code: string | undefined): string {
    switch (code) {
      case 'license_already_bound':
        return $localize`:@@billing.claim.bound:This license is already linked to another workspace.`;
      case 'already_upgraded':
        return $localize`:@@billing.claim.upgraded:This workspace already has an active Team plan.`;
      case 'wrong_plan':
      case 'invalid_license':
        return $localize`:@@billing.claim.invalid:That license ID was not recognized. Check your receipt email.`;
      case 'license_inactive':
        return $localize`:@@billing.claim.inactive:This license is no longer active.`;
      default:
        return $localize`:@@billing.claim.error:Could not link the license. Retry shortly.`;
    }
  }
}
