import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { NAV_GROUPS, NavGroup } from '../../core/nav/nav.config';

/** Persistent role-aware navigation (workbench overhaul phase 4). Sticky rail on desktop; an
 *  accordion under a toggle on mobile (no drawer/overlay/focus-trap). Rendered by AppComponent on
 *  data.shell routes only. Server + roleGuard remain the boundary; this only hides links a role can't use. */
@Component({
  selector: 'app-side-nav',
  standalone: true,
  imports: [RouterLink, RouterLinkActive],
  template: `
    @if (member()) {
      <button type="button" class="side-nav__toggle btn btn--outline" (click)="toggle()"
              [attr.aria-expanded]="open()" aria-controls="primary-nav">
        <svg width="16" height="16" viewBox="0 0 16 16" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round">
          <line x1="2.5" y1="4" x2="13.5" y2="4"/><line x1="2.5" y1="8" x2="13.5" y2="8"/><line x1="2.5" y1="12" x2="13.5" y2="12"/>
        </svg>
        <span i18n="@@nav.toggle">Menu</span>
      </button>
      <nav id="primary-nav" class="side-nav__panel" [class.side-nav__panel--open]="open()"
           aria-label="Primary" i18n-aria-label="@@nav.primary">
        @for (group of groups(); track group.title) {
          <div class="side-nav__group">
            <p class="side-nav__group-title eyebrow eyebrow--quiet">{{ group.title }}</p>
            <ul class="side-nav__list">
              @for (item of group.items; track item.path) {
                <li>
                  <a class="side-nav__link" [routerLink]="item.path"
                     routerLinkActive="is-active" ariaCurrentWhenActive="page"
                     (click)="close()">{{ item.label }}</a>
                </li>
              }
            </ul>
          </div>
        }
      </nav>
    }
  `,
  styles: [`
    :host { display: block; }
    .side-nav__toggle { width: 100%; justify-content: center; margin-bottom: var(--space-3); gap: var(--space-2); }
    .side-nav__panel { display: none; }
    .side-nav__panel--open { display: block; }
    .side-nav__group { margin-bottom: var(--space-5); }
    .side-nav__group-title { margin-bottom: var(--space-2); }
    .side-nav__list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 2px; }
    .side-nav__link {
      display: block; padding: var(--space-2) var(--space-3); border-radius: var(--radius-sm);
      color: var(--ink); text-decoration: none; font-size: var(--step--1);
    }
    .side-nav__link:hover { background: var(--surface-sunken); }
    .side-nav__link.is-active { background: var(--accent-wash); color: var(--accent-ink); font-weight: 600; }
    @media (min-width: 48rem) {
      :host { position: sticky; top: 4rem; align-self: flex-start; flex: none; width: 15rem; }
      .side-nav__toggle { display: none; }
      .side-nav__panel { display: block; }   /* always open on desktop */
    }
  `]
})
export class SideNavComponent {
  private readonly auth = inject(AuthService);
  readonly member = toSignal(this.auth.member$, { initialValue: null });
  readonly open = signal(false);

  readonly groups = computed<NavGroup[]>(() => {
    const role = this.member()?.role;
    if (!role) return [];
    return NAV_GROUPS
      .map((g) => ({ title: g.title, items: g.items.filter((i) => i.roles.includes(role)) }))
      .filter((g) => g.items.length > 0);
  });

  toggle(): void { this.open.update((v) => !v); }
  close(): void { this.open.set(false); }
}
