# Contract: Structured data (JSON-LD in `index.html`)

Four static `<script type="application/ld+json">` blocks in `<head>`, present in the served bytes without JS (D4). All URLs absolute + public; **no token, no private path, no PII** (FR-014).

## Blocks

### Organization
```json
{
  "@context": "https://schema.org",
  "@type": "Organization",
  "name": "Cadence",
  "url": "https://__CADENCE_PUBLIC_ORIGIN__/",
  "logo": "https://__CADENCE_PUBLIC_ORIGIN__/assets/og-cadence.png"
}
```

### SoftwareApplication
```json
{
  "@context": "https://schema.org",
  "@type": "SoftwareApplication",
  "name": "Cadence",
  "applicationCategory": "BusinessApplication",
  "operatingSystem": "Web",
  "description": "Interview scheduling, no-show defense, and a GDPR-safe candidate status page — no candidate login required.",
  "offers": { "@type": "Offer", "price": "0", "priceCurrency": "USD" }
}
```

### WebSite
```json
{
  "@context": "https://schema.org",
  "@type": "WebSite",
  "name": "Cadence",
  "url": "https://__CADENCE_PUBLIC_ORIGIN__/"
}
```
> No `potentialAction`/`SearchAction` — the public site has no search box; claiming one would be inaccurate structured data.

### FAQPage (AEO — answer-engine-extractable)
3–5 `Question`/`acceptedAnswer` pairs, e.g.:
- "What is Cadence?"
- "Do candidates need to create an account?" → No — scheduling, rescheduling, and status are private no-login links.
- "Which calendars and ATS does Cadence support?" → Google Calendar, Microsoft 365; Greenhouse, Lever, CSV import.
- "Is candidate data GDPR-safe?" → Encrypted at rest, consent recorded before contact, one-click erasure.

## CSP note (Security review S4)

JSON-LD is **data, not executable script** — browsers do not gate `<script type="application/ld+json">` under `script-src`, so it renders under the current CSP (`default-src 'self'`) with **no change**. **Do NOT add `'unsafe-inline'` to `script-src`/`default-src`** to accommodate it (that would be a real SC-010 regression). Confirm rendering under the existing `_headers` CSP during implementation.

## Contract assertions (CI scan + Jasmine, SC-004/FR-014)

- All four `@type`s present in the built `index.html`.
- Each block is valid JSON and validates against schema.org with 0 Rich-Results errors.
- **Zero** token/admin/candidate URL or PII in any block (CI scan over the same disallow set).
- Origin placeholder fully substituted (no `__CADENCE_PUBLIC_ORIGIN__` left at deploy).
