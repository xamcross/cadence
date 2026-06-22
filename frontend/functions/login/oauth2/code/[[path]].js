import { proxyToBackend } from '../../../_proxy.js';

// Proxy /login/oauth2/code/** (the OIDC redirect callback) to the Fly backend.
export const onRequest = (context) => proxyToBackend(context.request);
