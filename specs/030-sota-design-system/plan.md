# 030 — SOTA Design-System Refresh & Form-Control Alignment

Branch: `030-sota-design-system` · Frontend-only (no backend/Mongo/Mongock change).

## Goal
Push the existing **"Editorial Calm"** system (027) to a state-of-the-art bar and **align every
input, button, label, select, textarea, table and card to one system**, removing ad-hoc per-component
form styling and hardcoded color/spacing literals.

## Hard constraints (must not regress)
- **CSP `font-src 'self'`** — no third-party/CDN font. New fonts must be self-hosted woff2. (We cannot
  download binaries in this environment, so we elevate the existing self-hosted **Fraunces** + system
  stack rather than bolt on a fetched font.)
- **No UI framework added** (Material/Tailwind). Rationale: a mature codebase with strict CSP, a
  2 kB/4 kB per-component style budget, axe + Lighthouse gates, and 250+ specs. A hand-crafted
  token system is what top-tier products (Linear/Stripe/Vercel) actually ship and is lower-risk +
  lower-weight than a library. (Decision recorded for reviewer challenge.)
- **Per-component style budget**: warn 2 kB / **error 4 kB**. ⇒ grow the *global* `styles.scss`
  (unbudgeted) with shared primitives; *shrink* per-component CSS.
- **Candidate-token pages** (`/schedule /booking /confirm /status /feedback`): Lighthouse ≥ 85,
  WCAG 2.2 AA (axe 0), system font only (already `--font-display: var(--font-body)`), token never
  leaks off-origin. **`--accent` is runtime-overridden per workspace brand** ⇒ buttons must be
  **accent-on-border + wash fill + `--accent-ink` text, NEVER white-on-accent** (contrast-safe for
  any brand hue). This is the existing "review #2" rule and is load-bearing.
- i18n (`$localize`), reduced-motion, ≥44 px targets, RTL logical properties — all preserved.

## Two-voice discipline (kept)
- **Entry/brand** (home, login, invite, reset, `/app` launchpad, request-access): Fraunces display,
  clay masthead, warm hero wash — may use the fixed Cadence indigo `.btn--primary` (white-on-indigo
  is AA-safe because the indigo is fixed here).
- **Workbench** (pipeline, dashboards, admin, templates, calendar): system sans, dense, quiet — no
  display serif, no atmosphere. Uses the same primitives but the quiet variants.
- **Candidate-token**: system sans, perf-safe, **brand-safe buttons only**.

## Phase A — Elevate global `styles.scss` (the source of truth)
Additive token + primitive work (no breaking renames of existing `.btn/.card/.field/.input/.badge`):
1. **Tokens**: motion-duration + easing tokens; a focus-ring token; a z-index scale; one refined
   elevation set (already warm-tinted — tighten). Add `--brand-safe` button semantics.
2. **Form controls — one system**:
   - `.input` already exists → extend to `select.input`, `textarea.input` (min-height, resize-y),
     consistent 44 px, refined focus glow (`0 0 0 3px var(--accent-wash)`), `:disabled`, invalid
     (`[aria-invalid=true]`) state.
   - `.field` + `.field__label` / `.field__hint` / `.field__error` (replaces ad-hoc `> label`/error
     markup; keep back-compat `.field > label`).
   - `.check` / `.radio-row` (≥44 px control rows, used by scorecard).
   - `.segmented` + `.segmented__btn` (replaces dashboard `window-btn`; ARIA pressed pattern).
3. **Buttons**: keep `.btn` + `--primary/ghost/outline/danger`; add `--sm`, `--block`, `--icon`,
   and **`.btn--brand`** = the candidate-safe accent-on-border+wash variant (so candidate pages stop
   hand-rolling it). Refine primary (subtle top highlight + crisper hover lift).
4. **Layout/workbench primitives**: `.page` + `.page__head` (eyebrow→h1→actions header used by
   dashboard/pipeline/admin), `.toolbar` (filter bars), `.table` (quiet dense data table:
   sticky head, hairline rows, numeric alignment), `.empty` state, `.alert`/`.callout` (info/ok/
   warn/danger), `.spinner`/`.skeleton` shimmer (reduced-motion safe).
5. **Aesthetic polish** (restrained, voice-scoped): a warm SVG-noise grain on entry hero washes
   (CSP `img-src 'self' data:` allows it), refined link underline, refined `::selection`. **None of
   this applies on candidate-token routes** (scoped off, perf).

