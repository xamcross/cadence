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
      <h1 i18n="@@home.h1">Cadence — interview scheduling that respects candidates</h1>
      <p class="lede" i18n="@@home.lede">
        Schedule interviews, prevent no-shows, and keep every candidate informed — with no candidate
        account required. Cadence syncs with Google and Microsoft calendars and is GDPR-safe by design.
      </p>

      <a class="cta" routerLink="/login" i18n="@@home.cta">Sign in</a>

      <section class="features" aria-labelledby="features-h">
        <h2 id="features-h" i18n="@@home.features.title">What Cadence does</h2>
        <ul>
          <li i18n="@@home.features.scheduling">One-link self-scheduling, rescheduling, and cancellation.</li>
          <li i18n="@@home.features.noshow">A no-show confirmation cascade with recruiter alerts.</li>
          <li i18n="@@home.features.status">A private candidate status page — live stage, next step, and dates.</li>
          <li i18n="@@home.features.gdpr">Consent-recorded email and one-click right-to-erasure.</li>
        </ul>
      </section>
    </main>
  `,
  styles: [`
    .home { max-width: 48rem; margin: 0 auto; padding: 2rem 1rem; }
    h1 { font-size: 1.75rem; line-height: 1.25; }
    .lede { font-size: 1.1rem; color: #333; }
    .cta { display: inline-block; margin: 1rem 0; padding: 0.75rem 1.25rem; min-height: 44px;
           font-weight: 600; background: #1f2937; color: #fff; border-radius: 6px; text-decoration: none; }
    .features ul { line-height: 1.8; }
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
