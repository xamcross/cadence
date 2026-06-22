import { Component, ElementRef, OnInit, ViewChild, effect, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { LiveAnnouncer } from '@angular/cdk/a11y';
import { FeedbackService, ScorecardFormView } from './feedback.service';
import { CandidateBrandingService } from '../../core/branding/candidate-branding.service';

type FeedbackState =
  | 'loading'         // resolving the token / fetching the form
  | 'form'            // blank scorecard ready to fill
  | 'submitted'       // thank-you
  | 'expired'         // past-TTL link (distinct, helpful)
  | 'invalid'         // used / unknown / invalidated — byte-identical not-an-oracle
  | 'rate_limited'    // 429
  | 'retryable_error'; // network failure (status 0)

/**
 * F32 interviewer scorecard page (Flow §IX — a candidate-class no-login surface). PUBLIC, no login — the token
 * rides the URL query string and is held in memory only (never persisted to web storage, never logged). On init
 * it re-resolves the token from the URL (bfcache-safe) and GETs the blank form; submission is an affirmative
 * POST. The page never displays previously submitted content (write-only). Mobile-first + WCAG 2.2 AA: one
 * focusable <h1 tabindex="-1"> per state with focus management (the F14/F30 pattern), an assertive live region
 * for transient validation errors, >=44px targets, all strings $localize-marked.
 */
@Component({
  selector: 'app-scorecard-page',
  standalone: true,
  imports: [CommonModule, FormsModule],
  styleUrl: './scorecard-page.component.scss',
  template: `
    <main class="scorecard">
      <h1 #stateHeading tabindex="-1" class="state-heading">{{ heading() }}</h1>

      <ng-container [ngSwitch]="state()">
        <section *ngSwitchCase="'loading'" aria-busy="true">
          <p i18n="@@feedback.loading">Loading the scorecard…</p>
        </section>

        <section *ngSwitchCase="'form'">
          <p class="intro" i18n="@@feedback.intro">{{ interviewLabel() }} — please share your feedback. No login is required.</p>

          <form (ngSubmit)="submit()" #f="ngForm" novalidate>
            <fieldset class="recommendation">
              <legend i18n="@@feedback.recommendation.legend">Overall recommendation</legend>
              <label *ngFor="let opt of recommendationOptions()" class="radio">
                <input type="radio" name="recommendation" [value]="opt" [(ngModel)]="recommendation" required />
                <span>{{ recommendationLabel(opt) }}</span>
              </label>
            </fieldset>

            <fieldset class="ratings" *ngIf="ratingDimensions().length">
              <legend i18n="@@feedback.ratings.legend">Competency ratings (optional, 1–4)</legend>
              <label *ngFor="let dim of ratingDimensions()" class="rating">
                <span class="dim">{{ dim }}</span>
                <select [ngModel]="ratingFor(dim)" (ngModelChange)="setRating(dim, $event)"
                        [name]="'rating-' + dim" [attr.aria-label]="dim">
                  <option [ngValue]="null" i18n="@@feedback.rating.none">—</option>
                  <option [ngValue]="1">1</option>
                  <option [ngValue]="2">2</option>
                  <option [ngValue]="3">3</option>
                  <option [ngValue]="4">4</option>
                </select>
              </label>
            </fieldset>

            <label class="comment">
              <span i18n="@@feedback.comment.label">Comments (optional)</span>
              <textarea name="comment" [(ngModel)]="comment" rows="5" maxlength="5000"></textarea>
            </label>

            <p class="error" role="alert" *ngIf="errorMsg()">{{ errorMsg() }}</p>

            <button type="submit" class="action submit" [disabled]="submitting() || !recommendation"
                    i18n="@@feedback.submit">Submit feedback</button>
          </form>
        </section>

        <section *ngSwitchCase="'submitted'">
          <p i18n="@@feedback.submitted.body">Thank you — your feedback has been recorded.</p>
        </section>

        <section *ngSwitchCase="'expired'">
          <p i18n="@@feedback.expired.body">This link has expired. Please contact your recruiter for a new one.</p>
        </section>

        <section *ngSwitchCase="'invalid'">
          <p i18n="@@feedback.invalid.body">This link is not valid.</p>
        </section>

        <section *ngSwitchCase="'rate_limited'">
          <p i18n="@@feedback.rate.body">Too many attempts. Please wait a little while and try again.</p>
        </section>

        <section *ngSwitchCase="'retryable_error'">
          <p i18n="@@feedback.retry.body">We couldn't load the scorecard.</p>
          <button type="button" class="action retry" (click)="reload()" i18n="@@feedback.retry.action">Try again</button>
        </section>
      </ng-container>
    </main>
  `
})
export class ScorecardPageComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly api = inject(FeedbackService);
  private readonly announcer = inject(LiveAnnouncer);
  private readonly host = inject(ElementRef);
  private readonly branding = inject(CandidateBrandingService);

  // The token is held in memory ONLY — never localStorage/sessionStorage, never logged.
  private token = '';

  readonly state = signal<FeedbackState>('loading');
  readonly interviewLabel = signal<string>('');
  readonly recommendationOptions = signal<string[]>([]);
  readonly ratingDimensions = signal<string[]>([]);
  readonly submitting = signal(false);
  readonly errorMsg = signal<string | null>(null);

  recommendation = '';
  comment = '';
  private readonly ratings = new Map<string, number | null>();

  @ViewChild('stateHeading') stateHeading?: ElementRef<HTMLHeadingElement>;

  constructor() {
    // Move focus to the state heading on each transition (skip the initial 'loading' so the page does not
    // steal focus on first paint) — the F14/F30 focus-management pattern (no double-announce).
    let first = true;
    effect(() => {
      const s = this.state();
      if (first) { first = false; return; }
      queueMicrotask(() => this.stateHeading?.nativeElement.focus());
      void s;
    });
  }

  ngOnInit(): void {
    this.branding.applyAccent(this.host.nativeElement);
    this.token = this.route.snapshot.queryParamMap.get('token') ?? '';
    this.reload();
  }

  reload(): void {
    this.state.set('loading');
    this.errorMsg.set(null);
    this.api.load(this.token).subscribe({
      next: (v: ScorecardFormView) => {
        if (v.state === 'FORM') {
          this.interviewLabel.set(v.interviewLabel ?? '');
          this.recommendationOptions.set(v.recommendationOptions ?? []);
          this.ratingDimensions.set(v.ratingDimensions ?? []);
          this.state.set('form');
        } else if (v.state === 'EXPIRED') {
          this.state.set('expired');
        } else {
          this.state.set('invalid');
        }
      },
      error: (e: HttpErrorResponse) => this.state.set(this.mapError(e))
    });
  }

  submit(): void {
    if (!this.recommendation || this.submitting()) {
      return;
    }
    this.submitting.set(true);
    this.errorMsg.set(null);
    const ratings = [...this.ratings.entries()]
      .filter(([, score]) => score != null)
      .map(([dimension, score]) => ({ dimension, score: score as number }));
    this.api.submit(this.token, { recommendation: this.recommendation, ratings, comment: this.comment || null })
      .subscribe({
        next: (r) => {
          this.submitting.set(false);
          if (r.state === 'SUBMITTED') {
            this.state.set('submitted');
          } else if (r.state === 'EXPIRED') {
            this.state.set('expired');
          } else {
            this.state.set('invalid');
          }
        },
        error: (e: HttpErrorResponse) => {
          this.submitting.set(false);
          if (e.status === 400) {
            const msg = $localize`:@@feedback.error.invalid:Please choose a recommendation before submitting.`;
            this.errorMsg.set(msg);
            this.announcer.announce(msg, 'assertive');
          } else {
            this.state.set(this.mapError(e));
          }
        }
      });
  }

  ratingFor(dim: string): number | null { return this.ratings.get(dim) ?? null; }
  setRating(dim: string, score: number | null): void { this.ratings.set(dim, score); }

  recommendationLabel(opt: string): string {
    switch (opt) {
      case 'STRONG_YES': return $localize`:@@feedback.rec.strongYes:Strong yes`;
      case 'YES': return $localize`:@@feedback.rec.yes:Yes`;
      case 'NO': return $localize`:@@feedback.rec.no:No`;
      case 'STRONG_NO': return $localize`:@@feedback.rec.strongNo:Strong no`;
      default: return opt;
    }
  }

  heading(): string {
    switch (this.state()) {
      case 'loading': return $localize`:@@feedback.h.loading:Interview feedback`;
      case 'form': return $localize`:@@feedback.h.form:Interview scorecard`;
      case 'submitted': return $localize`:@@feedback.h.submitted:Feedback received`;
      case 'expired': return $localize`:@@feedback.h.expired:Link expired`;
      case 'rate_limited': return $localize`:@@feedback.h.rate:Please wait`;
      case 'retryable_error': return $localize`:@@feedback.h.retry:Something went wrong`;
      default: return $localize`:@@feedback.h.invalid:Link not valid`;
    }
  }

  private mapError(e: HttpErrorResponse): FeedbackState {
    if (e.status === 429) { return 'rate_limited'; }
    if (e.status === 0) { return 'retryable_error'; }
    return 'invalid';
  }
}
