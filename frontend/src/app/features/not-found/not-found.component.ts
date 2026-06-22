import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';

/**
 * F60 (026-seo-aeo) wildcard `**` 404 page. Required once `/` is the public home: without it every
 * unknown/typo URL would be served as the indexable home (a soft-404 SEO + UX trap). Its route
 * carries `seo: PRIVATE`, so SeoService marks it `noindex,nofollow`.
 */
@Component({
  selector: 'app-not-found',
  standalone: true,
  imports: [RouterLink],
  template: `
    <main class="nf">
      <h1 i18n="@@notfound.title">Page not found</h1>
      <p i18n="@@notfound.body">The page you were looking for does not exist.</p>
      <a routerLink="/" i18n="@@notfound.home">Go to the homepage</a>
    </main>
  `,
  styles: [`
    .nf { max-width: 32rem; margin: 4rem auto; padding: 1rem; }
    a { display: inline-block; margin-top: 1rem; min-height: 44px; }
  `]
})
export class NotFoundComponent {}
