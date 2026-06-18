import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { AtsService, AtsHealth, AtsSyncStatus, AtsDeadLetter } from './ats.service';

/**
 * F40 ATS integration admin screen (US1/US2/US4). Connect/disconnect Greenhouse (write-only API key), and
 * view connection health, last sync, degraded state, and dead-lettered write-backs. Internal Admin screen —
 * no candidate PII surface, no WCAG/Lighthouse gate (the F50/F51 internal-screen precedent). All strings $localize.
 */
@Component({
  selector: 'app-ats-integration',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <section class="ats">
      <h1>{{ title }}</h1>

      <div class="status" *ngIf="health() as h">
        <p>
          <strong>{{ providerLabel }}</strong>
          <span class="badge" [class.ok]="h.status === 'CONNECTED'" [class.warn]="h.degraded">{{ h.status }}</span>
        </p>
        <p *ngIf="h.lastVerifiedAt">{{ verifiedLabel }} {{ h.lastVerifiedAt }}</p>
        <p *ngIf="h.lastSyncAt">{{ lastSyncLabel }} {{ h.lastSyncAt }}</p>
        <p *ngIf="h.degraded" class="warn-text">{{ degradedLabel }}</p>
        <p *ngIf="h.deadLetterCount > 0" class="warn-text">{{ deadLetterCountLabel }} {{ h.deadLetterCount }}</p>
      </div>

      <form class="connect" (ngSubmit)="connect()" *ngIf="!health()?.credentialSet">
        <label for="apiKey">{{ apiKeyLabel }}</label>
        <input id="apiKey" name="apiKey" type="password" [(ngModel)]="apiKey" autocomplete="off" />
        <button type="submit" [disabled]="busy()">{{ connectLabel }}</button>
      </form>

      <button class="disconnect" *ngIf="health()?.credentialSet" (click)="disconnect()" [disabled]="busy()">
        {{ disconnectLabel }}
      </button>

      <p class="error" *ngIf="error()">{{ error() }}</p>

      <div class="sync" *ngIf="sync() as s">
        <h2>{{ syncTitle }}</h2>
        <p>{{ s.lastOutcome || none }} — {{ processedLabel }} {{ s.processed }}, {{ createdLabel }} {{ s.created }},
          {{ updatedLabel }} {{ s.updated }}</p>
      </div>

      <div class="dead-letters" *ngIf="deadLetters().length > 0">
        <h2>{{ deadLetterTitle }}</h2>
        <ul>
          <li *ngFor="let d of deadLetters()">{{ d.type }} — {{ d.lastOutcomeCategory }} ({{ d.attemptCount }})</li>
        </ul>
      </div>
    </section>
  `,
  styleUrls: ['./ats-integration.component.scss']
})
export class AtsIntegrationComponent implements OnInit {
  private readonly ats = inject(AtsService);

  readonly title = $localize`:@@ats.title:ATS Integration (Greenhouse)`;
  readonly providerLabel = $localize`:@@ats.provider:Greenhouse`;
  readonly verifiedLabel = $localize`:@@ats.verified:Last verified:`;
  readonly lastSyncLabel = $localize`:@@ats.lastSync:Last sync:`;
  readonly degradedLabel = $localize`:@@ats.degraded:Integration is degraded — sync or write-back is failing.`;
  readonly deadLetterCountLabel = $localize`:@@ats.deadLetterCount:Write-backs needing attention:`;
  readonly apiKeyLabel = $localize`:@@ats.apiKey:Greenhouse API key`;
  readonly connectLabel = $localize`:@@ats.connect:Connect`;
  readonly disconnectLabel = $localize`:@@ats.disconnect:Disconnect`;
  readonly syncTitle = $localize`:@@ats.syncTitle:Last sync`;
  readonly deadLetterTitle = $localize`:@@ats.deadLetterTitle:Dead-lettered write-backs`;
  readonly processedLabel = $localize`:@@ats.processed:processed`;
  readonly createdLabel = $localize`:@@ats.created:created`;
  readonly updatedLabel = $localize`:@@ats.updated:updated`;
  readonly none = $localize`:@@ats.none:No sync yet`;
  private readonly connectFailed = $localize`:@@ats.connectFailed:Could not connect — check the API key.`;

  readonly health = signal<AtsHealth | null>(null);
  readonly sync = signal<AtsSyncStatus | null>(null);
  readonly deadLetters = signal<AtsDeadLetter[]>([]);
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  apiKey = '';

  ngOnInit(): void {
    this.refresh();
  }

  refresh(): void {
    this.ats.getHealth().subscribe({ next: (h) => this.health.set(h) });
    this.ats.syncStatus().subscribe({ next: (s) => this.sync.set(s), error: () => {} });
    this.ats.deadLetters().subscribe({ next: (d) => this.deadLetters.set(d), error: () => {} });
  }

  connect(): void {
    if (!this.apiKey.trim()) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.ats.connect(this.apiKey).subscribe({
      next: (h) => {
        this.health.set(h);
        this.apiKey = '';
        this.busy.set(false);
      },
      error: () => {
        this.error.set(this.connectFailed);
        this.busy.set(false);
      }
    });
  }

  disconnect(): void {
    this.busy.set(true);
    this.ats.disconnect().subscribe({
      next: () => {
        this.busy.set(false);
        this.refresh();
      },
      error: () => this.busy.set(false)
    });
  }
}
