import { Component, ElementRef, OnInit, ViewChild, effect, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { BookingService, CancelResponse } from './booking.service';
import { CandidateBrandingService } from '../../core/branding/candidate-branding.service';

type CancelState =
  | 'confirm'      // explicit affirmative confirmation — the cancel POST has NOT fired yet
  | 'cancelling'   // POST in flight
  | 'cancelled'    // 200 cancelled
  | 'cleanup_incomplete' // 200 cleanup_incomplete — cancelled, recruiter will confirm
  | 'ineligible'   // 409 ineligible — can no longer cancel online
  | 'expired'      // 410 — interview already in the past
  | 'invalid'      // 400 / missing token
  | 'rate_limited' // 429
  | 'retryable_error'; // network failure — the cancel did not happen, so retrying is safe

/**
 * F20 candidate cancellation confirmation (Flow A3, §IX). Reached from the booking-manage page; the manage
 * token rides the URL query string (memory-only, never persisted / logged). This page presents an explicit
 * affirmative "Yes, cancel my interview" step — the cancel POST fires ONLY on that button click, NEVER on
 * page load (FR-012: no prefetch / link-scanner / GET can auto-cancel). "No, keep my interview" returns to
 * the booking-manage page without any state change.
 *
 * Mobile-first + WCAG 2.2 AA: one focusable <h1 tabindex="-1"> per state with focus management (FR-024), an
 * assertive live region for transient errors, >=44px targets, and $localize-marked strings throughout.
 */
@Component({
  selector: 'app-cancel-confirm',
  standalone: true,
  imports: [CommonModule],
  styleUrl: './booking-manage.component.scss',
  template: `
    <main class="booking">
      <h1 #stateHeading tabindex="-1" class="state-heading">{{ heading() }}</h1>

      <ng-container [ngSwitch]="state()">
        <section *ngSwitchCase="'confirm'">
          <p i18n="@@cancel.confirm.body">Are you sure you want to cancel your interview? This can't be undone online — your recruiter would need to set up a new time.</p>
          <div class="actions">
            <button
              type="button"
              class="action cancel"
              (click)="doCancel()"
              [disabled]="busy()"
              i18n="@@cancel.confirm.yes">
              Yes, cancel my interview
            </button>
            <button
              type="button"
              class="action reschedule"
              (click)="back()"
              [disabled]="busy()"
              i18n="@@cancel.confirm.no">
              No, keep my interview
            </button>
          </div>
          <ng-container *ngTemplateOutlet="help"></ng-container>
        </section>

        <section *ngSwitchCase="'cancelling'" aria-busy="true">
          <p i18n="@@cancel.cancelling">Cancelling your interview…</p>
        </section>

        <section *ngSwitchCase="'cancelled'">
          <p i18n="@@cancel.cancelled.body">Your interview has been cancelled. Thank you for letting us know.</p>
          <ng-container *ngTemplateOutlet="help"></ng-container>
        </section>

        <section *ngSwitchCase="'cleanup_incomplete'">
          <p i18n="@@cancel.cleanup.body">Your interview has been cancelled. Your recruiter will confirm the details shortly.</p>
          <ng-container *ngTemplateOutlet="help"></ng-container>
        </section>

        <section *ngSwitchCase="'ineligible'">
          <p i18n="@@cancel.ineligible.body">This interview can no longer be cancelled online. Please contact your recruiter.</p>
          <ng-container *ngTemplateOutlet="help"></ng-container>
        </section>

        <section *ngSwitchCase="'expired'">
          <p i18n="@@cancel.expired.body">This interview has already taken place. Please contact your recruiter if you need to follow up.</p>
          <ng-container *ngTemplateOutlet="help"></ng-container>
        </section>

        <section *ngSwitchCase="'invalid'">
          <p i18n="@@cancel.invalid.body">This link is not valid.</p>
          <ng-container *ngTemplateOutlet="help"></ng-container>
        </section>

        <section *ngSwitchCase="'rate_limited'">
          <p i18n="@@cancel.rate.body">Too many attempts. Please wait a little while and try again.</p>
          <ng-container *ngTemplateOutlet="help"></ng-container>
        </section>

        <section *ngSwitchCase="'retryable_error'">
          <p i18n="@@cancel.retry.body">We couldn't reach the server, so nothing was changed. Your interview is still booked.</p>
          <button type="button" class="link-button" (click)="reset()" i18n="@@cancel.retry.action">Back</button>
          <ng-container *ngTemplateOutlet="help"></ng-container>
        </section>
      </ng-container>

      <p class="err" role="alert" aria-live="assertive" [class.visually-hidden]="!error()">{{ error() }}</p>
    </main>

    <ng-template #help>
      <p class="help" i18n="@@cancel.help">Need help? Please contact your recruiter.</p>
    </ng-template>
  `
})
export class CancelConfirmComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly api = inject(BookingService);
  private readonly host = inject(ElementRef);
  private readonly branding = inject(CandidateBrandingService);

  @ViewChild('stateHeading') private headingRef?: ElementRef<HTMLElement>;

  // Memory-only — never written to local/session storage, never logged.
  private token = '';

  readonly state = signal<CancelState>('confirm');
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);

  constructor() {
    // FR-024: move focus to the heading on each transition (skip the in-flight 'cancelling' which is a
    // momentary busy state, and the initial 'confirm' so we don't steal focus on first paint).
    effect(() => {
      const s = this.state();
      if (s === 'confirm' || s === 'cancelling') return;
      queueMicrotask(() => this.headingRef?.nativeElement?.focus());
    });
  }

  readonly heading = (): string => {
    switch (this.state()) {
      case 'confirm': return $localize`:@@cancel.confirm.title:Cancel your interview?`;
      case 'cancelling': return $localize`:@@cancel.cancelling.title:Cancelling…`;
      case 'cancelled': return $localize`:@@cancel.cancelled.title:Interview cancelled`;
      case 'cleanup_incomplete': return $localize`:@@cancel.cleanup.title:Interview cancelled`;
      case 'ineligible': return $localize`:@@cancel.ineligible.title:This interview can't be cancelled online`;
      case 'expired': return $localize`:@@cancel.expired.title:This interview has already taken place`;
      case 'rate_limited': return $localize`:@@cancel.rate.title:Too many attempts`;
      case 'retryable_error': return $localize`:@@cancel.retry.title:Nothing was changed`;
      default: return $localize`:@@cancel.invalid.title:This link is not valid`;
    }
  };

  ngOnInit(): void {
    this.branding.applyAccent(this.host.nativeElement);
    this.token = this.route.snapshot.queryParamMap.get('token') ?? '';
    if (!this.token) { this.state.set('invalid'); }
  }

  /** Fires the cancel POST — ONLY from the affirmative "Yes, cancel" click (never on load). */
  doCancel(): void {
    if (!this.token) { this.state.set('invalid'); return; }
    this.busy.set(true);
    this.error.set(null);
    this.state.set('cancelling');
    this.api.cancel(this.token).subscribe({
      next: (r: CancelResponse) => {
        this.busy.set(false);
        this.state.set(r.status === 'cleanup_incomplete' ? 'cleanup_incomplete' : 'cancelled');
      },
      error: (e: HttpErrorResponse) => {
        this.busy.set(false);
        if (e.status === 429) { this.state.set('rate_limited'); return; }
        if (e.status === 410) { this.state.set('expired'); return; }
        const code = e.error?.error;
        if (code === 'cleanup_incomplete') { this.state.set('cleanup_incomplete'); return; }
        if (code === 'ineligible') { this.state.set('ineligible'); return; }
        if (e.status === 400) { this.state.set('invalid'); return; }
        if (!e.status) { this.state.set('retryable_error'); return; } // network — cancel did not happen
        this.error.set($localize`:@@cancel.err.generic:We couldn't cancel your interview — please try again.`);
        this.state.set('confirm');
      }
    });
  }

  /** Return to the confirm prompt (after a retryable error — the booking is untouched). */
  reset(): void {
    this.error.set(null);
    this.state.set('confirm');
  }

  /** "No, keep my interview" — return to the booking-manage page with no state change. */
  back(): void {
    this.router.navigate(['/booking'], { queryParams: { token: this.token } });
  }
}
