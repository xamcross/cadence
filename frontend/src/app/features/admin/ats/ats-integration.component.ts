import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { AtsService, AtsHealth } from './ats.service';

/**
 * F40/F41 ATS integration admin screen (US1/US2/US4). Lists EVERY ATS provider (Greenhouse + Lever) with its own
 * connect/disconnect (write-only API key), health, last-sync, degraded state, and dead-letter count — each
 * managed independently (coexistence). Internal Admin screen — no candidate PII surface, no WCAG/Lighthouse gate
 * (the F50/F51 internal-screen precedent). All strings $localize.
 */
@Component({
  selector: 'app-ats-integration',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <section class="ats">
      <h1>{{ title }}</h1>

      <article class="provider" *ngFor="let p of providers()">
        <div class="status">
          <p>
            <strong>{{ p.provider }}</strong>
            <span class="badge" [class.ok]="p.status === 'CONNECTED'" [class.warn]="p.degraded">{{ p.status }}</span>
          </p>
          <p *ngIf="p.lastVerifiedAt">{{ verifiedLabel }} {{ p.lastVerifiedAt }}</p>
          <p *ngIf="p.lastSyncAt">{{ lastSyncLabel }} {{ p.lastSyncAt }}</p>
          <p *ngIf="p.degraded" class="warn-text">{{ degradedLabel }}</p>
          <p *ngIf="p.deadLetterCount > 0" class="warn-text">{{ deadLetterCountLabel }} {{ p.deadLetterCount }}</p>
        </div>

        <form class="connect field" (ngSubmit)="connect(p.provider)" *ngIf="!p.credentialSet">
          <label class="field__label" [attr.for]="'apiKey-' + p.provider">{{ apiKeyLabel }} ({{ p.provider }})</label>
          <input class="input" [id]="'apiKey-' + p.provider" [name]="'apiKey-' + p.provider" type="password"
                 [ngModel]="keys[p.provider]" (ngModelChange)="keys[p.provider] = $event" autocomplete="off" />
          <button type="submit" class="btn btn--primary" [disabled]="busy()">{{ connectLabel }}</button>
        </form>

        <button class="disconnect btn btn--danger-soft" *ngIf="p.credentialSet" (click)="disconnect(p.provider)" [disabled]="busy()">
          {{ disconnectLabel }}
        </button>
      </article>

      <p class="error alert alert--danger" role="alert" *ngIf="error()">{{ error() }}</p>
    </section>
  `,
  styleUrls: ['./ats-integration.component.scss']
})
export class AtsIntegrationComponent implements OnInit {
  private readonly ats = inject(AtsService);

  readonly title = $localize`:@@ats.title:ATS Integrations`;
  readonly verifiedLabel = $localize`:@@ats.verified:Last verified:`;
  readonly lastSyncLabel = $localize`:@@ats.lastSync:Last sync:`;
  readonly degradedLabel = $localize`:@@ats.degraded:Integration is degraded — sync or write-back is failing.`;
  readonly deadLetterCountLabel = $localize`:@@ats.deadLetterCount:Write-backs needing attention:`;
  readonly apiKeyLabel = $localize`:@@ats.apiKey:API key`;
  readonly connectLabel = $localize`:@@ats.connect:Connect`;
  readonly disconnectLabel = $localize`:@@ats.disconnect:Disconnect`;
  private readonly connectFailed = $localize`:@@ats.connectFailed:Could not connect — check the API key.`;

  readonly providers = signal<AtsHealth[]>([]);
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  /** Per-provider write-only key inputs (never retained after a successful connect). */
  keys: Record<string, string> = {};

  ngOnInit(): void {
    this.refresh();
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
    this.error.set(null);
    this.ats.connect(provider, key).subscribe({
      next: () => {
        this.keys[provider] = '';
        this.busy.set(false);
        this.refresh();
      },
      error: () => {
        this.error.set(this.connectFailed);
        this.busy.set(false);
      }
    });
  }

  disconnect(provider: string): void {
    this.busy.set(true);
    this.ats.disconnect(provider).subscribe({
      next: () => {
        this.busy.set(false);
        this.refresh();
      },
      error: () => this.busy.set(false)
    });
  }
}
