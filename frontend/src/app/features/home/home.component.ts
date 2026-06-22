import { Component, DestroyRef, OnInit, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';

/**
 * F60 (026-seo-aeo) public marketing home at `/` — the one indexable page.
 *
 * Anonymous-FIRST: marketing content renders immediately (the common visitor/crawler case) and is
 * NEVER gated on auth. A background me() probe redirects an already-signed-in member to /app; an
 * anonymous 401 must NOT bounce to /login (the auth interceptor exempts the home route), or every
 * crawler would be redirected off `/` and root indexing would break (FR-022/SC-005).
 *
 * The primary descriptive copy is ALSO authored statically in index.html inside <app-root> so a
 * no-JS crawler reads it; this component re-renders the same content for JS clients.
 */
@Component({
  selector: 'app-home',
  standalone: true,
  imports: [RouterLink],
  template: `
    <main class="home">
      <section class="hero">
        <p class="hero__eyebrow eyebrow eyebrow--rule reveal reveal-1" i18n="@@home.eyebrow">Interview scheduling &amp; candidate experience</p>
        <h1 class="reveal reveal-2" i18n="@@home.h1">Cadence — interview scheduling that respects candidates</h1>
        <p class="lede reveal reveal-2" i18n="@@home.lede">
          Schedule interviews, prevent no-shows, and keep every candidate informed — with no candidate
          account required. Cadence syncs with Google and Microsoft calendars and is GDPR-safe by design.
        </p>
        <div class="hero__actions reveal reveal-3">
          <a class="cta btn btn--primary" routerLink="/login" i18n="@@home.cta">Sign in</a>
          <a class="btn btn--ghost" href="#features" i18n="@@home.learn">See what it does</a>
        </div>
      </section>

      <section id="features" class="features" aria-labelledby="features-h">
        <p class="eyebrow eyebrow--rule" i18n="@@home.features.eyebrow">The product</p>
        <h2 id="features-h" i18n="@@home.features.title">What Cadence does</h2>
        <ul class="features__grid">
          <li class="card feature">
            <span class="feature__icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
                <rect x="3" y="4.5" width="18" height="16" rx="2.5"/><path d="M3 9h18M8 2.5v4M16 2.5v4"/><path d="m8.5 14.5 2.5 2.5 4.5-5"/>
              </svg>
            </span>
            <h3 class="feature__title" i18n="@@home.features.scheduling.title">Self-scheduling</h3>
            <p class="muted" i18n="@@home.features.scheduling">One-link self-scheduling, rescheduling, and cancellation.</p>
          </li>
          <li class="card feature">
            <span class="feature__icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
                <path d="M12 2.5 4 6v6c0 5 3.4 8.2 8 9.5 4.6-1.3 8-4.5 8-9.5V6Z"/><path d="m9 12 2.2 2.2L15.5 10"/>
              </svg>
            </span>
            <h3 class="feature__title" i18n="@@home.features.noshow.title">No-show defense</h3>
            <p class="muted" i18n="@@home.features.noshow">A no-show confirmation cascade with recruiter alerts.</p>
          </li>
          <li class="card feature">
            <span class="feature__icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
                <path d="M6 2.5h8l4 4V21a.5.5 0 0 1-.5.5h-11A.5.5 0 0 1 6 21Z"/><path d="M13.5 2.5V7h4.5M9 12h6M9 16h4"/>
              </svg>
            </span>
            <h3 class="feature__title" i18n="@@home.features.status.title">Candidate status page</h3>
            <p class="muted" i18n="@@home.features.status">A private candidate status page — live stage, next step, and dates.</p>
          </li>
          <li class="card feature">
            <span class="feature__icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
                <rect x="4.5" y="10.5" width="15" height="10" rx="2"/><path d="M8 10.5V7a4 4 0 0 1 8 0v3.5M12 14.5v2.5"/>
              </svg>
            </span>
            <h3 class="feature__title" i18n="@@home.features.gdpr.title">GDPR-safe by design</h3>
            <p class="muted" i18n="@@home.features.gdpr">Consent-recorded email and one-click right-to-erasure.</p>
          </li>
        </ul>
      </section>

      <section class="closing">
        <h2 i18n="@@home.closing.title">Built for candidate trust</h2>
        <p class="muted" i18n="@@home.closing.body">
          No candidate account, ever. Personal data is encrypted at rest and consent is recorded before
          any contact — so your candidate experience is fast and your compliance posture is calm.
        </p>
        <div class="hero__actions">
          <a class="btn btn--primary" routerLink="/login" i18n="@@home.cta2">Sign in to your workspace</a>
          <!-- F61: plain href (NOT routerLink) so it leaves the SPA and loads the static /resources library. -->
          <a class="btn btn--ghost resources-link" href="/resources/" i18n="@@home.resources">Recruiting resources</a>
        </div>
      </section>
    </main>
  `,
  styles: [`
    .hero {
      position: relative; max-width: 54rem; margin-inline: auto;
      padding: var(--space-16) var(--space-4) var(--space-12); text-align: center;
      /* ONE warm clay bloom only - indigo is interactive, never a decorative wash. */
      background: radial-gradient(52rem 26rem at 50% -14%, var(--clay-wash), transparent 70%);
    }
    .hero__eyebrow { justify-content: center; }
    .hero h1 { font-size: clamp(2.1rem, 7vw, var(--step-4)); margin-bottom: var(--space-5); }
    .lede { font-size: var(--step-1); color: var(--ink-muted); max-width: 38rem; margin-inline: auto; margin-bottom: var(--space-8); overflow-wrap: break-word; }
    .hero__actions { display: flex; flex-wrap: wrap; gap: var(--space-3); justify-content: center; }

    .features { max-width: var(--content-max); margin-inline: auto; padding: var(--space-12) var(--space-4); text-align: center; }
    .features > .eyebrow { justify-content: center; }
    .features > h2 { margin-bottom: var(--space-8); }
    .features__grid {
      list-style: none; margin: 0; padding: 0; text-align: left;
      display: grid; gap: var(--space-4); grid-template-columns: repeat(auto-fit, minmax(15rem, 1fr));
    }
    .feature { overflow-wrap: break-word; transition: border-color 0.15s ease, box-shadow 0.15s ease, transform 0.1s ease; }
    .feature:hover { border-color: var(--line-strong); box-shadow: var(--shadow-md); transform: translateY(-2px); }
    .feature__icon {
      display: inline-flex; align-items: center; justify-content: center;
      width: 2.75rem; height: 2.75rem; margin-bottom: var(--space-4);
      /* Quiet neutral tile - icons are ornament, not interactive, so no indigo here. */
      color: var(--ink-muted); background: var(--surface-sunken);
      border-radius: var(--radius); border: 1px solid var(--line);
    }
    .feature__title { font-size: var(--step-1); margin-bottom: var(--space-2); color: var(--ink); }

    .closing { max-width: 40rem; margin-inline: auto; padding: var(--space-8) var(--space-4) var(--space-16); text-align: center; }
    .closing > h2 { margin-bottom: var(--space-3); }
    .closing > .muted { margin-bottom: var(--space-6); }
  `]
})
export class HomeComponent implements OnInit {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  ngOnInit(): void {
    // Background probe only — never blocks rendering. A signed-in member is sent to the app; an
    // anonymous 401 is swallowed (the interceptor does not redirect on the home route).
    // takeUntilDestroyed: if the visitor navigates away before /me resolves, a late response must
    // not redirect from a destroyed component.
    this.auth.me().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => this.router.navigate(['/app']),
      error: () => {
        /* anonymous — stay on the marketing page */
      }
    });
  }
}
