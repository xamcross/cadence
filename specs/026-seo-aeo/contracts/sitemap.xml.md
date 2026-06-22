# Contract: `sitemap.xml`

Served at the site root, `Content-Type: application/xml`. Origin build-time injected (D8). Lists **only** public, indexable URLs — exactly one in MVP.

## Body

```xml
<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
  <url>
    <loc>https://__CADENCE_PUBLIC_ORIGIN__/</loc>
    <changefreq>monthly</changefreq>
    <priority>1.0</priority>
  </url>
</urlset>
```

## Contract assertions (CI scan, SC-002/SC-007)

- MUST parse as well-formed XML against the sitemap 0.9 namespace.
- MUST contain exactly the home `<loc>` (absolute, injected origin, no placeholder remaining).
- MUST contain **zero** of: `/schedule`, `/booking`, `/confirm`, `/status`, `/feedback`, `/admin`, `/pipeline`, `/scheduling`, `/calendar`, `/workspace`, `/interview-templates`, `/email-templates`, `/login`, `/accept-invite`, `/reset`, and **no `?` / `token=`** anywhere.
- Non-production builds MAY omit the sitemap entirely (robots disallows all); if present it MUST still contain no private URL.
