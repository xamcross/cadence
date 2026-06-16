import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { ActivatedRoute } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { CandidateSlot, CandidateSlotsResponse, ScheduleService } from './schedule.service';

/**
 * F13 candidate slot-picker (§II). Public, no login — the token rides the URL query string. Renders the
 * offered times in the candidate's local zone with the offer zone labelled, and confirms a pick. Functional
 * for F13; the formal WCAG 2.2 AA axe gate, Lighthouse >= 85 budget, and full localization sign-off are F14
 * (this ships with $localize markers + time-zone-aware rendering + no-login so F14 hardens, not rewrites).
 */
@Component({
  selector: 'app-schedule',
  standalone: true,
  imports: [CommonModule, DatePipe],
  template: `
    <main class="schedule">
      <ng-container [ngSwitch]="state()">
        <section *ngSwitchCase="'loading'"><p i18n="@@schedule.loading">Loading available times…</p></section>

        <section *ngSwitchCase="'open'">
          <h1 i18n="@@schedule.title">Pick your interview time</h1>
          <p i18n="@@schedule.zone">Times shown in your local time zone (offered in {{ zoneHint() }}).</p>
          <ul class="slots">
            <li *ngFor="let s of slots()">
              <button type="button" (click)="confirm(s)" [disabled]="busy()">
                {{ s.start | date: 'EEE, d MMM y, HH:mm' }}
              </button>
            </li>
          </ul>
          <p class="err" *ngIf="error()">{{ error() }}</p>
        </section>

        <section *ngSwitchCase="'booked'">
          <h1 i18n="@@schedule.booked.title">You're booked</h1>
          <p i18n="@@schedule.booked.body">Your interview is confirmed for {{ bookedStart() | date: 'EEE, d MMM y, HH:mm' }}.</p>
        </section>

        <section *ngSwitchCase="'expired'">
          <h1 i18n="@@schedule.expired.title">This link has expired</h1>
          <p i18n="@@schedule.expired.body">Please contact your recruiter for a new scheduling link.</p>
        </section>

        <section *ngSwitchCase="'invalid'">
          <h1 i18n="@@schedule.invalid.title">This link is not valid</h1>
          <p i18n="@@schedule.invalid.body">Please contact your recruiter.</p>
        </section>
      </ng-container>
    </main>
  `
})
export class ScheduleComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly api = inject(ScheduleService);

  private token = '';
  readonly state = signal<'loading' | 'open' | 'booked' | 'expired' | 'invalid'>('loading');
  readonly slots = signal<CandidateSlot[]>([]);
  readonly zoneHint = signal('');
  readonly bookedStart = signal<string | null>(null);
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);

  ngOnInit(): void {
    this.token = this.route.snapshot.queryParamMap.get('token') ?? '';
    if (!this.token) { this.state.set('invalid'); return; }
    this.load();
  }

  private load(): void {
    this.api.view(this.token).subscribe({
      next: (r) => this.apply(r),
      error: (e: HttpErrorResponse) => this.state.set(e.status === 410 ? 'expired' : 'invalid')
    });
  }

  private apply(r: CandidateSlotsResponse): void {
    this.zoneHint.set(r.zoneHint);
    if (r.status === 'booked') {
      this.bookedStart.set(r.bookedStart);
      this.state.set('booked');
    } else {
      this.slots.set(r.slots);
      this.state.set('open');
    }
  }

  confirm(slot: CandidateSlot): void {
    this.busy.set(true);
    this.error.set(null);
    this.api.confirm(this.token, slot.slotId).subscribe({
      next: (r) => { this.bookedStart.set(r.bookedStart); this.state.set('booked'); this.busy.set(false); },
      error: (e: HttpErrorResponse) => {
        this.busy.set(false);
        if (e.status === 410) { this.state.set('expired'); return; }
        const code = e.error?.error;
        if (code === 'slot_taken' || code === 'slot_no_longer_available') {
          this.error.set($localize`:@@schedule.err.taken:That time was just taken — please pick another.`);
          this.load();
        } else if (code === 'not_available') {
          this.state.set('invalid');
        } else {
          this.error.set($localize`:@@schedule.err.generic:We could not complete the booking — please try again.`);
        }
      }
    });
  }
}
