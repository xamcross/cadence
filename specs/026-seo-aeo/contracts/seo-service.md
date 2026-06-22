# Contract: `SeoService` + route SEO data

## Public surface

```ts
@Injectable({ providedIn: 'root' })
export class SeoService {
  /** Wire once in the app root: subscribes to NavigationEnd and applies per-route SEO. */
  init(): void;
}
```

`RouteSeo` is attached to routes via `data: { seo: RouteSeo }` (see data-model.md §1). `SeoService.init()` is invoked once in the **`AppComponent` constructor** (not a child `ngOnInit`).

## Angular mechanics (review-mandated)

- **Initial navigation**: `provideRouter` defaults to `initialNavigation: 'enabledNonBlocking'`, so the first `NavigationEnd` fires after bootstrap. `init()` MUST also apply SEO to the **current URL synchronously** on subscribe, so the very first paint/route is covered (not just subsequent navigations). Tested explicitly (T010).
- **Canonical is NOT a `<meta>`**: Angular's `Meta` service manages `<meta>` only. The `<link rel="canonical">` MUST be created/updated/removed via direct `document.head` DOM (`querySelector('link[rel=canonical]')`). On a private route the service MUST **remove the static canonical element shipped in `index.html`** (not merely skip adding one), else the token page keeps the home canonical.
- **OG removal**: OG tags use `property=` (not `name=`); remove with `Meta.removeTag("property='og:title'")` etc.
- **Anonymous home**: the home route is unguarded; `SeoService` must not depend on auth. `HomeComponent` (separate, T012) renders for anonymous immediately and must not let its `me()` probe trigger the auth-interceptor redirect to `/login` (would defeat root indexing).

## Behavioral contract

| # | Given | Then |
|---|---|---|
| C-1 | a route with `seo.index === true` | sets `<title>`, `<meta name="description">`, `<meta name="robots" content="index,follow">`, `<link rel="canonical" href="{origin}{path}">`, OG/Twitter title/description/image/url |
| C-2 | a route with no `seo` (or `index !== true`) | sets `<meta name="robots" content="noindex,nofollow">`, **removes** canonical + description + OG tags, sets the generic title |
| C-3 | navigation away from an indexable route to a token route | all managed tags are **replaced**, not appended — no stale canonical/OG persists |
| C-4 | any route whose URL carries a query string (`?token=...`) | the canonical (if any) is built from `path` only — the query/token is **never** included |
| C-5 | non-production environment | robots is forced to `noindex` regardless of route `seo.index` |
| C-6 | the home route (`/`) | emits `index,follow` + canonical `{origin}/` + the product title/description |

## Test contract (seo.service.spec.ts + route-seo-inventory.spec.ts)

- C-1…C-6 each have a Jasmine assertion.
- **Inventory (FR-004/SC-009)**: iterate the real `app.routes.ts`; assert every route is either `seo.index === true` (only `/`) or resolves to `noindex` via the default. Add a synthetic route with no `seo` and assert the service emits `noindex,nofollow` (deny-by-default proven, not assumed).
- **No-token (C3/SC-002)**: drive a navigation to `/status?token=SENTINEL_SEO_TOKEN` and assert no canonical/OG tag in the DOM contains `SENTINEL_SEO_TOKEN`.
