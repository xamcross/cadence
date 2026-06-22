import { Component, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { SeoService } from './core/seo/seo.service';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet],
  templateUrl: './app.component.html',
  styleUrl: './app.component.scss'
})
export class AppComponent {
  title = 'cadence';

  // F60 (026-seo-aeo): wire per-route SEO in the constructor so the initial (enabledNonBlocking)
  // navigation is covered — SeoService subscribes then applies for the current snapshot.
  constructor() {
    inject(SeoService).init();
  }
}
