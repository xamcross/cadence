# SEO manual steps — things only you can do

Companion to the July 2026 SEO audit. Everything code-side (HSTS, asset caching, meta descriptions,
trailing-slash links, expanded articles, the `/features`, `/pricing`, `/integrations/*`, `/vs/calendly`
pages, per-page OG cards) ships in the `seo/audit-improvements` PR and goes live with the next
frontend deploy. The steps below need accounts or dashboard access that the tooling here does not
have. Ordered by impact — step 1 matters more than everything else combined.

## 1. Google Search Console (~20 min) — CRITICAL

The site is currently **absent from Google's index** (`site:cadenceapp.cc` returns nothing). Until
this step is done, every other SEO improvement is invisible.

1. Go to <https://search.google.com/search-console> and sign in with the Google account you want to
   own the property.
2. Add a property → **Domain** (`cadenceapp.cc`), not URL-prefix. It covers http/https and subdomains.
3. Google shows a TXT record. In **Cloudflare dashboard → cadenceapp.cc → DNS → Records → Add
   record**: Type `TXT`, Name `@`, Content = the `google-site-verification=...` value. Save, then
   click Verify in Search Console (propagation is usually under a minute on Cloudflare).
4. In Search Console → **Sitemaps** → enter `https://cadenceapp.cc/sitemap.xml` → Submit.
5. In **URL Inspection**, paste each of these and click **Request indexing** (quota is ~10/day —
   the deploy after the PR adds the new pages, so do this AFTER deploying):
   - `https://cadenceapp.cc/`
   - `https://cadenceapp.cc/features/`
   - `https://cadenceapp.cc/pricing/`
   - `https://cadenceapp.cc/integrations/`
   - `https://cadenceapp.cc/vs/calendly/`
   - `https://cadenceapp.cc/resources/`
   - the four `/resources/<slug>/` articles (next day's quota if needed)
6. Check back in ~1 week: **Pages** report should show indexed URLs. If pages sit in "Discovered –
   currently not indexed" for weeks, that is normal for a new domain — keep the content cadence up.

## 2. Bing Webmaster Tools (~5 min)

1. Go to <https://www.bing.com/webmasters> → sign in → **Import from Google Search Console** (one
   click once step 1 is done; it copies the verified property and sitemap).
2. Bing powers DuckDuckGo, ChatGPT search, and Copilot answers — cheap coverage for 5 minutes.

## 3. `www.cadenceapp.cc` DNS + redirect (~10 min)

`www.cadenceapp.cc` is currently **NXDOMAIN** — anyone typing `www.` gets a browser error.

1. Cloudflare dashboard → cadenceapp.cc → **DNS → Records → Add record**:
   Type `CNAME`, Name `www`, Target `cadenceapp.cc`, Proxy status **Proxied** (orange cloud).
2. **Rules → Redirect Rules → Create rule** (or Bulk Redirects):
   - When: Hostname equals `www.cadenceapp.cc`
   - Then: Dynamic redirect, expression `concat("https://cadenceapp.cc", http.request.uri.path)`,
     status **301**, preserve query string ✓.
3. Verify: `curl -sI https://www.cadenceapp.cc/` → expect `301` with
   `Location: https://cadenceapp.cc/`.

## 4. Deploy + post-deploy verification (~10 min)

1. Merge the PR, then run `scripts\deploy-frontend.ps1`.
2. Spot-check:
   - `curl -sI https://cadenceapp.cc/` → `Strict-Transport-Security` header present.
   - `curl -sI https://cadenceapp.cc/main-<hash>.js` → `Cache-Control: public, max-age=31536000, immutable`.
   - `https://cadenceapp.cc/features/`, `/pricing/`, `/integrations/greenhouse/`, `/vs/calendly/`
     load as static pages with content.
   - `https://cadenceapp.cc/sitemap.xml` lists the new pages.
3. Then do step 1.5 (request indexing) with the new URLs.

## 5. Directory listings (~1–2 h, spread over a week)

First backlinks + presence on the pages buyers actually compare tools on. Create a vendor account
and submit Cadence (use the live OG card `https://cadenceapp.cc/assets/og-cadence.png` as the logo
where a 1200×630 image is accepted; the favicon SVG for square logos):

- **G2**: <https://sell.g2.com> → "Claim your profile" / add product (category: Interview Scheduling
  Software).
- **Capterra / GetApp / Software Advice** (one Gartner Digital Markets submission covers all three):
  <https://www.capterra.com/vendors>.
- **AlternativeTo**: <https://alternativeto.net> → Add application; list Calendly, GoodTime,
  ModernLoop as alternatives (this is how the `/vs/` pages earn referral traffic).
- Optional later: Product Hunt launch, BetaList (early-access fits their model).

## 6. Listicle outreach (~2 h, ongoing)

The "best interview scheduling software" SERP is owned by vendor listicles that accept additions.
Target list (from the audit): peoplemanagingpeople.com, selectsoftwarereviews.com, recruiterflow.com
(blog), youcanbook.me (blog), koalendar.com (blog). Template:

> Subject: Addition for your interview scheduling software roundup
>
> Hi — I run Cadence (cadenceapp.cc), an interview scheduler built specifically for recruiting
> teams: no-show confirmation cascades, private candidate status pages, Greenhouse/Lever write-back,
> and GDPR tooling (one-click erasure, consent-gated email). Free during early access. If you update
> your roundup, we'd be glad to be considered — happy to provide screenshots, a demo workspace, or a
> founder quote.

Expect a low hit rate; one or two placements is a real win at this domain age.

## 7. Optional, later

- **Connect an SEO tool**: in Claude Code, the marketing plugin's Ahrefs/Semrush connectors can be
  authenticated to replace qualitative keyword estimates with real volume/difficulty and rank
  tracking. Worth it once the site is indexed.
- **HSTS preload**: after a few weeks of stable HSTS, consider `preload` +
  <https://hstspreload.org> submission. Not urgent; irreversible-ish, so deliberately deferred.
- **CI deploy secret**: `CLOUDFLARE_API_TOKEN` is empty in GitHub Actions (deploys are manual-only
  right now). Creating a Pages-scoped token and adding it to the repo secrets re-enables CI deploys.
