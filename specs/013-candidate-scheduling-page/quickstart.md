# Quickstart: Candidate Scheduling Page (UX) (F14)

F14 hardens the F13 candidate slot-picker (`frontend/src/app/features/schedule/`) into a WCAG 2.2 AA, mobile-first, localization-ready page with **blocking** accessibility + performance gates. No backend change is expected.

## Prerequisites

- The F13 flow works end-to-end (recruiter sends link → candidate `/schedule?token=...` → confirm). Use the F13 `quickstart` to seed a live token for manual testing.
- Node 20 + the installed Chrome (Karma ChromeHeadless). **Do not** install Playwright (would download Chromium — Principle X).
- Frontend deps installed: `cd frontend && npm ci`. F14 adds one devDependency: `axe-core`.

## Run the page locally

```powershell
# Backend (needs a local mongo:7 per F13 quickstart)
cd backend ; ./gradlew bootRun
# Frontend (proxies /api to backend)
cd frontend ; ng serve
# Open the candidate page with a seeded token:
#   http://localhost:4200/schedule?token=<seeded-token>
```

Resize the browser to 375 px, 768 px, 1280 px and confirm: no horizontal scroll, ≥44 px tap targets, times in your local zone with the offered zone labelled.

## Run the gates

```powershell
# Accessibility (axe-core) + localization + WCAG-2.2 component tests (the frontend-test CI job):
cd frontend ; npx ng test --watch=false --browsers=ChromeHeadless

# Lighthouse on the REAL candidate route via the CI static+stub server (mirrors the `lighthouse` CI job):
cd frontend
npx ng build --configuration production
node lighthouse/serve-with-stub.mjs &     # serves dist + canned /api/candidate/scheduling/<demo> open-state
npx lhci autorun                            # asserts performance >= 0.85 AND LCP <= 2000ms (mobile preset, 4G)
```

## Manual accessibility audit checklist (SC-002a — the WCAG 2.2 AA criteria axe cannot fully verify)

Record pass/fail in the task notes at close. Drive each state via a seeded token / mocked responses.

- [ ] **Keyboard-only** (FR-006): Tab through every state; reach and operate every control; focus order logical; **visible focus** always (2.4.13).
- [ ] **Focus not obscured** (2.4.11, FR-021): with the page scrolled and any sticky header/confirm bar, the focused slot is never hidden behind it.
- [ ] **Focus management** (FR-024): on load→slots, confirm→success, conflict, and expiry, focus lands on the new heading/message (not lost to `<body>`).
- [ ] **Screen reader** (FR-007): state changes are announced (errors/conflicts assertively, info politely); each slot's accessible name reads the full date + time.
- [ ] **Target size** (2.5.8 / FR-003): slot + action controls ≥ 44 px.
- [ ] **Accessible authentication** (3.3.8 / FR-022): no CAPTCHA/puzzle anywhere, incl. the rate-limited state.
- [ ] **Consistent help** (3.2.6 / FR-023): "contact your recruiter" appears in the same place/wording across expired/invalid/empty/rate-limited.
- [ ] **Contrast & colour-independence** (FR-008): AA contrast; no info by colour alone.
- [ ] **200% text zoom** (FR-004): content/function intact, no clipping.
- [ ] **Reduced motion** (FR-004): with `prefers-reduced-motion: reduce`, no continuous animation (loading affordance is static).
- [ ] **RTL / long strings** (FR-012, SC-007): with `dir="rtl"` and long pseudo-localized strings, no overflow/truncation.

## Security/leakage checks (SC-010)

- [ ] `index.html` has `<meta name="referrer" content="no-referrer">`; `_headers` sets `Referrer-Policy: no-referrer`, restrictive CSP, `nosniff`.
- [ ] No third-party origin in `index.html`/`angular.json`/`styles.scss` (esp. **no `fonts.googleapis.com`** — fonts/icons self-hosted).
- [ ] Loading/using the page issues **no** off-origin request (check the network panel) — the URL token never appears in any `Referer`.
- [ ] The SPA writes **no** token/slot state to `localStorage`/`sessionStorage` (memory only); reopening/back-forward shows the current state, not a stale picker.
- [ ] Candidate API responses still carry `Cache-Control: no-store` (F13, not regressed); no candidate PII/token in any log (the F13 `Scheduling scan` CI step still green).

## Definition of Done (F14)

- [ ] axe-core 0 violations across all enumerated states (blocking, `frontend-test`).
- [ ] Lighthouse Performance ≥ 85 **and** LCP ≤ 2000 ms on `/schedule` (blocking, `lighthouse`).
- [ ] SC-002a manual checklist completed and recorded.
- [ ] All candidate strings externalized; RTL/long-string overflow test green.
- [ ] Referrer/CSP/no-third-party-asset/no-storage controls in place and verified.
- [ ] Multi-role sub-agent review (≥3) completed; findings applied or reported.
</content>
