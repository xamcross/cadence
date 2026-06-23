import { Component, ElementRef, OnInit, ViewChild, effect, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { ActivatedRoute } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { LiveAnnouncer } from '@angular/cdk/a11y';
import { CandidateSlot, CandidateSlotsResponse, ScheduleService } from './schedule.service';
import { CandidateBrandingService } from '../../core/branding/candidate-branding.service';

type ScheduleState =
  | 'loading'
  | 'open'
  | 'empty'
  | 'booked'
  | 'expired'
  | 'invalid'
  | 'problem'
  | 'rate_limited'
  | 'retryable_error';

/**
 * F14 candidate self-scheduling slot-picker (§IX). Public, no login — the token rides the URL query
 * string and is held in memory only (never persisted to web storage). Renders offered times in the
 * candidate's local time zone with the offered zone labelled (DST-correct via the 'zzz' zone token),
 * times only — never participant identities. Mobile-first + WCAG 2.2 AA: one focusable heading per
 * state (focus moves to it on every transition so keyboard/SR users are not stranded — FR-024), an
 * assertive live announcement for transient conflict/booking errors (no double-announce: state titles
 * are conveyed by the focused heading, transient errors by the announcer), slot controls carry an
 * accessible name with the full local date+time, and all strings are $localize-marked.
 */
@Component({
  selector: 'app-schedule',
  standalone: true,
  imports: [CommonModule, DatePipe],
  providers: [DatePipe],
  styleUrl: './schedule.component.scss',
  template: `
    <main class="schedule">
      <h1 #stateHeading tabindex="-1" class="state-heading">{{ heading() }}</h1>

      <ng-container [ngSwitch]="state()">
        <section *ngSwitchCase="'loading'" aria-busy="true">
          <p i18n="@@schedule.loading">Loading available times…</p>
        </section>

        <section *ngSwitchCase="'open'">
          <p class="zone" i18n="@@schedule.zone">
            Times are shown in your local time zone ({{ localZone() }}); offered in {{ zoneHint() }}.
          </p>
          <ul class="slots">
            <li *ngFor="let s of slots()">
              <button
                type="button"
                class="slot btn btn--outline"
                (click)="confirm(s)"
                [disabled]="busy()"
                [attr.aria-label]="slotLabel(s)">
                {{ s.start | date: 'EEE, d MMM y, HH:mm zzz' }}
              </button>
            </li>
          </ul>
        </section>

        <section *ngSwitchCase="'empty'">
          <p i18n="@@schedule.empty.body">There are no interview times available right now. Your recruiter will follow up with new options.</p>
          <ng-container *ngTemplateOutlet="help"></ng-container>
        </section>

        <section *ngSwitchCase="'booked'">
          <p i18n="@@schedule.booked.body">Your interview is confirmed for {{ bookedStart() | date: 'EEE, d MMM y, HH:mm zzz' }}.</p>
        </section>

        <section *ngSwitchCase="'expired'">
          <p i18n="@@schedule.expired.body">This scheduling link has expired.</p>
          <ng-container *ngTemplateOutlet="help"></ng-container>
        </section>

        <section *ngSwitchCase="'invalid'">
          <p i18n="@@schedule.invalid.body">This scheduling link is not valid.</p>
          <ng-container *ngTemplateOutlet="help"></ng-container>
        </section>

        <section *ngSwitchCase="'problem'">
          <p i18n="@@schedule.problem.body">We hit a problem completing your booking. Your recruiter will follow up.</p>
          <ng-container *ngTemplateOutlet="help"></ng-container>
        </section>

        <section *ngSwitchCase="'rate_limited'">
          <p i18n="@@schedule.rate.body">Too many attempts. Please wait a little while and try again.</p>
          <ng-container *ngTemplateOutlet="help"></ng-container>
        </section>

        <section *ngSwitchCase="'retryable_error'">
          <p i18n="@@schedule.retry.body">We couldn't load your interview times.</p>
          <button type="button" class="retry btn btn--outline" (click)="reload(true)" i18n="@@schedule.retry.action">Try again</button>
          <ng-container *ngTemplateOutlet="help"></ng-container>
        </section>
      </ng-container>

      <!-- Transient, assertive errors (conflict / booking failure) — distinct from the heading so no double-announce.
           The region is ALWAYS mounted (text-changes, not node-insertion) so screen readers reliably announce it;
           when there's no error it is visually hidden but stays in the DOM and accessibility tree. -->
      <p class="err" role="alert" aria-live="assertive" [class.visually-hidden]="!error()">{{ error() }}</p>

      <!-- 031-terms-privacy-notice (T021/C-LINK-2/3): single inline Privacy Notice link. Root-relative
           full-document anchor to the static /privacy page (outside the SPA router). Opens in a NEW TAB
           (target=_blank + rel=noopener noreferrer, mandatory for reverse-tabnabbing) to preserve the
           candidate's in-memory token/state; the href carries no token. -->
      <p class="privacy-notice">
        <a class="privacy-link btn btn--link" href="/privacy" target="_blank" rel="noopener noreferrer"
           i18n="@@privacy.link">Privacy Notice</a>
      </p>
    </main>

    <!-- Consistent help affordance: identical wording + placement across every "what next" state (WCAG 2.2 3.2.6). -->
    <ng-template #help>
      <p class="help" i18n="@@schedule.help">Need help? Please contact your recruiter.</p>
    </ng-template>
  `
})
export class ScheduleComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly api = inject(ScheduleService);
  private readonly datePipe = inject(DatePipe);
  private readonly announcer = inject(LiveAnnouncer);
  private readonly host = inject(ElementRef);
  private readonly branding = inject(CandidateBrandingService);

  @ViewChild('stateHeading') private headingRef?: ElementRef<HTMLElement>;

  private token = '';
  readonly state = signal<ScheduleState>('loading');
  readonly slots = signal<CandidateSlot[]>([]);
  readonly zoneHint = signal('');
  readonly bookedStart = signal<string | null>(null);
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  readonly localZone = signal(Intl.DateTimeFormat().resolvedOptions().timeZone);

  constructor() {
    // FR-024: move focus to the current state's heading on every transition (skip the initial
    // 'loading' so the page does not steal focus on first paint). The heading text IS the state
    // announcement for screen readers, so we do NOT also LiveAnnounce the title (avoids double-speak).
    effect(() => {
      const s = this.state();
      if (s === 'loading') return;
      queueMicrotask(() => this.headingRef?.nativeElement?.focus());
    });
  }

  readonly heading = (): string => {
    switch (this.state()) {
      case 'loading': return $localize`:@@schedule.loading.title:Loading available times…`;
      case 'open': return $localize`:@@schedule.title:Pick your interview time`;
      case 'empty': return $localize`:@@schedule.empty.title:No times are available right now`;
      case 'booked': return $localize`:@@schedule.booked.title:You're booked`;
      case 'expired': return $localize`:@@schedule.expired.title:This link has expired`;
      case 'problem': return $localize`:@@schedule.problem.title:We couldn't finish your booking`;
      case 'rate_limited': return $localize`:@@schedule.rate.title:Too many attempts`;
      case 'retryable_error': return $localize`:@@schedule.retry.title:Something went wrong`;
      default: return $localize`:@@schedule.invalid.title:This link is not valid`;
    }
  };

  /** Accessible name for a slot button: the full local date + time (FR-007). */
  slotLabel(slot: CandidateSlot): string {
    const formatted = this.datePipe.transform(slot.start, 'EEEE, d MMMM y, HH:mm zzzz') ?? slot.start;
    return $localize`:@@schedule.slot.aria:Book interview on ${formatted}:slot:`;
  }

  ngOnInit(): void {
    this.branding.applyAccent(this.host.nativeElement);
    this.token = this.route.snapshot.queryParamMap.get('token') ?? '';
    if (!this.token) { this.state.set('invalid'); return; }
    this.reload();
  }

  /** Re-resolve the link's current state freshly (no stale/cached view; bfcache-safe). */
  reload(userInitiated = false): void {
    this.error.set(null);
    this.state.set('loading');
    // On an explicit user action (the retry button) focus is not moved, so politely announce that we
    // are loading — otherwise a screen-reader user gets silence until the result heading appears.
    if (userInitiated) {
      this.announcer.announce($localize`:@@schedule.loading.announce:Loading available times…`, 'polite');
    }
    this.api.view(this.token).subscribe({
      next: (r) => this.apply(r),
      error: (e: HttpErrorResponse) => this.state.set(this.viewErrorState(e))
    });
  }

  private apply(r: CandidateSlotsResponse): void {
    this.zoneHint.set(r.zoneHint);
    if (r.status === 'booked') {
      this.bookedStart.set(r.bookedStart);
      this.state.set('booked');
    } else if (!r.slots || r.slots.length === 0) {
      this.slots.set([]);
      this.state.set('empty');
    } else {
      this.slots.set(r.slots);
      this.state.set('open');
    }
  }

  private viewErrorState(e: HttpErrorResponse): ScheduleState {
    if (e.status === 429) return 'rate_limited';
    if (e.status === 410) return 'expired';
    if (!e.status) return 'retryable_error'; // network failure, not a token-state response
    return 'invalid';
  }

  confirm(slot: CandidateSlot): void {
    this.busy.set(true);
    this.error.set(null);
    this.api.confirm(this.token, slot.slotId).subscribe({
      next: (r) => {
        this.bookedStart.set(r.bookedStart);
        this.state.set('booked');
        this.busy.set(false);
      },
      error: (e: HttpErrorResponse) => {
        this.busy.set(false);
        if (e.status === 429) { this.state.set('rate_limited'); return; }
        if (e.status === 410) { this.state.set('expired'); return; }
        const code = e.error?.error;
        // The visible <p class="err" role="alert"> is the single assertive channel — setting error()
        // announces it once. Do NOT also LiveAnnounce the same text (that double-speaks on most SRs).
        if (code === 'slot_taken' || code === 'slot_no_longer_available') {
          this.error.set($localize`:@@schedule.err.taken:That time was just taken — please pick another.`);
          this.reloadRemaining();
        } else if (code === 'not_available') {
          this.state.set('invalid');
        } else if (code === 'cleanup_incomplete') {
          this.state.set('problem');
        } else if (e.status === 400) {
          // Used / superseded / unknown token at confirm — go to the shared invalid view, not a dead slot list.
          this.state.set('invalid');
        } else if (!e.status) {
          this.error.set($localize`:@@schedule.err.network:We couldn't reach the server — please try again.`);
        } else {
          this.error.set($localize`:@@schedule.err.generic:We could not complete the booking — please try again.`);
        }
      }
    });
  }

  /** After a conflict, reload the offered slots; if none remain, fall through to the empty state. */
  private reloadRemaining(): void {
    this.api.view(this.token).subscribe({
      next: (r) => {
        const remaining = r.slots ?? [];
        this.slots.set(remaining);
        this.state.set(remaining.length === 0 ? 'empty' : 'open');
      },
      error: (e: HttpErrorResponse) => this.state.set(this.viewErrorState(e))
    });
  }
}
