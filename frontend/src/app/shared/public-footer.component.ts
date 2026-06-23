import { Component } from '@angular/core';

/**
 * Shared public footer (031-terms-privacy-notice, US3 / contract C-LINK-1). Renders clearly-labelled links
 * to the Terms & Conditions, Privacy Notice, and the marketing home.
 *
 * The legal pages live at /terms and /privacy as STATIC pre-rendered files OUTSIDE the Angular router (the
 * F61 static-content pipeline), so the links MUST be root-relative FULL-DOCUMENT anchors (href="/terms" /
 * href="/privacy" / href="/", leading slash required):
 *  - NOT routerLink - that would hit the SPA router and fall through to the wildcard ** -> NotFound.
 *  - NOT a relative href ("privacy") - that would mis-resolve against <base href="/"> on nested routes.
 *
 * Mounted INSIDE each public page component template (the marketing home today) - NEVER in AppComponent
 * (a global mount would leak the footer onto token cards and the authenticated shell).
 *
 * Accessible: a <footer> landmark, link labels externalised for i18n, and the .btn/.btn--link primitives
 * (>=44px touch target) reused from styles.scss (no new global CSS).
 */
@Component({
  selector: 'app-public-footer',
  standalone: true,
  template: `
    <footer class="public-footer" role="contentinfo">
      <nav class="public-footer__nav" aria-label="Legal and home" i18n-aria-label="@@footer.nav.aria">
        <a class="footer-link btn btn--link" href="/" i18n="@@footer.home">Home</a>
        <a class="footer-link btn btn--link" href="/terms" i18n="@@footer.terms">Terms &amp; Conditions</a>
        <a class="footer-link btn btn--link" href="/privacy" i18n="@@footer.privacy">Privacy Notice</a>
      </nav>
    </footer>
  `,
  styles: [`
    .public-footer {
      border-top: 1px solid var(--line);
      padding: var(--space-6) var(--space-4);
      margin-top: var(--space-12);
    }
    .public-footer__nav {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-2);
      justify-content: center;
      align-items: center;
      max-width: var(--content-max);
      margin-inline: auto;
    }
  `]
})
export class PublicFooterComponent {}
