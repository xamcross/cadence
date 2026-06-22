import { Component, inject, signal } from '@angular/core';
import { NavigationEnd, Router, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';
import { SeoService } from './core/seo/seo.service';
import { TopBarComponent } from './features/shell/top-bar.component';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, TopBarComponent],
  templateUrl: './app.component.html',
  styleUrl: './app.component.scss'
})
export class AppComponent {
  title = 'cadence';

  private readonly router = inject(Router);

  // 027-ui-design-system: show the persistent top bar only on authenticated internal routes
  // (data.shell === true). Public/candidate/auth routes render bare (no chrome, no nav leak).
  readonly showChrome = signal(false);

  // F60 (026-seo-aeo): wire per-route SEO in the constructor so the initial (enabledNonBlocking)
  // navigation is covered — SeoService subscribes then applies for the current snapshot.
  constructor() {
    inject(SeoService).init();
    this.router.events.pipe(filter((e) => e instanceof NavigationEnd)).subscribe(() => this.updateChrome());
    this.updateChrome();
  }

  private updateChrome(): void {
    let r = this.router.routerState.snapshot.root;
    while (r.firstChild) { r = r.firstChild; }
    this.showChrome.set(r.data['shell'] === true);
  }
}
