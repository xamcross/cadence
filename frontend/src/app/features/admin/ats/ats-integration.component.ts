import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { AtsService, AtsHealth } from './ats.service';
import { BillingService } from '../billing/billing.service';
import { PageHeaderComponent } from '../../../shared/ui/page-header.component';
import { ConfirmDialogService } from '../../../shared/ui/confirm-dialog.service';
import { ToastService } from '../../../shared/ui/toast.service';
import { UpgradePromptComponent } from '../../../shared/ui/upgrade-prompt.component';

/**
 * F40/F41 ATS integration admin screen (US1/US2/US4). Lists EVERY ATS provider (Greenhouse + Lever) with its own
 * connect/disconnect (write-only API key), health, last-sync, degraded state, and dead-letter count — each
 * managed independently (coexistence). Internal Admin screen — no candidate PII surface, no WCAG/Lighthouse gate
 * (the F50/F51 internal-screen precedent). All strings $localize.
 *
 * Phase 3b (workbench overhaul): `disconnect(provider)` is gated behind the shared `ConfirmDialogService`
 * (⚠ danger). `connect`/`disconnect` outcomes are surfaced via `ToastService`, replacing the old
 * connect-only inline `error` signal (disconnect previously had no feedback at all).
 *
 * 032 T9: ATS integrations are a Team-plan feature (FR-013). On a FREE workspace the CONNECT form is replaced
 * by the shared upgrade prompt — but provider status keeps rendering unconditionally, so a previously configured
 * connection still reads as retained (with a `pausedForPlan` badge), never as gone (US2-AS2). DISCONNECT stays
 * outside the gate (final-review fix): the server never gates it, and a Free admin must be able to remove a
 * stored credential. A failed entitlement load leaves `plan()` null, which is treated the same as TEAM here
 * (never blocks the screen — the server remains the actual enforcement boundary).
 */
@Component({
  selector: 'app-ats-integration',
  standalone: true,
  imports: [CommonModule, FormsModule, PageHeaderComponent, UpgradePromptComponent],
  template: `
    <section class="ats">
      <app-page-header
        eyebrow="Administration" i18n-eyebrow="@@ats.eyebrow"
        heading="ATS Integrations" i18n-heading="@@ats.title"
        subtitle="Connect Greenhouse or Lever and sync status." i18n-subtitle="@@ats.subtitle">
      </app-page-header>

      @if (plan() === 'FREE') {
        <app-upgrade-prompt featureLabel="ATS integrations" i18n-featureLabel="@@upgrade.ats" />
      }

      <article class="provider" *ngFor="let p of providers()">
        <div class="status">
          <p>
            <strong>{{ p.provider }}</strong>
            <span class="badge" [class.ok]="p.status === 'CONNECTED'" [class.warn]="p.degraded">{{ p.status }}</span>
            @if (p.pausedForPlan) {
              <span class="badge badge--warn" data-test="paused-badge" i18n="@@ats.pausedForPlan">Paused - requires Team plan</span>
            }
          </p>
          <p *ngIf="p.lastVerifiedAt">{{ verifiedLabel }} {{ p.lastVerifiedAt }}</p>
          <p *ngIf="p.lastSyncAt">{{ lastSyncLabel }} {{ p.lastSyncAt }}</p>
          <p *ngIf="p.degraded" class="warn-text">{{ degradedLabel }}</p>
          <p *ngIf="p.deadLetterCount > 0" class="warn-text">{{ deadLetterCountLabel }} {{ p.deadLetterCount }}</p>
        </div>

        @if (plan() !== 'FREE') {
          <form class="connect field" (ngSubmit)="connect(p.provider)" *ngIf="!p.credentialSet">
            <label class="field__label" [attr.for]="'apiKey-' + p.provider">{{ apiKeyLabel }} ({{ p.provider }})</label>
            <input class="input" [id]="'apiKey-' + p.provider" [name]="'apiKey-' + p.provider" type="password"
                   [ngModel]="keys[p.provider]" (ngModelChange)="keys[p.provider] = $event" autocomplete="off" />
            <button type="submit" class="btn btn--primary" [disabled]="busy()">{{ connectLabel }}</button>
          </form>
        }

        <!-- Deliberately OUTSIDE the plan gate: disconnect is ungated server-side, so a FREE admin must always
             be able to remove a stored credential (never trap a workspace with a paused connection it cannot delete). -->
        <button class="disconnect btn btn--danger-soft" *ngIf="p.credentialSet" (click)="disconnect(p.provider)" [disabled]="busy()">
          {{ disconnectLabel }}
        </button>
      </article>
    </section>
  `,
  styleUrls: ['./ats-integration.component.scss']
})
export class AtsIntegrationComponent implements OnInit {
  private readonly ats = inject(AtsService);
  private readonly billing = inject(BillingService);
  private readonly confirm = inject(ConfirmDialogService);
  private readonly toast = inject(ToastService);

