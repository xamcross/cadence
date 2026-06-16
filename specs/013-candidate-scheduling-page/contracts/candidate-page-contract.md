# Contracts: Candidate Scheduling Page (UX) (F14)

F14 adds **no new HTTP API**. It consumes the F13 candidate contract (`specs/012-single-stage-scheduling/contracts/scheduling-api.md`, contract B) unchanged. Its "contracts" are therefore (A) the candidate-page **view-state contract**, (B) the **CI quality-gate contract**, and (C) the **security-headers / asset contract** — each is the verifiable interface F14 must satisfy and that downstream features (F20 reschedule, F30 status page) can reuse.

---

## A. View-state contract (component behaviour, asserted by Jasmine + axe)

For each backend outcome, the page MUST render exactly one state with the listed obligations. (See `data-model.md` §2 for the state machine.)

| Backend outcome | State | Obligations (all asserted) |
|---|---|---|
| `200` open, slots > 0 | OPEN | Slot list in local zone + zone label (FR-009); times only, no identities (FR-010); ≥44 px controls (FR-003); focus to heading; axe 0 (FR-005). |
| `200` open, slots = 0 | EMPTY | Calm "no times available — recruiter will follow up" (FR-011); axe 0. |
| `200` booked | BOOKED | `bookedStart` in local zone (FR-015); no re-book; axe 0. |
| `410` | EXPIRED | Distinct, friendly "expired — contact recruiter" (FR-013); axe 0. |
| `400` (used/superseded/unknown), `409 not_available`, `409 cleanup_incomplete` | INVALID (shared) | Single indistinguishable "not valid — contact recruiter" (cleanup variant: "we hit a problem, recruiter will follow up"); no existence/GDPR oracle (FR-014); axe 0. |
| `429` | RATE_LIMITED | "Too many attempts — please wait" (FR-016); no CAPTCHA/cognitive test (FR-022); recovers after window; axe 0. |
| `409 slot_taken` / `slot_no_longer_available` (on confirm) | back to OPEN/EMPTY | Inline "that time was just taken — pick another" (FR-017); re-load remaining; if none → EMPTY; assertive announce; axe 0. |
| network failure (no HTTP status) | RETRYABLE_ERROR | Distinct retryable message, not "invalid"; axe 0. |

Cross-state obligations: consistent "contact your recruiter" help text/placement (FR-023, 3.2.6); correct focus management + `aria-live` politeness on every transition (FR-024); keyboard-operable with visible focus (FR-006, 2.4.13); no token/PII in DOM text, ARIA names, or announcements (FR-019); token kept in memory only, never written to web storage (FR-026).

## B. CI quality-gate contract (blocking)

| Gate | Mechanism | Threshold | Job |
|---|---|---|---|
| Accessibility (FR-005, SC-002) | `axe-core` in Karma/Jasmine component specs, run on each enumerated state with tags `wcag2a, wcag2aa, wcag21a, wcag21aa, wcag22aa` | **0 violations** | `frontend-test` (`npx ng test`) |
| Performance (FR-001, SC-001) | Lighthouse CI against `/schedule?token=lighthouse-demo` via the CI static+stub server (SPA fallback required) | `categories:performance ≥ 0.85` (**error** level) | `lighthouse` |
| LCP (FR-001, SC-001) | Lighthouse CI `largest-contentful-paint` (ms) under the mobile-preset 4G throttle, with `numberOfRuns: 3` + median | `≤ 2000 ms` — introduced at **warn**/margin, promoted to **error** once the CI median is established (flakiness control) | `lighthouse` |
| A11y score (secondary signal) | Lighthouse `categories:accessibility` | high threshold (e.g. ≥ 0.95) — axe is the authoritative gate | `lighthouse` |
| WCAG 2.2 non-automatable (SC-002a) | Explicit Jasmine tests (target-size **≥44 px — sole automated check; axe `target-size` does NOT run under the WCAG tag set**, no-CAPTCHA absence, consistent-help, focus-management/live-region) + **manual-authoritative** steps for focus-not-obscured (2.4.11) & focus-appearance (2.4.13) — Karma layout math is unreliable, so those are not hard gates | tests green; manual checklist recorded | `frontend-test` + `quickstart.md` |
| Localization (FR-012, SC-007) | All candidate strings `i18n`/`$localize`-marked (extract-i18n / no-unmarked-text check) + RTL/long-string overflow component test (`scrollWidth ≤ clientWidth`) | green | `frontend-test` |

The `lighthouserc.json` `url` MUST be changed from `http://localhost:4200` to the candidate route; the LCP and (secondary) accessibility assertions MUST be added to the `assert.assertions` block.

## C. Security-headers / asset contract (Cloudflare Pages + index.html)

| Control | Where | Requirement (FR-025/FR-026/SC-010) |
|---|---|---|
| Referrer suppression | `index.html` `<meta name="referrer" content="no-referrer">` + `_headers` `Referrer-Policy: no-referrer` | The URL bearer token never leaves the origin via `Referer`. |
| No third-party assets | `index.html`, `angular.json`, `styles.scss` (+ CI grep guard for `googleapis`/`gstatic`) | None today (system fonts). Requirement is **do not introduce** a CDN font/asset; bundle Material icons locally if added. |
| CSP | `_headers` fully specified (NOT bare `default-src`) | `default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'` — validate against a real prod build (bare `default-src 'self'` breaks Angular's injected styles). |
| MIME sniffing | `_headers` `X-Content-Type-Options: nosniff` | Defence-in-depth. |
| No-store (no regress) | backend (F13) already sets `Cache-Control: no-store` on candidate responses | F14 asserts it still holds and the SPA caches no token state. |

**`_headers` placement (corrected)**: the file lives at `frontend/src/_headers` and `angular.json` MUST gain an assets glob `{ "glob": "_headers", "input": "src", "output": "/" }` so it lands at the served root `dist/cadence/browser/_headers` — the `application` builder has no `public/` glob, so a `frontend/public/_headers` would never be deployed. The `_headers` file is a Cloudflare Pages static config (LF endings), **not** a `.ps1`/`.cmd`/`.bat` (Principle V script rule N/A), and introduces **no** runtime service (C2 N/A).

---

## D. Reuse note (F20 / F30)

The view-state contract (A), the axe-per-state harness (B), and the headers/asset controls (C) are the candidate-facing presentation baseline that F20 (reschedule/cancel) and F30 (status page) inherit — those features add states to the same machine and reuse the same gates rather than re-deriving them.
</content>
