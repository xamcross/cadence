export const environment = {
  production: true,
  // Same-origin in prod via the Cloudflare reverse-proxy of /api -> Fly backend (research D10).
  // Placeholder; the real value is injected at Cloudflare Pages build time from CADENCE_API_URL
  // (defaults to the same-origin '/api').
  apiBaseUrl: '/api'
};
