/**
 * Guards the configured API base URL.
 *
 * The production `apiBaseUrl` is injected at build time from the `CADENCE_API_URL` env var. If that
 * injection runs under Git Bash (MSYS), the leading-slash same-origin value `/api` is silently
 * path-converted to the Git install path, e.g. `C:/Program Files/Git/api`. The browser then resolves
 * `C:/Program Files/Git/api/public/...` as a `file://` URL and every API call fails with
 * "Not allowed to load local resource". This sanitiser is the runtime backstop: whatever the build
 * baked in, the app only ever talks to a valid same-origin path or absolute http(s) origin.
 *
 * A valid base is either a root-relative path (`/api`, but not protocol-relative `//host`) or an
 * absolute `http(s)://` URL. Anything else (a Windows path, a bare host, an empty value) falls back
 * to the same-origin default `/api`. Any trailing slash is trimmed so `${base}/path` stays clean.
 */
export function sanitizeApiBase(raw: string | null | undefined): string {
  const value = (raw ?? '').trim();
  const isRootRelative = /^\/(?!\/)/.test(value);
  const isAbsoluteHttp = /^https?:\/\//i.test(value);
  if (isRootRelative || isAbsoluteHttp) {
    const trimmed = value.replace(/\/+$/, ''); // drop trailing slash so `${base}/path` stays clean
    return trimmed === '' ? '/api' : trimmed; // a bare "/" trims to "" — fall back rather than ship an empty base
  }
  return '/api';
}