  readonly verifiedLabel = $localize`:@@ats.verified:Last verified:`;
  readonly lastSyncLabel = $localize`:@@ats.lastSync:Last sync:`;
  readonly degradedLabel = $localize`:@@ats.degraded:Integration is degraded — sync or write-back is failing.`;
  readonly deadLetterCountLabel = $localize`:@@ats.deadLetterCount:Write-backs needing attention:`;
  readonly apiKeyLabel = $localize`:@@ats.apiKey:API key`;
  readonly connectLabel = $localize`:@@ats.connect:Connect`;
  readonly disconnectLabel = $localize`:@@ats.disconnect:Disconnect`;

  readonly providers = signal<AtsHealth[]>([]);
  readonly busy = signal(false);
  /** 032: null until the entitlement load resolves; a load failure leaves it null (never blocks the screen). */
  readonly plan = signal<'FREE' | 'TEAM' | null>(null);
  /** Per-provider write-only key inputs (never retained after a successful connect). */
  keys: Record<string, string> = {};

  ngOnInit(): void {
    this.refresh();
    this.billing.getEntitlement().subscribe({
      next: (e) => this.plan.set(e.plan),
      error: () => this.plan.set(null)
    });
  }

  refresh(): void {
    this.ats.getConnections().subscribe({ next: (list) => this.providers.set(list) });
  }

  connect(provider: string): void {
    const key = (this.keys[provider] ?? '').trim();
    if (!key) {
      return;
    }
    this.busy.set(true);
    this.ats.connect(provider, key).subscribe({
      next: () => {
        this.keys[provider] = '';
        this.busy.set(false);
        this.toast.success($localize`:@@toast.ats.connected:${provider}:provider: connected.`);
        this.refresh();
      },
      error: () => {
        this.busy.set(false);
        this.toast.error($localize`:@@toast.ats.connectFailed:Could not connect — check the API key.`);
      }
    });
  }

  async disconnect(provider: string): Promise<void> {
    const ok = await this.confirm.confirm({
      title: $localize`:@@confirm.ats.disconnect.title:Disconnect ${provider}:provider:?`,
      body: $localize`:@@confirm.ats.disconnect.body:Cadence will stop syncing with ${provider}:provider:. You'll need to re-enter the API key to reconnect.`,
      confirmLabel: $localize`:@@confirm.ats.disconnect.cta:Disconnect`,
      danger: true
    });
    if (!ok) { return; }
    this.busy.set(true);
    this.ats.disconnect(provider).subscribe({
      next: () => {
        this.busy.set(false);
        this.toast.success($localize`:@@toast.ats.disconnected:${provider}:provider: disconnected.`);
        this.refresh();
      },
      error: () => {
        this.busy.set(false);
        this.toast.error($localize`:@@toast.ats.disconnectFailed:Could not disconnect ${provider}:provider:.`);
      }
    });
  }
}
