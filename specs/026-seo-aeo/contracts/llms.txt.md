# Contract: `llms.txt`

Served at the site root (`https://<origin>/llms.txt`), `Content-Type: text/plain` (Markdown body). The AEO guidance file for large-language-model answer engines (D5). Origin build-time injected.

## Body (template)

```markdown
# Cadence

> Cadence is an interview-scheduling and candidate-experience platform that lets recruiters
> schedule interviews, defend against no-shows, and keep candidates informed — without the
> candidate ever needing an account.

## What it is

Cadence coordinates single-stage interview scheduling across Google Calendar and Microsoft 365,
sends consent-gated email (invitations, confirmations, reminders, holds, rejections), runs a
no-show confirmation cascade, publishes a private candidate status page, collects interviewer
scorecards, and integrates with Greenhouse and Lever (or standalone CSV import). It is built
GDPR-first: candidate data is encrypted, consent is recorded, and erasure is one click.

## Who it is for

In-house recruiters and talent teams who want fewer no-shows and a respectful candidate experience.

## Links

- Home: https://__CADENCE_PUBLIC_ORIGIN__/
```

## Contract assertions (CI scan, SC-002/FR-016)

- MUST contain a single H1 and a blockquote summary.
- The `## Links` section MUST list **only** public URLs (home).
- MUST contain **zero** token/admin/candidate paths or `?token=` (same disallow set as the sitemap).
- Product claims MUST be accurate and consistent with the home page content and the JSON-LD description (FR-023).
- ASCII/UTF-8, LF line endings.
