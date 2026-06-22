// F60 same-origin reverse proxy (026-seo-aeo deployment).
//
// The SPA is served from https://cadenceapp.cc (Cloudflare Pages); the Spring backend lives on
// Fly. For the `cad_session` cookie (HttpOnly; Secure; SameSite=Lax) to be sent on API calls and
// for the CSP `connect-src 'self'` to allow them, the API MUST be same-origin — so these Pages
// Functions proxy /api, /oauth2, and /login/oauth2/code to the Fly backend. Everything else falls
// through to the static SPA (with the SEO `_headers` + SPA fallback intact).
//
// Files starting with `_` are NOT routes, so this module is shared, not mounted.

const BACKEND_ORIGIN = 'https://cadence--mlohw.fly.dev';

/** Proxy the incoming request to the Fly backend, preserving method/body/cookies and adding
 *  forwarded headers so the backend can build correct https://cadenceapp.cc absolute URLs. */
export async function proxyToBackend(request) {
  const url = new URL(request.url);
  const target = BACKEND_ORIGIN + url.pathname + url.search;

  const headers = new Headers(request.headers);
  headers.delete('host'); // let fetch set Host from the Fly URL (Fly routes by it)
  headers.set('X-Forwarded-Proto', 'https');
  headers.set('X-Forwarded-Host', url.host);
  const clientIp = request.headers.get('CF-Connecting-IP');
  if (clientIp) {
    headers.set('X-Forwarded-For', clientIp);
  }

  const init = { method: request.method, headers, redirect: 'manual' };
  if (request.method !== 'GET' && request.method !== 'HEAD') {
    init.body = request.body;
    init.duplex = 'half';
  }

  const resp = await fetch(target, init);
  // Pass the response through verbatim (status, Set-Cookie, body) — the cookie is then set on
  // cadenceapp.cc; `redirect: manual` keeps OAuth 302s client-visible rather than followed here.
  return new Response(resp.body, {
    status: resp.status,
    statusText: resp.statusText,
    headers: resp.headers
  });
}
