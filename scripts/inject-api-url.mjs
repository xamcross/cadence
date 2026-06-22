// Injects the production API base URL into frontend/src/environments/environment.prod.ts.
//
// Replaces the previous inline `node -e "...JSON.stringify(process.env.CADENCE_API_URL)..."` one-liner,
// which silently shipped a broken bundle: run under Git Bash (MSYS), the same-origin value `/api` is
// path-converted to the Git install path (e.g. `C:/Program Files/Git/api`), the browser then issues
// `file://` API requests, and every call fails. This script VALIDATES the value and fails the build
// loudly instead of baking in garbage.
//
// A valid base is a root-relative path (`/api`, not protocol-relative `//host`) or an absolute
// `http(s)://` URL. Unset -> defaults to the same-origin `/api`. Anything else -> hard error.
//
// Usage: node scripts/inject-api-url.mjs   (reads CADENCE_API_URL; runnable from any cwd)

import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { writeFileSync } from 'node:fs';

const scriptDir = dirname(fileURLToPath(import.meta.url));
const target = join(scriptDir, '..', 'frontend', 'src', 'environments', 'environment.prod.ts');

const raw = (process.env.CADENCE_API_URL ?? '').trim();
const value = raw === '' ? '/api' : raw;

const isRootRelative = /^\/(?!\/)/.test(value); // "/api" but not "//host"
const isAbsoluteHttp = /^https?:\/\//i.test(value);
if (!isRootRelative && !isAbsoluteHttp) {
  console.error(
    `[inject-api-url] CADENCE_API_URL is not a valid API base: ${JSON.stringify(value)}\n` +
      `  Expected a root-relative path (e.g. "/api") or an absolute http(s) URL ` +
      `(e.g. "https://api.example.com").\n` +
      `  A Windows-style path usually means Git Bash (MSYS) path-converted "/api"; ` +
      `set MSYS_NO_PATHCONV=1 or run the deploy from PowerShell.`
  );
  process.exit(1);
}

const trimmed = value.replace(/\/+$/, ''); // trim trailing slash so `${base}/path` stays clean
const normalized = trimmed === '' ? '/api' : trimmed; // a bare "/" trims to "" -- fall back to same-origin
const contents = `export const environment = { production: true, apiBaseUrl: ${JSON.stringify(normalized)} };\n`;
writeFileSync(target, contents);
console.log(`[inject-api-url] wrote apiBaseUrl=${JSON.stringify(normalized)} to ${target}`);
