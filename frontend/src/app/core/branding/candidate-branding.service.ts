import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { environment } from '../../../environments/environment';

/**
 * Applies the public workspace brand colour to the candidate-facing pages (027-ui-design-system,
 * follow-up 3). The candidate pages style their accents with `var(--accent, <fallback>)`; this service
 * fetches the public branding and, when the colour is a valid hex, sets `--accent` on the page host so
 * each workspace's scheduling/status/booking/feedback pages pick up its colour.
 *
 * SECURITY: the brand colour is workspace-influenced and is written to a CSS custom property, so it is
 * validated against a strict 6-digit hex pattern first. Anything else (a colour name, a function, an
 * attempted value-injection like `red;}…`) is rejected and the design-system default is kept. Text
 * colours stay at the AA-safe `--accent-ink` default regardless of the brand colour, so contrast holds.
 */
@Injectable({ providedIn: 'root' })
export class CandidateBrandingService {
  private readonly http = inject(HttpClient);
  private readonly base = environment.apiBaseUrl;

  /** Strict 6-digit hex only — see the SECURITY note above. */
  static isHexColor(c: string | null | undefined): c is string {
    return !!c && /^#[0-9a-fA-F]{6}$/.test(c);
  }

  /** Set --accent on the host if the colour is a valid hex; otherwise leave the default untouched. */
  setAccent(host: HTMLElement, color: string | null | undefined): void {
    if (CandidateBrandingService.isHexColor(color)) {
      host.style.setProperty('--accent', color);
    }
  }

  /**
   * Fetch public branding and apply the accent. Best-effort: any failure (network, missing/invalid
   * colour) silently keeps the default — branding is decorative and never blocks the page.
   */
  applyAccent(host: HTMLElement): void {
    this.http.get<{ brandColor?: string }>(`${this.base}/public/workspace/branding`).subscribe({
      next: (b) => this.setAccent(host, b?.brandColor),
      error: () => { /* keep the design-system default accent */ }
    });
  }
}
