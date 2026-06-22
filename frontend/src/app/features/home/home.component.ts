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
        <p class="hero__eyebrow" i18n="@@home.eyebrow">Interview scheduling &amp; candidate experience</p>
        <h1 i18n="@@home.h1">Cadence — interview scheduling that respects candidates</h1>
        <p class="lede" i18n="@@home.lede">
          Schedule interviews, prevent no-shows, and keep every candidate informed — with no candidate
          account required. Cadence syncs with Google and Microsoft calendars and is GDPR-safe by design.
        </p>
        <div class="hero__actions">
          <a class="cta btn btn--primary" routerLink="/login" i18n="@@home.cta">Sign in</a>
          <a class="btn btn--ghost" href="#features" i18n="@@home.learn">See what it does</a>
        </div>
      </section>

      <section id="features" class="features" aria-labelledby="features-h">
        <h2 id="features-h" i18n="@@home.features.title">What Cadence does</h2>
        <ul class="features__grid">
          <li class="card feature">
            <h3 class="feature__title" i18n="@@home.features.scheduling.title">Self-scheduling</h3>
            <p class="muted" i18n="@@home.features.scheduling">One-link self-scheduling, rescheduling, and cancellation.</p>
          </li>
          <li class="card feature">
            <h3 class="feature__title" i18n="@@home.features.noshow.title">No-show defense</h3>
            <p class="muted" i18n="@@home.features.noshow">A no-show confirmation cascade with recruiter alerts.</p>
          </li>
          <li class="card feature">
            <h3 class="feature__title" i18n="@@home.features.status.title">Candidate status page</h3>
            <p class="muted" i18n="@@home.features.status">A private candidate status page — live stage, next step, and dates.</p>
          </li>
          <li class="card feature">
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
          <a class="btn btn--ghost resources-link" href="/resources" i18n="@@home.resources">Recruiting resources</a>
        </div>
      </section>
    </main>
  `,
  styles: [`
    .hero {
      max-width: 52rem; margin-inline: auto;
      padding: var(--space-16) var(--space-4) var(--space-12); text-align: center;
      background: radial-gradient(55rem 26rem at 50% -12%, var(--accent-wash), transparent 70%);
    }
    .hero__eyebrow {
      font-weight: 700; font-size: var(--step--1); letter-spacing: 0.08em; text-transform: uppercase;
      color: var(--accent-ink); margin-bottom: var(--space-4);
    }
    .hero h1 { font-size: clamp(2rem, 7vw, var(--step-4)); margin-bottom: var(--space-5); }
    .lede { font-size: var(--step-1); color: var(--ink-muted); max-width: 38rem; margin-inline: auto; margin-bottom: var(--space-8); overflow-wrap: break-word; }
    .hero__actions { display: flex; flex-wrap: wrap; gap: var(--space-3); justify-content: center; }

    .features { max-width: var(--content-max); margin-inline: auto; padding: var(--space-12) var(--space-4); }
    .features > h2 { text-align: center; margin-bottom: var(--space-8); }
    .features__grid {
      list-style: none; margin: 0; padding: 0;
      display: grid; gap: var(--space-4); grid-template-columns: repeat(auto-fit, minmax(15rem, 1fr));
    }
    .feature { overflow-wrap: break-word; }
    .feature__title { font-size: var(--step-1); margin-bottom: var(--space-2); color: var(--accent-ink); }

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