## Phase B — Align candidate-token pages → primitives
`schedule`, `booking-manage`, `cancel-confirm`, `confirm-attendance`, `candidate-status`,
`scorecard-page`, `request-access`. Replace local `.input/.action/.slot/.field/.err` + hardcoded
hexes with `.input`, `.field`, `.btn .btn--brand`, `.btn--danger-soft`, token colors
(`--ink/--ink-muted/--danger/--ok`). Keep their mobile-first grids + perf scoping. Preserve the
brand-on-border contrast rule via `.btn--brand`. Net: large CSS deletion, fewer hexes → 0.

## Phase C — Align workbench components → primitives
`dashboard` (→ `.page`,`.segmented`,`.btn`,`.table`/metrics), `pipeline-list` + `candidate-timeline`,
`members`, `requisitions`, `workspace-settings` + `setup-wizard`, `gdpr/*` (4), `interest-requests`,
`ats`, `csv-import`, `interview-templates`, `email-templates`, `scheduling`, `calendar-connections`.
Replace ad-hoc buttons/inputs/tables/headers with primitives; purge hardcoded rem-spacing → `--space-*`
and hex → tokens. Keep each component's domain-specific layout.

## Phase D — Polish entry pages
`home`, `login`, `shell` launchpad, `top-bar`, auth (`accept-invite/reset/reset-confirm`),
`not-found`, `not-authorized` — adopt new polish tokens; verify the masthead signature is crisp.

## Verification
- `ng build --configuration production` clean (per-component budgets respected).
- `ng test` green (watch for specs asserting old class names / inline styles).
- axe specs 0 violations across candidate states; ≥44 px retained.
- Lighthouse stub run for `/schedule` (perf ≥ 85) unaffected.
- CI guards: no `googleapis.com/gstatic.com`/font-CDN literal; CSP unchanged.

## Review gates (subagent, 2 loops)
- **Pre-implementation**: design/UX critic, accessibility (WCAG 2.2), security/CSP+token-leak,
  frontend-architecture (Angular/budget/perf). Reconcile before coding.
- **Post-implementation**: same roles re-review the diff. Reconcile, then verify build+test.

---

## Reconciliation (v2) — decisions after the 4-reviewer pre-implementation panel
All four returned APPROVE-WITH-CHANGES (no blockers). Decisions folded in:

