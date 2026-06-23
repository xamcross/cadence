// Injects the production API base URL into frontend/src/environments/environment.prod.ts.
//
// Replaces the previous inline `node -e "...JSON.stringify(process.env.CADENCE_API_URL)..."` one-liner,
// which silently shipped a broken bundle: run under Git Bash (MSYS), the same-origin value `/api` is
// path-converted to the Git install path (e.g. `C:/Program Files/Git/api`), the browser then issues
// `file://` API requests, and every call fails.
//
// A valid base is a root-relative path (`/api`, not protocol-relative `//host`) or an absolute
// `http(s)://` URL. Unset or anything else (a Windows path, bare host, ...) -> the same-origin `/api`,
// with a warning so a genuine misconfiguration is visible in the build log. This mirrors the runtime
// backstop (frontend/src/app/core/api-base.ts `sanitizeApiBase`) so all layers agree on one rule.
//
// Usage: node scripts/inject-api-url.mjs   (reads CADENCE_API_URL; runnable from any cwd)

import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { writeFileSync } from 'node:fs';

const scriptDir = dirname(fileURLToPath(import.meta.url));
const target = join(scriptDir, '..', 'frontend', 'src', 'environments', 'environment.prod.ts');

const raw = (process.env.CADENCE_API_URL ?? '').trim();

const isRootRelative = /^\/(?!\/)/.test(raw); // "/api" but not "//host"
const isAbsoluteHttp = /^https?:\/\//i.test(raw);

let normalized;
if (isRootRelative || isAbsoluteHttp) {
  const trimmed = raw.replace(/\/+$/, ''); // trim trailing slash so `${base}/path` stays clean
  normalized = trimmed === '' ? '/api' : trimmed; // a bare "/" trims to "" -- fall back to same-origin
} else {
  if (raw !== '') {
    console.warn(
      `[inject-api-url] ignoring invalid CADENCE_API_URL ${JSON.stringify(raw)} ` +
        `(expected a root-relative path like "/api" or an absolute http(s) URL). ` +
        `A Windows-style path usually means Git Bash (MSYS) path-converted "/api"; ` +
        `set MSYS_NO_PATHCONV=1 or run the deploy from PowerShell. Defaulting to same-origin "/api".`
    );
  }
  normalized = '/api';
}

const contents = `export const environment = { production: true, apiBaseUrl: ${JSON.stringify(normalized)} };\n`;
writeFileSync(target, contents);
console.log(`[inject-api-url] wrote apiBaseUrl=${JSON.stringify(normalized)} to ${target}`);
