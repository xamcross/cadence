import { proxyToBackend } from '../_proxy.js';

// Proxy all /api/** requests to the Fly backend (same-origin session cookie + CSP connect-src 'self').
export const onRequest = (context) => proxyToBackend(context.request);
