import { Component, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { CalendarService, ConnectionRow } from './calendar.service';

interface ProviderDef {
  id: string; // path segment: google | microsoft
  enumName: string; // GOOGLE | MICROSOFT
  label: string;
}

/**
 * Member-self "Calendar connections" surface (F01.1). Any authenticated role (authGuard only).
 * Connect performs a FULL-PAGE navigation to the provider authorize URL (so the callback is a
 * top-level GET carrying the SameSite=Lax session cookie) — NOT the Angular Router. On return the
 * provider redirects to ?connected=... or ?error=..., which is read here and shown as a banner.
 */
@Component({
  selector: 'app-calendar-connections',
  standalone: true,
  template: `
    <h1 i18n="@@calendar.title">Calendar connections</h1>

    @if (banner(); as b) {
      <p role="alert" [class.error]="b.type === 'error'" [class.success]="b.type === 'success'">{{ b.text }}</p>
    }

    <ul class="providers">
      @for (p of providers; track p.id) {
        <li class="provider">
          <span class="name">{{ p.label }}</span>
          @if (rowFor(p.enumName); as row) {
            @if (row.status === 'NEEDS_RECONNECTION') {
              <span class="status reconnect" i18n="@@calendar.needsReconnection">Needs reconnection</span>
              <button type="button" [disabled]="starting() === p.id" (click)="connect(p)"
                      i18n="@@calendar.reconnect">Reconnect</button>
            } @else {
              <span class="status connected" i18n="@@calendar.connectedAs">Connected as {{ row.connectedAccount }}</span>
              @if (confirmingDisconnect() === p.id) {
                <span i18n="@@calendar.confirmDisconnect">Disconnect this calendar?</span>
                <button type="button" (click)="disconnect(p)" i18n="@@calendar.confirmYes">Yes, disconnect</button>
                <button type="button" (click)="confirmingDisconnect.set(null)" i18n="@@calendar.cancel">Cancel</button>
              } @else {
                <button type="button" (click)="confirmingDisconnect.set(p.id)" i18n="@@calendar.disconnect">Disconnect</button>
              }
            }
          } @else {
            <span class="status notconnected" i18n="@@calendar.notConnected">Not connected</span>
            <button type="button" [disabled]="starting() === p.id" (click)="connect(p)"
                    i18n="@@calendar.connect">Connect</button>
          }
        </li>
      }
    </ul>
  `,
  styles: [`
    .providers { list-style: none; padding: 0; }
    .provider { display: flex; align-items: center; gap: 1rem; padding: 0.5rem 0; }
    .name { font-weight: 600; min-width: 8rem; }
    .spacer { flex: 1; }
    button { min-height: 44px; }
    .error { color: #b00020; }
    .success { color: #1b5e20; }
    .reconnect { color: #b00020; }
  `]
})
export class CalendarConnectionsComponent implements OnInit {
  private readonly calendar = inject(CalendarService);
  private readonly route = inject(ActivatedRoute);

  readonly providers: ProviderDef[] = [
    { id: 'google', enumName: 'GOOGLE', label: 'Google Calendar' },
    { id: 'microsoft', enumName: 'MICROSOFT', label: 'Microsoft 365' }
  ];

  readonly connections = signal<ConnectionRow[]>([]);
  readonly starting = signal<string | null>(null);
  readonly confirmingDisconnect = signal<string | null>(null);
  readonly banner = signal<{ type: 'success' | 'error'; text: string } | null>(null);

  ngOnInit(): void {
    const q = this.route.snapshot.queryParamMap;
    const connected = q.get('connected');
    const error = q.get('error');
    if (connected) {
      this.banner.set({ type: 'success', text: $localize`:@@calendar.banner.connected:Your calendar is now connected.` });
    } else if (error) {
      this.banner.set({ type: 'error', text: this.errorMessage(error) });
    }
    this.load();
  }

  rowFor(enumName: string): ConnectionRow | undefined {
    return this.connections().find((c) => c.provider === enumName);
  }

  connect(p: ProviderDef): void {
    this.starting.set(p.id);
    this.calendar.start(p.id).subscribe({
      // Full-page navigation so the eventual callback is a top-level GET (carries the session cookie).
      next: (r) => (window.location.href = r.authorizationUrl),
      error: () => {
        this.starting.set(null);
        this.banner.set({ type: 'error', text: $localize`:@@calendar.banner.startFailed:Could not start the connection. Please try again.` });
      }
    });
  }

  disconnect(p: ProviderDef): void {
    this.calendar.disconnect(p.id).subscribe({
      next: () => {
        this.confirmingDisconnect.set(null);
        this.load();
      },
      error: () => this.banner.set({ type: 'error', text: $localize`:@@calendar.banner.disconnectFailed:Could not disconnect. Please try again.` })
    });
  }

  private load(): void {
    this.calendar.list().subscribe((r) => this.connections.set(r.connections));
  }

  private errorMessage(code: string): string {
    switch (code) {
      case 'consent_denied':
        return $localize`:@@calendar.error.consentDenied:You declined access. The calendar was not connected.`;
      case 'no_offline_grant':
        return $localize`:@@calendar.error.noOfflineGrant:The provider did not grant offline access. Please try connecting again.`;
      case 'session_expired':
        return $localize`:@@calendar.error.sessionExpired:Your session expired during connection. Please sign in and try again.`;
      case 'exchange_failed':
        return $localize`:@@calendar.error.exchangeFailed:The connection could not be completed. Please try again.`;
      default:
        return $localize`:@@calendar.error.invalidState:The connection link was invalid or expired. Please try again.`;
    }
  }
}
