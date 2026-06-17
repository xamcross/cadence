import { Component, ElementRef, OnInit, ViewChild, effect, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { ActivatedRoute } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { LiveAnnouncer } from '@angular/cdk/a11y';
import { CandidateStatusView, PublicBranding, StatusService } from './status.service';

type StatusState =
  | 'loading'         // resolving the token from the URL / fetching the view
  | 'published'       // in-progress, dated — stage + next step + date
  | 'past_date'       // in-progress, past the expected date — "still your stage" framing (FR-017)
  | 'terminal'        // concluded — honest closing message + outcome
  | 'under_review'    // never published — neutral "under review" default (FR-006)
  | 'invalid'         // 404 (unknown / malformed / erased) — byte-identical not-an-oracle
  | 'rate_limited'    // 429
  | 'retryable_error'; // network failure (status 0) — retry is safe

/**
 * F30 candidate status page (Flow §IX). PUBLIC, no login — the status token rides the URL query string and is
 * held in memory only (never persisted to web storage / never logged — FR-032/SC-012). On init it re-resolves
 * the token from the URL (bfcache-safe) and GETs the server-resolved `displayState`, rendering exactly ONE
 * block per state. Recruiter-authored free text (stage / next step) is rendered via Angular interpolation
 * ONLY (auto-escaped — never `[innerHTML]`, so markup-laden text renders INERT — FR-009/SC-015). The expected
 * date is presented in the candidate's LOCAL presentation, with the workspace zone labelled for clarity.
 *
 * Branding (logo + brand colour) is composed from the public workspace branding endpoint (F03) — never any
 * candidate PII. The only "contact route" surfaced is a generic, workspace-sourced "contact your recruiter"
 * affordance (FR-007 — never a candidate email/phone).
 *
 * A "Request data deletion" affirmative action (FR-015/017) POSTs an erasure request and shows an on-page
 * acknowledgement; it fires ONLY on the explicit button click (never on load).
 *
 * Mobile-first + WCAG 2.2 AA: one focusable <h1 tabindex="-1"> per state with focus management (FR-024 — the
 * F14/F23 pattern), an assertive live region for transient errors (no double-announce), >=44px targets, all
 * strings $localize-marked, RTL/long-text-safe via overflow-wrap on the container.
 */
@Component({
  selector: 'app-candidate-status',
  standalone: true,
  imports: [CommonModule, DatePipe],
  providers: [DatePipe],
  styleUrl: './candidate-status.component.scss',
  template: `
    <main class="status">
      <!-- Branding header (logo + brand colour). The logo URL is workspace-sourced; never candidate PII. -->
      <header class="brand" [style.border-block-end-color]="brandColor()">
        <img class="logo" [src]="logoUrl()" alt="" aria-hidden="true" />
      </header>

      <h1 #stateHeading tabindex="-1" class="state-heading">{{ heading() }}</h1>

      <ng-container [ngSwitch]="state()">
        <section *ngSwitchCase="'loading'" aria-busy="true">
          <p i18n="@@status.loading">Loading your application status…</p>
        </section>

        <!-- PUBLISHED: in-progress, dated. Free text via interpolation only (auto-escaped). -->
        <section *ngSwitchCase="'published'">
          <dl class="status-detail">
            <dt i18n="@@status.stage.label">Current stage</dt>
            <dd class="stage">{{ view()?.stage }}</dd>
            <dt i18n="@@status.next.label">What happens next</dt>
            <dd class="next-step">{{ view()?.nextStep }}</dd>
            <dt i18n="@@status.date.label">Expected by</dt>
            <dd class="expected-date">{{ expectedDateDisplay() }}</dd>
          </dl>
          <p class="zone" i18n="@@status.zone">Dates are shown in the {{ workspaceZone() }} time zone.</p>
        </section>

        <!-- PAST_DATE: in-progress, past the expected date (FR-017) — preserve the stage, honest framing. -->
        <section *ngSwitchCase="'past_date'">
          <p class="past" i18n="@@status.past.body">
            We are taking a little longer than expected. Your application is still active and currently at this stage:
          </p>
          <dl class="status-detail">
            <dt i18n="@@status.stage.label">Current stage</dt>
            <dd class="stage">{{ view()?.stage }}</dd>
            <dt i18n="@@status.date.label">Expected by</dt>
            <dd class="expected-date">{{ expectedDateDisplay() }}</dd>
          </dl>
          <ng-container *ngTemplateOutlet="help"></ng-container>
        </section>

        <!-- TERMINAL: concluded — honest closing message + outcome. Free text via interpolation only. -->
        <section *ngSwitchCase="'terminal'">
          <p class="outcome">{{ outcomeLabel() }}</p>
          <p class="next-step">{{ view()?.nextStep }}</p>
          <ng-container *ngTemplateOutlet="help"></ng-container>
        </section>

        <!-- UNDER_REVIEW: never published — neutral default (FR-006). -->
        <section *ngSwitchCase="'under_review'">
          <p i18n="@@status.review.body">Your application is being reviewed. We will update this page as things progress.</p>
          <ng-container *ngTemplateOutlet="help"></ng-container>
        </section>

        <section *ngSwitchCase="'invalid'">
          <p i18n="@@status.invalid.body">This link is not valid.</p>
          <ng-container *ngTemplateOutlet="help"></ng-container>
        </section>

        <section *ngSwitchCase="'rate_limited'">
          <p i18n="@@status.rate.body">Too many attempts. Please wait a little while and try again.</p>
          <ng-container *ngTemplateOutlet="help"></ng-container>
        </section>

        <section *ngSwitchCase="'retryable_error'">
          <p i18n="@@status.retry.body">We couldn't load your application status.</p>
          <button type="button" class="action retry" (click)="reload(true)" i18n="@@status.retry.action">Try again</button>
          <ng-container *ngTemplateOutlet="help"></ng-container>
        </section>
      </ng-container>

      <!-- "Request data deletion" — an affirmative GDPR self-service action (FR-015). Offered on every
           resolved state where the candidate has a live link; the POST fires ONLY on the explicit click.
           After acknowledgement it is replaced by an on-page confirmation (FR-017). -->
      <section class="erasure" *ngIf="canRequestErasure()">
        <ng-container *ngIf="!erasureAck(); else erasureAcked">
          <button
            type="button"
            class="action erasure-request"
            (click)="requestErasure()"
            [disabled]="erasureBusy()"
            i18n="@@status.erasure.action">
            Request data deletion
          </button>
          <p class="erasure-note" i18n="@@status.erasure.note">
            You can ask us to delete your personal data. We will review your request.
          </p>
        </ng-container>
        <ng-template #erasureAcked>
          <p class="erasure-ack" role="status" i18n="@@status.erasure.ack">
            Thank you. We have received your data deletion request and will review it.
          </p>
        </ng-template>
      </section>

      <!-- Transient, assertive errors — distinct from the heading so no double-announce. Always mounted. -->
      <p class="err" role="alert" aria-live="assertive" [class.visually-hidden]="!error()">{{ error() }}</p>
    </main>

    <!-- Consistent help affordance: a generic, workspace-sourced "contact your recruiter" route. Never a
         candidate email/phone (FR-007). Identical wording + placement across states (WCAG 2.2 3.2.6). -->
    <ng-template #help>
      <p class="help" i18n="@@status.help">Need help? Please contact your recruiter.</p>
    </ng-template>
  `
})
export class CandidateStatusComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly api = inject(StatusService);
  private readonly datePipe = inject(DatePipe);
  private readonly announcer = inject(LiveAnnouncer);

  @ViewChild('stateHeading') private headingRef?: ElementRef<HTMLElement>;

  // Memory-only — never written to local/session storage, never logged.
  private token = '';

  readonly state = signal<StatusState>('loading');
  readonly view = signal<CandidateStatusView | null>(null);
  readonly brandColor = signal<string>('#1F2937');
  readonly logoUrl = signal<string>('');
  readonly error = signal<string | null>(null);
  readonly erasureBusy = signal(false);
  readonly erasureAck = signal(false);

  // Skip the very first non-loading settle so the page does not steal focus on first paint; every
  // subsequent state transition moves focus to the heading (FR-024).
  private firstSettle = true;

  constructor() {
    effect(() => {
      const s = this.state();
      if (s === 'loading') return;
      if (this.firstSettle) { this.firstSettle = false; return; }
      queueMicrotask(() => this.headingRef?.nativeElement?.focus());
    });
  }

  readonly heading = (): string => {
    switch (this.state()) {
      case 'loading': return $localize`:@@status.loading.title:Loading your application status…`;
      case 'published': return $localize`:@@status.published.title:Your application status`;
      case 'past_date': return $localize`:@@status.past.title:Your application status`;
      case 'terminal': return $localize`:@@status.terminal.title:Your application status`;
      case 'under_review': return $localize`:@@status.review.title:Your application is under review`;
      case 'rate_limited': return $localize`:@@status.rate.title:Too many attempts`;
      case 'retryable_error': return $localize`:@@status.retry.title:Something went wrong`;
      default: return $localize`:@@status.invalid.title:This link is not valid`;
    }
  };

  readonly workspaceZone = (): string => this.view()?.workspaceZone ?? '';

  /** The expected date in the candidate's local presentation (FR-004) — date only, no device-clock framing. */
  readonly expectedDateDisplay = (): string => {
    const iso = this.view()?.expectedDate;
    if (!iso) { return ''; }
    return this.datePipe.transform(iso, 'EEEE, d MMMM y') ?? iso;
  };

  readonly outcomeLabel = (): string => {
    switch (this.view()?.outcome) {
      case 'COMPLETE_OFFER': return $localize`:@@status.outcome.offer:Good news — your application has progressed to an offer.`;
      case 'COMPLETE_REJECTED': return $localize`:@@status.outcome.rejected:Your application has concluded.`;
      default: return $localize`:@@status.outcome.complete:Your application has concluded.`;
    }
  };

  /** The erasure control is offered only once the link has resolved to a real (non-error) status. */
  canRequestErasure(): boolean {
    const s = this.state();
    return s === 'published' || s === 'past_date' || s === 'terminal' || s === 'under_review';
  }

  ngOnInit(): void {
    // Re-resolve from the URL on every init (bfcache-safe), held in a memory-only field.
    this.token = this.route.snapshot.queryParamMap.get('token') ?? '';
    if (!this.token) { this.state.set('invalid'); return; }
    this.loadBranding();
    this.reload();
  }

  /** Compose the workspace branding (logo + colour) — best-effort; a failure leaves the defaults. */
  private loadBranding(): void {
    this.logoUrl.set(`${this.brandingLogoFallback()}`);
    this.api.branding().subscribe({
      next: (b: PublicBranding) => {
        if (b?.brandColor) { this.brandColor.set(b.brandColor); }
        if (b?.logoUrl) { this.logoUrl.set(b.logoUrl); }
      },
      error: () => { /* keep defaults — branding is decorative, never blocks the status */ }
    });
  }

  private brandingLogoFallback(): string {
    // Same-origin public logo route (F03) — workspace-sourced, never candidate PII.
    return '/api/public/workspace/logo';
  }

  /** Re-resolve the link's current status freshly (no stale/cached view; bfcache-safe). */
  reload(userInitiated = false): void {
    this.error.set(null);
    this.state.set('loading');
    if (userInitiated) {
      this.announcer.announce($localize`:@@status.loading.announce:Loading your application status…`, 'polite');
    }
    this.api.view(this.token).subscribe({
      next: (v: CandidateStatusView) => this.apply(v),
      error: (e: HttpErrorResponse) => this.state.set(this.viewErrorState(e))
    });
  }

  private apply(v: CandidateStatusView): void {
    this.view.set(v);
    switch (v.displayState) {
      case 'PUBLISHED': this.state.set('published'); break;
      case 'PAST_DATE': this.state.set('past_date'); break;
      case 'TERMINAL': this.state.set('terminal'); break;
      default: this.state.set('under_review'); break;
    }
  }

  private viewErrorState(e: HttpErrorResponse): StatusState {
    if (e.status === 429) { return 'rate_limited'; }
    if (!e.status) { return 'retryable_error'; } // network failure, not a token-state response
    return 'invalid'; // 404 (unknown / malformed / erased) — byte-identical not-an-oracle
  }

  /** Affirmative erasure request — fired ONLY from the explicit "Request data deletion" click. */
  requestErasure(): void {
    if (!this.token || this.erasureBusy()) { return; }
    this.erasureBusy.set(true);
    this.error.set(null);
    this.api.requestErasure(this.token).subscribe({
      next: () => {
        this.erasureBusy.set(false);
        this.erasureAck.set(true);
        this.announcer.announce(
          $localize`:@@status.erasure.ack.announce:We have received your data deletion request.`, 'polite');
      },
      error: (e: HttpErrorResponse) => {
        this.erasureBusy.set(false);
        if (e.status === 429) {
          this.error.set($localize`:@@status.err.rate:Too many attempts. Please wait a little while and try again.`);
          this.announcer.announce(
            $localize`:@@status.err.rate:Too many attempts. Please wait a little while and try again.`, 'assertive');
          return;
        }
        this.error.set($localize`:@@status.err.erasure:We couldn't submit your request — please try again.`);
        this.announcer.announce(
          $localize`:@@status.err.erasure:We couldn't submit your request — please try again.`, 'assertive');
      }
    });
  }
}
