import { Component, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink } from '@angular/router';
import { filter } from 'rxjs';
import { NAV_GROUPS } from '../../core/nav/nav.config';

interface Crumb { readonly label: string; readonly link: string | null; }

/** Home > current-screen breadcrumb (workbench overhaul phase 4). Label resolves from the deepest route's
 *  data.breadcrumb first, then the nav config (longest path match). Hidden on the /app launchpad. */
@Component({
  selector: 'app-breadcrumbs',
  standalone: true,
  imports: [RouterLink],
  template: `
    @if (crumbs().length > 1) {
      <nav class="breadcrumbs" aria-label="Breadcrumb" i18n-aria-label="@@breadcrumb.aria">
        <ol class="breadcrumbs__list">
          @for (c of crumbs(); track c.label; let last = $last) {
            <li class="breadcrumbs__item">
              @if (c.link && !last) {
                <a [routerLink]="c.link">{{ c.label }}</a>
                <span class="breadcrumbs__sep" aria-hidden="true">/</span>
              } @else {
                <span aria-current="page">{{ c.label }}</span>
              }
            </li>
          }
        </ol>
      </nav>
    }
  `,
  styles: [`
    .breadcrumbs { padding-block: var(--space-3); }
    .breadcrumbs__list { list-style: none; display: flex; flex-wrap: wrap; gap: var(--space-2); margin: 0; padding: 0; font-size: var(--step--1); color: var(--ink-faint); }
    .breadcrumbs__item { display: inline-flex; gap: var(--space-2); align-items: center; }
    .breadcrumbs a { color: var(--ink-faint); text-decoration: none; }
    .breadcrumbs a:hover { text-decoration: underline; }
  `]
})
export class BreadcrumbsComponent {
  private readonly router = inject(Router);
  readonly crumbs = signal<Crumb[]>([]);
  private readonly home: Crumb = { label: $localize`:@@breadcrumb.home:Home`, link: '/app' };

  constructor() {
    // The component is created/destroyed by `@if (showChrome())`, so the router subscription must be
    // torn down with it — the constructor is a valid injection context for takeUntilDestroyed().
    this.router.events
      .pipe(filter((e) => e instanceof NavigationEnd), takeUntilDestroyed())
      .subscribe(() => this.rebuild());
    this.rebuild();
  }

  private rebuild(): void {
    const url = this.router.url.split(/[?#]/)[0];
    if (url === '/app' || url === '/' || url === '') { this.crumbs.set([this.home]); return; }
    const label = this.labelFor(url);
    this.crumbs.set(label ? [this.home, { label, link: null }] : [this.home]);
  }

  private labelFor(url: string): string | null {
    let r = this.router.routerState.snapshot.root;
    while (r.firstChild) { r = r.firstChild; }
    const fromData = r.data['breadcrumb'] as string | undefined;
    if (fromData) return fromData;
    let best: { label: string; len: number } | null = null;
    for (const g of NAV_GROUPS) {
      for (const it of g.items) {
        if ((url === it.path || url.startsWith(it.path + '/')) && (!best || it.path.length > best.len)) {
          best = { label: it.label, len: it.path.length };
        }
      }
    }
    return best ? best.label : null;
  }
}
