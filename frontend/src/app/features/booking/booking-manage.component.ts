import { Component, ElementRef, OnInit, ViewChild, effect, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { ActivatedRoute, Router } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { LiveAnnouncer } from '@angular/cdk/a11y';
import { BookingService, BookingSlot, BookingView, OpenRescheduleResponse } from './booking.service';
import { ScheduleService } from '../schedule/schedule.service';
import { CandidateBrandingService } from '../../core/branding/candidate-branding.service';

type ManageState =
  | 'loading'
  | 'booked'        // A1 200 booked — Reschedule / Cancel actions
  | 'cancelled'     // A1/A3 terminal cancelled
  | 'rescheduled'   // A1 terminal rescheduled (an older link, superseded by a new booking)
  | 'reschedule_open' // A2 200 — slot-picker for the reschedule round
  | 'reschedule_done' // F13 confirm of the reschedule round succeeded
  | 'reschedule_empty'// A2 422 no_slots — nothing to offer, booking retained
  | 'cleanup_incomplete' // A3 cleanup_incomplete — cancelled, recruiter will confirm
  | 'expired'       // A1 410 — interview already in the past
  | 'invalid'       // 400 / missing token — byte-identical not-an-oracle
  | 'rate_limited'  // 429
  | 'retryable_error'; // network failure (status 0)

/**
 * F20 candidate booking-management page (Flow A3, §IX). Public, no login — the manage token rides the URL
 * query string and is held in memory only (never persisted to web storage / never logged). Renders the
 * confirmed interview time in the candidate's local zone (DST-correct via the 'zzz' zone token), times only
 * — never participant identities or the location. Offers Reschedule and Cancel actions, disabled per the
 * server's canReschedule/canCancel capabilities with a helpful (oracle-free) reason. Reschedule opens a
 * round and reuses the F13 slot-picker UI; confirming a slot calls the existing F13 confirm endpoint. Cancel
 * routes to an explicit affirmative confirmation step (the cancel POST is NEVER fired on page load — FR-012).
 *
 * Mobile-first + WCAG 2.2 AA: one focusable <h1 tabindex="-1"> per state (focus moves to it on every
 * transition so keyboard/SR users are not stranded — FR-024), an assertive live region for transient errors
 * (no double-announce: the heading conveys the state, transient errors the alert region), action/slot controls
 * carry full-date accessible names, and all strings are $localize-marked.
 */
@Component({
  selector: 'app-booking-manage',
  standalone: true,
  imports: [CommonModule, DatePipe],
  providers: [DatePipe],
  styleUrl: './booking-manage.component.scss',
  template: `
    <main class="booking">
      <h1 #stateHeading tabindex="-1" class="state-heading">{{ heading() }}</h1>

      <ng-container [ngSwitch]="state()">
        <section *ngSwitchCase="'loading'" aria-busy="true">
          <p i18n="@@booking.loading">Loading your interview…</p>
        </section>

        <!-- A1 booked: show the time + manage actions -->
        <section *ngSwitchCase="'booked'">
          <p class="confirmed" i18n="@@booking.booked.body">
            Your interview is confirmed for {{ bookedStart() | date: 'EEE, d MMM y, HH:mm zzz' }}.
          </p>
          <p class="zone" i18n="@@booking.zone">Shown in your local time zone ({{ localZone() }}).</p>

          <div class="actions">
            <button
              type="button"
              class="action reschedule"
              (click)="startReschedule()"
              [disabled]="!view()?.canReschedule || busy()"
              [attr.aria-label]="rescheduleLabel()">
              <ng-container i18n="@@booking.action.reschedule">Reschedule</ng-container>
            </button>
            <button
              type="button"
              class="action cancel"
              (click)="goToCancel()"
              [disabled]="!view()?.canCancel || busy()"
              [attr.aria-label]="cancelLabel()">
              <ng-container i18n="@@booking.action.cancel">Cancel interview</ng-container>
            </button>
          </div>

          <p *ngIf="!view()?.canReschedule" class="hint" i18n="@@booking.reschedule.disabled">
            Rescheduling online isn't available for this interview — please contact your recruiter to change the time.
          </p>
          <p *ngIf="view()?.canReschedule && (view()?.rescheduleRemaining ?? 0) > 0" class="hint" i18n="@@booking.reschedule.remaining">
            You can reschedule online {{ view()?.rescheduleRemaining }} more time(s).
          </p>
          <p *ngIf="!view()?.canCancel" class="hint" i18n="@@booking.cancel.disabled">
            Cancelling online isn't available for this interview — please contact your recruiter.
          </p>

          <ng-container *ngTemplateOutlet="help"></ng-container>
        </section>

        <!-- A2 reschedule round open: reuse the F13 slot-picker presentation (times only) -->
        <section *ngSwitchCase="'reschedule_open'">
          <p class="zone" i18n="@@booking.reschedule.zone">
            Times are shown in your local time zone ({{ localZone() }}); offered in {{ zoneHint() }}.
          </p>
          <ul class="slots">
            <li *ngFor="let s of slots()">
              <button
                type="button"
                class="slot"
                (click)="confirmReschedule(s)"
                [disabled]="busy()"
                [attr.aria-label]="slotLabel(s)">
                {{ s.start | date: 'EEE, d MMM y, HH:mm zzz' }}
              </button>
            </li>
          </ul>
          <button type="button" class="link-button" (click)="reload()" i18n="@@booking.reschedule.keep">
            Keep my current time
          </button>
        </section>

        <section *ngSwitchCase="'reschedule_done'">
          <p i18n="@@booking.reschedule.done.body">
            Your interview has been moved to {{ bookedStart() | date: 'EEE, d MMM y, HH:mm zzz' }}.
          </p>
        </section>

        <section *ngSwitchCase="'reschedule_empty'">
          <p i18n="@@booking.reschedule.empty.body">
            There are no alternative times available right now, so your current interview time stays booked. Your recruiter will follow up with new options.
          </p>
          <button type="button" class="link-button" (click)="reload()" i18n="@@booking.reschedule.back">
            Back to my booking
          </button>
          <ng-container *ngTemplateOutlet="help"></ng-container>
        </section>

        <section *ngSwitchCase="'cancelled'">
          <p i18n="@@booking.cancelled.body">Your interview has been cancelled. Thank you for letting us know.</p>
          <ng-container *ngTemplateOutlet="help"></ng-container>
        </section>

        <section *ngSwitchCase="'cleanup_incomplete'">
          <p i18n="@@booking.cleanup.body">Your interview has been cancelled. Your recruiter will confirm the details shortly.</p>
          <ng-container *ngTemplateOutlet="help"></ng-container>
        </section>

        <section *ngSwitchCase="'rescheduled'">
          <p i18n="@@booking.rescheduled.body">This interview has already been rescheduled. Please use your most recent link for the current details.</p>
          <ng-container *ngTemplateOutlet="help"></ng-container>
        </section>

        <section *ngSwitchCase="'expired'">
          <p i18n="@@booking.expired.body">This interview has already taken place. Please contact your recruiter if you need to follow up.</p>
          <ng-container *ngTemplateOutlet="help"></ng-container>
        </section>

        <section *ngSwitchCase="'invalid'">
          <p i18n="@@booking.invalid.body">This link is not valid.</p>
          <ng-container *ngTemplateOutlet="help"></ng-container>
        </section>

        <section *ngSwitchCase="'rate_limited'">
          <p i18n="@@booking.rate.body">Too many attempts. Please wait a little while and try again.</p>
          <ng-container *ngTemplateOutlet="help"></ng-container>
        </section>

        <section *ngSwitchCase="'retryable_error'">
          <p i18n="@@booking.retry.body">We couldn't load your interview details.</p>
          <button type="button" class="link-button" (click)="reload(true)" i18n="@@booking.retry.action">Try again</button>
          <ng-container *ngTemplateOutlet="help"></ng-container>
        </section>
      </ng-container>

      <!-- Transient, assertive errors (conflict / open-reschedule failure) — distinct from the heading so no
           double-announce. Always mounted (text-changes, not node-insertion) so screen readers announce it. -->
      <p class="err" role="alert" aria-live="assertive" [class.visually-hidden]="!error()">{{ error() }}</p>
    </main>

    <!-- Consistent help affordance: identical wording + placement across every "what next" state (WCAG 2.2 3.2.6). -->
    <ng-template #help>
      <p class="help" i18n="@@booking.help">Need help? Please contact your recruiter.</p>
    </ng-template>
  `
})
export class BookingManageComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly api = inject(BookingService);
  private readonly schedule = inject(ScheduleService);
  private readonly datePipe = inject(DatePipe);
  private readonly announcer = inject(LiveAnnouncer);
  private readonly host = inject(ElementRef);
  private readonly branding = inject(CandidateBrandingService);

  @ViewChild('stateHeading') private headingRef?: ElementRef<HTMLElement>;

  // Memory-only — never written to local/session storage, never logged.
  private token = '';
  private rescheduleToken = '';

  readonly state = signal<ManageState>('loading');
  readonly view = signal<BookingView | null>(null);
  readonly slots = signal<BookingSlot[]>([]);
  readonly zoneHint = signal('');
  readonly bookedStart = signal<string | null>(null);
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  readonly localZone = signal(Intl.DateTimeFormat().resolvedOptions().timeZone);

  constructor() {
    // FR-024: move focus to the current state's heading on every transition (skip the initial 'loading'
    // so the page does not steal focus on first paint). The heading text IS the SR state announcement, so
    // we do NOT also LiveAnnounce the title (avoids double-speak).
    effect(() => {
      const s = this.state();
      if (s === 'loading') return;
      queueMicrotask(() => this.headingRef?.nativeElement?.focus());
    });
  }

  readonly heading = (): string => {
    switch (this.state()) {
      case 'loading': return $localize`:@@booking.loading.title:Loading your interview…`;
      case 'booked': return $localize`:@@booking.booked.title:Manage your interview`;
      case 'reschedule_open': return $localize`:@@booking.reschedule.title:Pick a new interview time`;
      case 'reschedule_done': return $localize`:@@booking.reschedule.done.title:Your interview has been moved`;
      case 'reschedule_empty': return $localize`:@@booking.reschedule.empty.title:No alternative times right now`;
      case 'cancelled': return $localize`:@@booking.cancelled.title:Interview cancelled`;
      case 'cleanup_incomplete': return $localize`:@@booking.cleanup.title:Interview cancelled`;
      case 'rescheduled': return $localize`:@@booking.rescheduled.title:This interview was rescheduled`;
      case 'expired': return $localize`:@@booking.expired.title:This interview has already taken place`;
      case 'rate_limited': return $localize`:@@booking.rate.title:Too many attempts`;
      case 'retryable_error': return $localize`:@@booking.retry.title:Something went wrong`;
      default: return $localize`:@@booking.invalid.title:This link is not valid`;
    }
  };

  /** Accessible name for a reschedule slot button: the full local date + time (FR-007). */
  slotLabel(slot: BookingSlot): string {
    const formatted = this.datePipe.transform(slot.start, 'EEEE, d MMMM y, HH:mm zzzz') ?? slot.start;
    return $localize`:@@booking.slot.aria:Move interview to ${formatted}:slot:`;
  }

  /** Accessible name for the Reschedule action, conveying the booked time being changed. */
  rescheduleLabel(): string {
    const formatted = this.datePipe.transform(this.bookedStart(), 'EEEE, d MMMM y, HH:mm zzzz') ?? '';
    return $localize`:@@booking.reschedule.aria:Reschedule your interview on ${formatted}:when:`;
  }

  /** Accessible name for the Cancel action, conveying the booked time being cancelled. */
  cancelLabel(): string {
    const formatted = this.datePipe.transform(this.bookedStart(), 'EEEE, d MMMM y, HH:mm zzzz') ?? '';
    return $localize`:@@booking.cancel.aria:Cancel your interview on ${formatted}:when:`;
  }

  ngOnInit(): void {
    this.branding.applyAccent(this.host.nativeElement);
    // Re-resolve from the URL on every init (bfcache-safe), held in a memory-only field.
    this.token = this.route.snapshot.queryParamMap.get('token') ?? '';
    if (!this.token) { this.state.set('invalid'); return; }
    this.reload();
  }

  /** Re-resolve the booking's current state freshly (no stale/cached view). */
  reload(userInitiated = false): void {
    this.error.set(null);
    this.state.set('loading');
    if (userInitiated) {
      this.announcer.announce($localize`:@@booking.loading.announce:Loading your interview…`, 'polite');
    }
    this.api.view(this.token).subscribe({
      next: (v) => this.applyView(v),
      error: (e: HttpErrorResponse) => this.state.set(this.viewErrorState(e))
    });
  }

  private applyView(v: BookingView): void {
    this.view.set(v);
    this.bookedStart.set(v.bookedStart);
    if (v.status === 'cancelled') { this.state.set('cancelled'); return; }
    if (v.status === 'rescheduled') { this.state.set('rescheduled'); return; }
    this.state.set('booked');
  }

  private viewErrorState(e: HttpErrorResponse): ManageState {
    if (e.status === 429) return 'rate_limited';
    if (e.status === 410) return 'expired';
    if (!e.status) return 'retryable_error'; // network failure, not a token-state response
    return 'invalid';
  }

  /** A2: open a reschedule round, then render the F13 slot-picker for the returned token + slots. */
  startReschedule(): void {
    this.busy.set(true);
    this.error.set(null);
    this.api.openReschedule(this.token).subscribe({
      next: (r) => { this.busy.set(false); this.applyReschedule(r); },
      error: (e: HttpErrorResponse) => {
        this.busy.set(false);
        if (e.status === 429) { this.state.set('rate_limited'); return; }
        if (e.status === 410) { this.state.set('expired'); return; }
        if (e.status === 422) { this.state.set('reschedule_empty'); return; } // no_slots — booking retained
        const code = e.error?.error;
        if (code === 'cap_reached') {
          this.error.set($localize`:@@booking.err.cap:You've reached the reschedule limit online — please contact your recruiter.`);
        } else if (code === 'ineligible') {
          this.error.set($localize`:@@booking.err.ineligible:This interview can no longer be rescheduled online — please contact your recruiter.`);
        } else if (code === 'not_available') {
          // Byte-identical refusal (no GDPR oracle) — neutral message.
          this.error.set($localize`:@@booking.err.unavailable:We can't change this interview right now — please contact your recruiter.`);
        } else if (e.status === 400) {
          this.state.set('invalid');
        } else if (!e.status) {
          this.error.set($localize`:@@booking.err.network:We couldn't reach the server — please try again.`);
        } else {
          this.error.set($localize`:@@booking.err.generic:We couldn't open rescheduling — please try again.`);
        }
      }
    });
  }

  private applyReschedule(r: OpenRescheduleResponse): void {
    this.rescheduleToken = r.rescheduleToken; // memory-only
    this.zoneHint.set(r.zoneHint);
    const offered = r.slots ?? [];
    this.slots.set(offered);
    this.state.set(offered.length === 0 ? 'reschedule_empty' : 'reschedule_open');
  }

  /** Confirm a reschedule slot via the EXISTING F13 confirm endpoint (contract B2, mode=RESCHEDULE). */
  confirmReschedule(slot: BookingSlot): void {
    this.busy.set(true);
    this.error.set(null);
    this.schedule.confirm(this.rescheduleToken, slot.slotId).subscribe({
      next: (r) => {
        this.bookedStart.set(r.bookedStart);
        this.state.set('reschedule_done');
        this.busy.set(false);
      },
      error: (e: HttpErrorResponse) => {
        this.busy.set(false);
        if (e.status === 429) { this.state.set('rate_limited'); return; }
        if (e.status === 410) { this.state.set('expired'); return; }
        const code = e.error?.error;
        // On any reschedule-confirm failure the ORIGINAL booking remains intact (FR-009/FR-010).
        if (code === 'slot_taken' || code === 'slot_no_longer_available') {
          this.error.set($localize`:@@booking.err.taken:That time was just taken — please pick another.`);
          this.reloadRescheduleSlots();
        } else if (code === 'not_available') {
          this.error.set($localize`:@@booking.err.unavailable:We can't change this interview right now — please contact your recruiter.`);
        } else if (code === 'cleanup_incomplete') {
          this.error.set($localize`:@@booking.err.generic:We couldn't open rescheduling — please try again.`);
        } else if (e.status === 400) {
          // Reschedule token used / superseded — re-resolve the (intact) original booking.
          this.error.set($localize`:@@booking.err.expired:That rescheduling step is no longer available — your original time is still booked.`);
          this.reload();
        } else if (!e.status) {
          this.error.set($localize`:@@booking.err.network:We couldn't reach the server — please try again.`);
        } else {
          this.error.set($localize`:@@booking.err.generic:We couldn't open rescheduling — please try again.`);
        }
      }
    });
  }

  /** After a conflict in the reschedule round, re-fetch the offered slots; if none remain, retained-empty. */
  private reloadRescheduleSlots(): void {
    this.schedule.view(this.rescheduleToken).subscribe({
      next: (r) => {
        const remaining = r.slots ?? [];
        this.slots.set(remaining);
        this.state.set(remaining.length === 0 ? 'reschedule_empty' : 'reschedule_open');
      },
      error: () => this.reload() // reschedule round gone — fall back to the intact original booking
    });
  }

  /**
   * Navigate to the explicit affirmative cancel confirmation. The cancel POST is NOT fired here — it only
   * fires from the user's "Yes, cancel" click on the confirmation page (FR-012, no prefetch/scanner cancel).
   */
  goToCancel(): void {
    this.router.navigate(['/booking/cancel'], { queryParams: { token: this.token } });
  }
}
