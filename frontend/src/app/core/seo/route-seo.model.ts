/**
 * F60 (026-seo-aeo) per-route SEO descriptor, attached to an Angular route via `data: { seo }`.
 *
 * DENY-BY-DEFAULT: a route with no `seo` (or `index !== true`) is treated as `noindex,nofollow` by
 * `SeoService`. Only routes explicitly marked `index: true` are indexable — so a future token/auth
 * route is non-indexable with no further change (FR-004/SC-009).
 *
 * Note: content-language (`<html lang>`) is a STATIC index.html concern (FR-007), not a field here.
 * The canonical is built at runtime from `location.origin + path` (query/token stripped) and managed
 * as a `<link rel="canonical">` via direct DOM — Angular's Meta service does not manage <link>.
 */
export interface RouteSeo {
  /** `true` only for the public home (and any future public page). Absent/false → noindex,nofollow. */
  index?: boolean;
  /** Document <title> + og/twitter title (required when `index`). */
  title?: string;
  /** <meta name="description"> + og/twitter description (required when `index`). */
  description?: string;
  /** Canonical path in preferred form (no trailing slash, no query). Combined with origin at runtime. */
  path?: string;
  /** Page-specific share image; falls back to the default brand OG image (FR-011). */
  ogImage?: string;
  /** Allows index,nofollow edge cases; defaults to mirror `!index`. */
  noFollow?: boolean;
  /** Future alternate-locale hook; only `en` emitted in MVP. */
  hreflang?: { lang: string; href: string }[];
}

/** The one indexable page in MVP — the public marketing home at `/`. */
export const PUBLIC_HOME: RouteSeo = {
  index: true,
  title: 'Cadence — Interview scheduling & candidate experience',
  description:
    'Cadence helps recruiters schedule interviews, prevent no-shows, and keep candidates ' +
    'informed — with no candidate account required. Calendar sync, GDPR-safe by design.',
  path: '/'
};

/** Explicit deny marker for token/auth/utility routes (equivalent to omitting `seo`). */
export const PRIVATE: RouteSeo = { index: false };
