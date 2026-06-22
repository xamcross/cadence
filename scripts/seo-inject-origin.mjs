// F60 (026-seo-aeo) build-time SEO origin + indexability injection.
//
// Runs AFTER `ng build`, on the EMITTED dist directory (the artifacts only exist post-build).
// Mirrors the existing CADENCE_API_URL build-time injection pattern (no new dependency, pure Node).
//
//   node scripts/seo-inject-origin.mjs <dist-dir>     (default: frontend/dist/cadence/browser)
//
// Env:
//   CADENCE_PUBLIC_ORIGIN   (required) e.g. https://app.cadence.example.com  — origin only, no path.
//   CADENCE_PUBLIC_ENV      'production' enables indexing; ANY other value (preview/unset) =>
//                           deny-by-default: all-disallow robots.txt + noindex everywhere.
//
// Substitutions (in robots.txt / sitemap.xml / llms.txt / index.html):
//   __CADENCE_PUBLIC_ORIGIN__  -> the validated origin host (no scheme)
//   __CADENCE_ROBOTS__         -> 'index,follow' (prod) | 'noindex,nofollow' (non-prod)   [index.html]
//   __CADENCE_INDEX__          -> 'enabled' (prod) | 'disabled' (non-prod)                [index.html]
//
// Non-production additionally: overwrites robots.txt with an all-disallow body and appends a global
// X-Robots-Tag: noindex rule to _headers (production _headers is left byte-identical).

import { readFileSync, writeFileSync, existsSync, appendFileSync } from 'node:fs';
import { join } from 'node:path';

const distDir = process.argv[2] || join('frontend', 'dist', 'cadence', 'browser');
const rawOrigin = process.env.CADENCE_PUBLIC_ORIGIN || '';
const isProd = process.env.CADENCE_PUBLIC_ENV === 'production';

// Validate the origin to a strict host shape so it cannot inject HTML/JSON into the artifacts.
const originHost = rawOrigin.replace(/^https?:\/\//i, '').replace(/\/+$/, '');
if (!/^[a-z0-9.-]+(:[0-9]+)?$/i.test(originHost)) {
  console.error(`seo-inject-origin: CADENCE_PUBLIC_ORIGIN is missing or invalid: "${rawOrigin}"`);
  process.exit(1);
}

function patch(file, fn) {
  const p = join(distDir, file);
  if (!existsSync(p)) {
    console.error(`seo-inject-origin: expected artifact not found: ${p}`);
    process.exit(1);
  }
  writeFileSync(p, fn(readFileSync(p, 'utf8')), 'utf8');
}

const subOrigin = (s) => s.split('__CADENCE_PUBLIC_ORIGIN__').join(originHost);

// index.html — origin + robots + indexability switch
patch('index.html', (html) =>
  subOrigin(html)
    .split('__CADENCE_ROBOTS__').join(isProd ? 'index,follow' : 'noindex,nofollow')
    .split('__CADENCE_INDEX__').join(isProd ? 'enabled' : 'disabled')
);

// sitemap.xml + llms.txt — origin only
patch('sitemap.xml', subOrigin);
patch('llms.txt', subOrigin);

// robots.txt — production: substitute origin; non-production: blanket disallow
patch('robots.txt', (txt) =>
  isProd ? subOrigin(txt) : 'User-agent: *\nDisallow: /\n'
);

// Non-production: append a global noindex header (production _headers stays byte-identical).
if (!isProd) {
  const headers = join(distDir, '_headers');
  if (existsSync(headers)) {
    appendFileSync(headers, '\n/*\n  X-Robots-Tag: noindex\n', 'utf8');
  }
}

console.log(`seo-inject-origin: origin=${originHost} env=${isProd ? 'production' : 'non-production'} dist=${distDir}`);
