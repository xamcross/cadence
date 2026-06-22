import { proxyToBackend } from '../_proxy.js';

// Proxy /oauth2/** (the SSO authorization kickoff) to the Fly backend.
export const onRequest = (context) => proxyToBackend(context.request);
