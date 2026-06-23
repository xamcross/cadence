import { Component, ElementRef, ViewChild, effect, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { LiveAnnouncer } from '@angular/cdk/a11y';
import { InterestService } from './interest.service';

type RequestAccessState =
  | 'form'          // blank form ready to fill
  | 'submitting'    // POST in flight
  | 'confirmation'  // neutral thank-you (no oracle)
  | 'rate_limited'  // 429
  | 'error';        // network / unexpected failure

/**
 * F70 join / express-interest public form (Flow §IX — a no-login surface). PUBLIC, no session, no token. The
 * page captures an interest submission (name/email + optional organization/message) and POSTs it; the response
 * is a neutral confirmation that never reveals account existence (no-oracle, server-enforced). The page is
 * `noindex` (route `data: { seo: PRIVATE }`, R4) and holds NO PII/token in web storage — the form model lives
 * in memory only and is never written to localStorage/sessionStorage.
 *
 * Bot defenses (FR-002/R6): a hidden honeypot field `website` that humans never see/fill plus a server-side
 * minimum-fill-time gate; a tripped heuristic returns the identical neutral confirmation with no row written.
 *
 * Mobile-first + WCAG 2.2 AA: one focusable <h1 tabindex="-1"> per state with focus management (the F14/F30
 * pattern — focus moves to the heading on each transition, skipping the initial paint), an assertive live
 * region for transient validation errors, >=44px targets, all strings $localize-marked, and a visible privacy
 * notice stating the four GDPR elements (data collected, purpose, lawful basis, retention).
 */
@Component({
  selector: 'app-request-access',
  standalone: true,
  imports: [CommonModule, FormsModule],
  styleUrl: './request-access.component.scss',
  template: `
    <main class="request-access">
      <h1 #stateHeading tabindex="-1" class="state-heading">{{ heading() }}</h1>

      <ng-container [ngSwitch]="formView()">
        <section *ngSwitchCase="'form'">
          <p class="intro" i18n="@@interest.intro">
            Tell us a little about you and we'll review your request for access. No account is required.
          </p>

          <form (ngSubmit)="submit()" #f="ngForm" novalidate>
            <div class="field">
              <label for="name" i18n="@@interest.name.label">Your name</label>
              <input class="input" id="name" name="name" type="text" maxlength="200"
                     [(ngModel)]="name" required autocomplete="name" />
            </div>

            <div class="field">
              <label for="email" i18n="@@interest.email.label">Email</label>
              <input class="input" id="email" name="email" type="email" maxlength="254"
                     [(ngModel)]="email" required autocomplete="email" />
            </div>

            <div class="field">
              <label for="organization" i18n="@@interest.org.label">Organization (optional)</label>
              <input class="input" id="organization" name="organization" type="text" maxlength="200"
                     [(ngModel)]="organization" autocomplete="organization" />
            </div>

            <div class="field">
              <label for="message" i18n="@@interest.message.label">Message (optional)</label>
              <textarea class="input" id="message" name="message" rows="4" maxlength="2000"
                        [(ngModel)]="message"></textarea>
            </div>

            <!-- Honeypot: visually + programmatically hidden, off the tab order, not autofilled. A non-empty
                 value (only a bot fills it) is silently dropped server-side. aria-hidden so SRs skip it. -->
            <div class="hp" aria-hidden="true">
              <label for="website">Leave this field empty</label>
              <input id="website" name="website" type="text" tabindex="-1" autocomplete="off"
                     [(ngModel)]="website" />
            </div>

            <p class="error" role="alert" *ngIf="errorMsg()">{{ errorMsg() }}</p>

            <button type="submit" class="action submit btn btn--primary" [disabled]="submitting() || !name || !email"
                    i18n="@@interest.submit">Request access</button>
          </form>

          <section class="privacy" aria-labelledby="privacy-h">
            <h2 id="privacy-h" class="privacy__title" i18n="@@interest.privacy.title">How we use your information</h2>
            <ul class="privacy__list">
              <li class="privacy__collected" i18n="@@interest.privacy.collected">
                What we collect: your name, email, and any organization or message you choose to share.
              </li>
              <li class="privacy__purpose" i18n="@@interest.privacy.purpose">
                Why: solely to evaluate and respond to your request for access.
              </li>
              <li class="privacy__basis" i18n="@@interest.privacy.basis">
                Lawful basis: our legitimate interest in assessing prospective users.
              </li>
              <li class="privacy__retention" i18n="@@interest.privacy.retention">
                Retention: we keep your details only as long as needed to handle the request, then delete them.
              </li>
            </ul>
            <!-- 031-terms-privacy-notice (T020/C-LINK-2): full Privacy Notice link added to the existing
                 notice block (the 4-point summary above is retained, FR-009). Root-relative full-document
                 anchor to the static /privacy page (outside the SPA router). Token-free page, so no
                 target=_blank is required. -->
            <p class="privacy__more">
              <a class="privacy-link btn btn--link" href="/privacy" i18n="@@interest.privacy.link">Read our full Privacy Notice</a>
            </p>
          </section>
        </section>

        <section *ngSwitchCase="'submitting'" aria-busy="true">
          <p i18n="@@interest.submitting">Sending your request…</p>
        </section>

        <section *ngSwitchCase="'confirmation'">
          <p i18n="@@interest.confirmation.body">
            Thank you — your request has been received. If access is appropriate, an administrator will be in touch.
          </p>
        </section>

        <section *ngSwitchCase="'rate_limited'">
          <p i18n="@@interest.rate.body">Too many requests right now. Please wait a little while and try again.</p>
        </section>

        <section *ngSwitchCase="'error'">
          <p i18n="@@interest.error.body">We couldn't send your request.</p>
          <button type="button" class="action retry btn btn--outline" (click)="backToForm()" i18n="@@interest.error.action">Try again</button>
        </section>
      </ng-container>
    </main>
  `
})
export class RequestAccessComponent {
  private readonly api = inject(InterestService);
  private readonly announcer = inject(LiveAnnouncer);

  readonly state = signal<RequestAccessState>('form');
  readonly submitting = signal(false);
  readonly errorMsg = signal<string | null>(null);

  // Form model — held in memory ONLY; never persisted to web storage, never logged.
  name = '';
  email = '';
  organization = '';
  message = '';
  website = ''; // honeypot — stays empty for humans

  // Epoch-ms timestamp stamped when the blank form is first shown. Sent on submit so the backend min-fill
  // bot heuristic (FR-002/R6) can reject an impossibly fast (sub-min-fill) submission. A normal human fill
  // (well over the threshold) passes. Held in memory only — never persisted to web storage.
  private readonly formRenderedAtMillis = Date.now();

  @ViewChild('stateHeading') stateHeading?: ElementRef<HTMLHeadingElement>;

  constructor() {
    // Move focus to the state heading on each transition (skip the initial 'form' paint so the page does not
    // steal focus on load) — the F14/F30 focus-management pattern (no double-announce; the heading IS the
    // announcement, transient validation errors use the assertive live region instead).
    let first = true;
    effect(() => {
      const s = this.state();
      if (first) { first = false; return; }
      queueMicrotask(() => this.stateHeading?.nativeElement.focus());
      void s;
    });
  }

  /** The 'submitting' state reuses the form template (overlaid heading) — map it for the switch. */
  formView(): RequestAccessState {
    return this.state() === 'submitting' ? 'form' : this.state();
  }

  submit(): void {
    if (this.submitting() || !this.name || !this.email) {
      return;
    }
    this.submitting.set(true);
    this.errorMsg.set(null);
    this.state.set('submitting');
    this.api.submit({
      name: this.name,
      email: this.email,
      organization: this.organization || null,
      message: this.message || null,
      website: this.website,
      formRenderedAtMillis: this.formRenderedAtMillis
    }).subscribe({
      next: () => {
        this.submitting.set(false);
        this.state.set('confirmation');
      },
      error: (e: HttpErrorResponse) => {
        this.submitting.set(false);
        if (e.status === 400) {
          const msg = $localize`:@@interest.error.invalid:Please enter a valid name and email before submitting.`;
          this.errorMsg.set(msg);
          this.state.set('form');
          this.announcer.announce(msg, 'assertive');
        } else if (e.status === 429) {
          this.state.set('rate_limited');
        } else {
          this.state.set('error');
        }
      }
    });
  }

  backToForm(): void {
    this.errorMsg.set(null);
    this.state.set('form');
  }

  heading(): string {
    switch (this.state()) {
      case 'submitting': return $localize`:@@interest.h.submitting:Sending your request`;
      case 'confirmation': return $localize`:@@interest.h.confirmation:Request received`;
      case 'rate_limited': return $localize`:@@interest.h.rate:Please wait`;
      case 'error': return $localize`:@@interest.h.error:Something went wrong`;
      default: return $localize`:@@interest.h.form:Request access to Cadence`;
    }
  }
}
