import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';

/**
 * Shown when a member reaches a route their role does not permit (F02 US5). Internal screen — WCAG /
 * Lighthouse gates are N/A (backlog F50/F51 note) but strings are still externalized via i18n.
 */
@Component({
  selector: 'app-not-authorized',
  standalone: true,
  imports: [RouterLink],
  template: `
    <main class="na">
      <h1 i18n="@@notauthorized.title">You do not have access</h1>
      <p i18n="@@notauthorized.body">
        Your role does not have permission to view this page. If you think this is a mistake, contact
        your workspace administrator.
      </p>
      <a routerLink="/login" i18n="@@notauthorized.home">Back to sign in</a>
    </main>
  `,
  styles: [`
    .na { max-width: 32rem; margin: 4rem auto; padding: 1rem; }
    a { display: inline-block; margin-top: 1rem; min-height: 44px; }
  `]
})
export class NotAuthorizedComponent {}
