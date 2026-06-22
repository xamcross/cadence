# Phase 1 Data Model: SEO & AEO Discoverability

This feature has **no persistent data** (no MongoDB collection, document, index, or Mongock changeset). The "entities" are frontend configuration shapes and static-artifact schemas. They are defined here as the authoritative source the runtime `SeoService`, the routes, and the static files all derive from.

---

## 1. `RouteSeo` (runtime route metadata) — `core/seo/route-seo.model.ts`

The per-route SEO descriptor attached to each Angular route via `data.seo`. **Deny-by-default**: a route with no `seo` (or `index !== true`) is treated as `noindex,nofollow`.

| Field | Type | Required | Notes |
|---|---|---|---|
| `index` | `boolean` | no (default `false`) | `true` only for the public home (and any future public page). `false`/absent → `noindex,nofollow`. |
| `title` | `string` | yes when `index` | Document `<title>`. Localized via `$localize`. |
| `description` | `string` | yes when `index` | `<meta name="description">` + OG/Twitter description. |
| `path` | `string` | yes when `index` | Canonical path in preferred form (no trailing slash, no query). Combined with the injected origin → absolute canonical. |
| `ogImage` | `string` | no | Path to a page-specific share image; falls back to the default `og-cadence.png` (FR-011). |
| `noFollow` | `boolean` | no (default mirrors `!index`) | Allows `index,nofollow` edge cases if ever needed; default ties to `index`. |
| `hreflang` | `{ lang: string; href: string }[]` | no | Future alternate-locale hook; only `en` emitted in MVP (D3). |

> **Content-language (FR-007/SC-001) is NOT a `RouteSeo` field.** It is declared once, statically, as `<html lang="en">` in `index.html` (already present). No runtime `lang` switching in MVP; T013 keeps it and T029 asserts it. (QA review BLOCKER-2.)
> **Canonical is DOM-managed, not a `Meta` tag.** `SeoService` creates/removes `<link rel="canonical">` via direct `document.head` DOM (Angular `Meta` manages `<meta>` only) and removes the static canonical on private routes. (Frontend review S2.)

**Presets** (exported constants):
- `PUBLIC_HOME: RouteSeo` — `index: true`, product title/description, `path: '/'`.
- `PRIVATE: RouteSeo` — `index: false` (the explicit deny marker; equivalent to omitting `seo`, used for readability on token/auth routes).

**Validation rules**:
- VR-1: If `index === true`, `title`, `description`, and `path` MUST be non-empty (enforced by a unit test over the route table).
- VR-2: A canonical/OG URL MUST NOT contain `?` or a token (the builder strips the query; a test asserts this against a token-bearing URL).
- VR-3: Any route lacking `seo` resolves to `PRIVATE` behavior (deny-by-default; the inventory test adds a synthetic route and asserts `noindex`).

---

## 2. `SeoService` behavior (state, not data) — `core/seo/seo.service.ts`

Subscribes to router `NavigationEnd`, resolves the deepest activated route's `data.seo`, and applies:

| Output sink | When `index: true` | When private (default) |
|---|---|---|
| `Title.setTitle(...)` | route title | a generic app title (`Cadence`) |
| `<meta name="description">` | route description | removed/absent |
| `<meta name="robots">` | `index,follow` | `noindex,nofollow` |
| `<link rel="canonical">` | `origin + path` | **removed** (never emits token) |
| OG/Twitter tags | title/description/image/url | removed |
| Non-prod override (D6) | forced `noindex` regardless | `noindex` |

State transition: `route A → route B` replaces (never appends) all managed tags, so stale canonical/OG from a previous route cannot persist onto a token page.

---

## 3. Static artifact schemas (build-time)

### `robots.txt` (deny-by-default; origin-injected) — see `contracts/robots.txt.md`
Fields: `User-agent`, layered `Allow`/`Disallow` (D2), `Sitemap` (absolute). Non-prod variant = `Disallow: /` only.

### `sitemap.xml` (origin-injected) — see `contracts/sitemap.xml.md`
One `<url>`: the home `<loc>` (absolute), `<changefreq>`, `<priority>`. **Invariant**: no token/auth path, no query string (SC-002).

### `llms.txt` (origin-injected) — see `contracts/llms.txt.md`
Markdown: H1 name, blockquote summary, product section, `## Links` (public URLs only). **Invariant**: no token/admin/candidate URL.

### Structured data (in `index.html`) — see `contracts/structured-data.md`
Four JSON-LD `@type`s: `Organization`, `SoftwareApplication`, `WebSite`, `FAQPage`. All URLs absolute + public. **Invariant**: no token/PII.

---

## 4. Route classification (the single source of truth)

Authoritative public/private split that robots.txt, sitemap, the inventory test, and llms.txt all agree with:

| Route(s) | Class | Index? |
|---|---|---|
| `/` (HomeComponent) | **public** | **yes** |
| `/login`, `/accept-invite`, `/reset`, `/reset/confirm`, `/not-authorized` | auth-utility | no (noindex) |
| `/schedule`, `/booking`, `/booking/cancel`, `/confirm`, `/status`, `/feedback` | candidate token | no (noindex + robots-disallow) |
| `/app`, `/admin/**`, `/pipeline/**`, `/scheduling`, `/interview-templates`, `/email-templates`, `/calendar/**`, `/workspace/**` | authenticated | no (noindex + robots-disallow) |
| `**` (NotFound — new, Frontend review B2) | wildcard/404 | no (noindex) |

Exactly one indexable URL in MVP (`/`). Everything else is non-indexable by the deny-by-default rule.

> **`/login` indexability (QA review SF-5)**: FR-022 says sign-in *MAY* be indexable; the design deliberately makes `/login` **non-indexable** (sign-in pages carry no SEO value and are conventionally noindexed). Only `/` is indexable in MVP — a permitted reading of "MAY".
