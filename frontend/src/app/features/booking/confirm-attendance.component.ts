import { Component, ElementRef, OnInit, ViewChild, effect, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { ActivatedRoute } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { LiveAnnouncer } from '@angular/cdk/a11y';
import { BookingService, ConfirmAttendanceResponse } from './booking.service';

type ConfirmState =
  | 'loading'      // resolving the token from the URL
  | 'confirm'      // explicit affirmative prompt — the confirm POST has NOT fired yet
  | 'confirming'   // POST in flight
  | 'confirmed'    // 200 confirmed (or idempotent replay) — show the booked time in the local zone
  | 'expired'      // 410 — the interview has already passed
  | 'invalid'      // 400 / missing token — byte-identical not-an-oracle
  | 'rate_limited' // 429
  | 'retryable_error'; // network failure (status 0) — the confirm did not happen, retry is safe

/**
 * F23 candidate attendance confirmation (Flow A4, §IX). Reached from the candidate's REMINDER_24H email
 * link; the confirm token (DISTINCT from the F20 manage token) rides the URL query string and is held in
 * memory only (never persisted to web storage / never logged). This page presents an explicit affirmative
 * "Confirm attendance" step — the confirm POST fires ONLY on that button click, NEVER on page load (FR-006:
 * no prefetch / link-scanner / GET can auto-confirm). On success it renders the booked interview time in the
 * candidate's LOCAL time zone, DST-correct via the 'zzz' zone token (times only — never participant
 * identities or the location).
 *
 * Mobile-first + WCAG 2.2 AA: one focusable <h1 tabindex="-1"> per state with focus management (FR-024 — the
 * F14/F20 pattern), an assertive live region for transient errors (no double-announce: the heading conveys
 * the state, transient errors the alert region), >=44px targets, and all strings $localize-marked.
 */
@Component({
  selector: 'app-confirm-attendance',
  standalone: true,
  imports: [CommonModule, DatePipe],
  providers: [DatePipe],
  styleUrl: './booking-manage.component.scss',
  template: `
    <main class="booking">
      <h1 #stateHeading tabindex="-1" class="state-heading">{{ heading() }}</h1>

      <ng-container [ngSwitch]="state()">
        <section *ngSwitchCase="'loading'" aria-busy="true">
          <p i18n="@@confirm.loading">Loading your interview…</p>
        </section>

        <section *ngSwitchCase="'confirm'">
          <p i18n="@@confirm.prompt.body">Please confirm that you will attend your interview. This helps us keep your slot reserved.</p>
          <div class="actions">
            <button
              type="button"
              class="action reschedule"
              (click)="doConfirm()"
              [disabled]="busy()"
              i18n="@@confirm.prompt.yes">
              Confirm attendance
            </button>
          </div>
          <ng-container *ngTemplateOutlet="help"></ng-container>
        </section>

        <section *ngSwitchCase="'confirming'" aria-busy="true">
          <p i18n="@@confirm.confirming">Confirming your attendance…</p>
        </section>

        <section *ngSwitchCase="'confirmed'">
          <p class="confirmed" i18n="@@confirm.confirmed.body">
            Thanks, you're confirmed for {{ bookedStart() | date: 'EEE, d MMM y, HH:mm zzz' }}.
          </p>
          <p class="zone" i18n="@@confirm.zone">Shown in your local time zone ({{ localZone() }}).</p>
          <ng-container *ngTemplateOutlet="help"></ng-container>
        </section>

        <section *ngSwitchCase="'expired'">
          <p i18n="@@confirm.expired.body">This link has expired. Please contact your recruiter if you need to follow up.</p>
          <ng-container *ngTemplateOutlet="help"></ng-container>
        </section>

        <section *ngSwitchCase="'invalid'">
          <p i18n="@@confirm.invalid.body">This link is not valid.</p>
          <ng-container *ngTemplateOutlet="help"></ng-container>
        </section>

        <section *ngSwitchCase="'rate_limited'">
          <p i18n="@@confirm.rate.body">Too many attempts. Please wait a little while and try again.</p>
          <ng-container *ngTemplateOutlet="help"></ng-container>
        </section>

        <section *ngSwitchCase="'retryable_error'">
          <p i18n="@@confirm.retry.body">We couldn't reach the server, so nothing was changed. Your interview is still booked.</p>
          <button type="button" class="link-button" (click)="reset()" i18n="@@confirm.retry.action">Back</button>
          <ng-container *ngTemplateOutlet="help"></ng-container>
        </section>
      </ng-container>

      <!-- Transient, assertive errors — distinct from the heading so no double-announce. Always mounted
           (text-changes, not node-insertion) so screen readers announce it. -->
      <p class="err" role="alert" aria-live="assertive" [class.visually-hidden]="!error()">{{ error() }}</p>
    </main>

    <!-- Consistent help affordance: identical wording + placement across every state (WCAG 2.2 3.2.6). -->
    <ng-template #help>
      <p class="help" i18n="@@confirm.help">Need help? Please contact your recruiter.</p>
    </ng-template>
  `
})
export class ConfirmAttendanceComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly api = inject(BookingService);
  private readonly announcer = inject(LiveAnnouncer);

  @ViewChild('stateHeading') private headingRef?: ElementRef<HTMLElement>;

  // Memory-only — never written to local/session storage, never logged.
  private token = '';

  readonly state = signal<ConfirmState>('loading');
  readonly bookedStart = signal<string | null>(null);
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  readonly localZone = signal(Intl.DateTimeFormat().resolvedOptions().timeZone);

  // Skip the very first non-loading settle (loading -> confirm on init) so the page does not steal focus on
  // first paint; every subsequent state transition moves focus to the heading (FR-024).
  private firstSettle = true;

  constructor() {
    // FR-024: move focus to the heading on each transition (skip the initial 'loading'/first settle so the
    // page does not steal focus on first paint, and the in-flight 'confirming' which is a momentary busy
    // state). The heading text IS the SR state announcement, so we do NOT also LiveAnnounce it (no double-speak).
    effect(() => {
      const s = this.state();
      if (s === 'loading' || s === 'confirming') return;
      if (this.firstSettle) { this.firstSettle = false; return; }
      queueMicrotask(() => this.headingRef?.nativeElement?.focus());
    });
  }

  readonly heading = (): string => {
    switch (this.state()) {
      case 'loading': return $localize`:@@confirm.loading.title:Loading your interview…`;
      case 'confirm': return $localize`:@@confirm.prompt.title:Confirm your attendance`;
      case 'confirming': return $localize`:@@confirm.confirming.title:Confirming…`;
      case 'confirmed': return $localize`:@@confirm.confirmed.title:You're confirmed`;
      case 'expired': return $localize`:@@confirm.expired.title:This link has expired`;
      case 'rate_limited': return $localize`:@@confirm.rate.title:Too many attempts`;
      case 'retryable_error': return $localize`:@@confirm.retry.title:Nothing was changed`;
      default: return $localize`:@@confirm.invalid.title:This link is not valid`;
    }
  };

  ngOnInit(): void {
    // Re-resolve from the URL on every init (bfcache-safe), held in a memory-only field.
    this.token = this.route.snapshot.queryParamMap.get('token') ?? '';
    this.state.set(this.token ? 'confirm' : 'invalid');
  }

  /** Fires the confirm POST — ONLY from the affirmative "Confirm attendance" click (never on load). */
  doConfirm(): void {
    if (!this.token) { this.state.set('invalid'); return; }
    this.busy.set(true);
    this.error.set(null);
    this.state.set('confirming');
    this.api.confirm(this.token).subscribe({
      next: (r: ConfirmAttendanceResponse) => {
        this.busy.set(false);
        this.bookedStart.set(r.bookedStart);
        this.state.set('confirmed');
      },
      error: (e: HttpErrorResponse) => {
        this.busy.set(false);
        if (e.status === 429) { this.state.set('rate_limited'); return; }
        if (e.status === 410) { this.state.set('expired'); return; }
        if (e.status === 400) { this.state.set('invalid'); return; }
        if (!e.status) { this.state.set('retryable_error'); return; } // network — confirm did not happen
        // Any other transient server error: stay on the prompt, announce a retryable message.
        this.error.set($localize`:@@confirm.err.generic:We couldn't confirm your attendance — please try again.`);
        this.announcer.announce($localize`:@@confirm.err.generic:We couldn't confirm your attendance — please try again.`, 'assertive');
        this.state.set('confirm');
      }
    });
  }

  /** Return to the confirm prompt (after a retryable error — the booking is untouched). */
  reset(): void {
    this.error.set(null);
    this.state.set('confirm');
  }
}