**Contrast / brand-override (a11y is authoritative; corrects the design reviewer's `color-mix` ring):**
- Confirmed via `candidate-branding.service.ts`: ONLY `--accent` is runtime-overridden (on the candidate
  host); `--accent-ink` (#11337a) + `--accent-wash` (#eef3fe) are FIXED. This is the load-bearing safety.
- **Focus ring = a FIXED token** `--focus-ring: var(--accent-ink)`, used by the global `:focus-visible`.
  NOT `--accent`-derived (a pale/yellow brand accent → invisible ring fails 1.4.11/2.4.13). Lets Phase B
  DELETE the per-component candidate focus overrides.
- **`.btn--brand`** (candidate-safe filled primary): `background: var(--accent-wash)` (fixed) +
  `border: 1.5px solid var(--accent)` (brand) + `color: var(--accent-ink)` (fixed). NEVER white-on-accent,
  NEVER ink/wash derived from `--accent`. `.btn--outline` is already brand-safe (bordered secondary).
  Candidate slot lists → `.btn .btn--outline`; primary action → `.btn .btn--brand`; cancel →
  `.btn .btn--danger-soft` (danger border + danger text + white/danger-wash, never white-on-danger).
- **Adversarial-hue axe test**: run candidate pages with `--accent` = `#ffff00`/`#ffffff`/`#000000` and
  assert axe color-contrast 0 on `.btn--brand`/`.slot` + focus ring.

**Forms / semantics (a11y):** `.field` mandates real `<label for>`; `.field__error` has an id referenced
by the control's `aria-describedby` (with `.field__hint` id when both present); `aria-invalid` set only
when invalid (drives the `[aria-invalid=true]` style); required backed by attribute, not asterisk-only;
never color-only. `.check`/`.radio-row` = label-wrapped, whole row ≥44px. `.segmented` = toggle-button
`aria-pressed` group (not role=radio), active state by fill+weight not color-only. `.table` = native
`<table>` + `<th scope>`, status via `.badge` text not color-only; `scroll-margin-top` on focusable rows
under the sticky head (2.4.11). `.skeleton` shimmer gated in `prefers-reduced-motion: no-preference`.

**Craft / signature (design) — folded into a new "Phase A.6 Signature & Craft":**
- A named **`.masthead`** composite (eyebrow + clay tick + h1, fixed rhythm) reproduced identically.
- A mono **numbering motif** `.kicker-index` (`01 / 04`) on launchpad groups + home features.
- **Per-step type tracking** (not one global `-0.015em`): hero tighter, `h3` ~0; raise display h1.
- **Voice-scoped elevation**: workbench separates with hairlines + caps at `--shadow-sm`; entry gets
  `--shadow-md/lg`. One shared **`.lift-card`** hover recipe, reduced-motion gated at `transform:none`.
- **One signature easing** `--ease-out-expo: cubic-bezier(0.2,0.7,0.2,1)` + `--motion-fast/slow` tokens,
  consumed by every transition/animation (so the global reduced-motion block catches them).
- **Serif + `tabular-nums` metric numbers** (dashboard) — distinctive, on-brand, free.
- Keep the **mono eyebrow in the workbench** `.page__head` so it stays recognizably Cadence.
- **Three paper tiers** + an inset top-highlight "paper catches light" on raised/primary surfaces.

**Dropped:** the SVG-noise grain (design called it a coin-flip; a11y flagged contrast risk) — invest the
distinctiveness budget in the masthead/metrics/elevation instead. Net: lower risk, more signature.

**Security guardrails (security):** CSP byte-identical; no off-origin `url()`/`@import`/`<link>`; all icons
inline SVG; candidate font-scoping selector list must keep covering every candidate component; no new
storage/logging of the token. CI: generalize the font-CDN grep to any `url(https?://`/`@import url(http`;
assert the CSP line + `no-referrer` unchanged.

**Architecture / safe sequencing (architecture):**
- Own every primitive in the GLOBAL `styles.scss`; component SCSS styles only component-private classes
  (`:host`/`:host-context` for host variation). NO `::ng-deep`, NO `ViewEncapsulation.None`, NO primitive
  in a component's inline `styles:`.
- **Additive-first**: Phase A renames nothing → build+test stay green. Then migrate ONE page/component per
  step, `ng build` (per-component budget — `booking-manage` is 3375 B raw, nearest the 4 kB ceiling) +
  that component's spec each step.
- **Preserve spec/security hooks** — keep these class names on their data elements, change only their
  declarations (hex→token) or add primitive classes alongside: `.slot .action .zone .help .err
  .state-heading .next-step .stage .erasure-ack` (candidate), `.cell-* .request-row .unverified .act-export`
  (interest-requests — XSS/CSV-injection tests), `.bulk` (pipeline RBAC). Where a rename is unavoidable
  (`.window-btn`/`.export-btn` → `.segmented__btn`/`.btn` in dashboard), update its spec in the SAME step.
- **Candidate LCP**: keep Phase A lean; verify `/schedule` Lighthouse ≥85 stays after global growth.

---

## Outcome — implemented + second review pass complete
- **Implemented**: Phase A (global system elevation), Phase B (7 candidate pages → primitives), Phase C
  (dashboard flagship + 17 workbench components → `.btn`/`.input`/`.field`/`.table`/`.toolbar`/`.segmented`/
  `.badge`/`.alert`), Phase D (home + launchpad signature/lift-card). All hardcoded hexes in touched files
  tokenized; all spec/security hooks preserved (additive class strategy).
- **Build**: `ng build --configuration production` clean; all per-component style budgets pass (candidate
  chunks shrank as CSS moved to the unbudgeted global). **Tests**: `ng test` 317/317 green (incl. 2 new
  adversarial-hue contrast tests proving `.btn--brand` keeps fixed ink/wash + axe-clean under `#ffe600`).
- **Post-implementation review (loop 2)** — design APPROVE-WITH-NITS, a11y APPROVE, security
  APPROVE-WITH-NITS. Reconciled: softened the segmented active state to a quiet raised "thumb" (fill+
  elevation+weight, not colour-alone); deduped the metric label onto `.eyebrow`; snapped the metric figure
  to `--step-4`; consumed `--track-hero` on the hero; strengthened `.btn--brand:hover`; added the home
  feature `01..04` numbering motif; added the adversarial-hue axe tests; **fixed the blocking CI bug** where
  the new off-origin `url()` guard matched its own doc comment (reworded the comment; guard now passes).
- **CI**: added a generalized off-origin `url()`/`@import` guard (scoped to `*.scss`/`*.css`); CSP +
  Referrer-Policy untouched; font self-hosting + candidate font-scoping intact.
