// F14 CI-only static + API-stub server for Lighthouse.
//
// The Lighthouse gate must measure the REAL candidate route (/schedule) in its rendered, content-bearing
// "open" state — but the lighthouse CI job has no backend. This tiny Node server (no dependencies) serves
// the built SPA with SPA fallback (so deep-link routes like /schedule return index.html, which `npx serve -s`
// did for free) AND answers the candidate scheduling endpoint with a fixed canned open-state payload (times
// only — no PII). It is a CI test fixture, never a deployed service.
//
// Usage (from the frontend/ working directory): node lighthouse/serve-with-stub.mjs &
import http from 'node:http';
import { gzipSync } from 'node:zlib';
import { readFile } from 'node:fs/promises';
import { existsSync, statSync } from 'node:fs';
import { extname, join, normalize } from 'node:path';

const PORT = 4200;
const DIST = join(process.cwd(), 'dist', 'cadence', 'browser');
const DEMO_TOKEN = 'lighthouse-demo';

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.ico': 'image/x-icon',
  '.svg': 'image/svg+xml',
  '.woff2': 'font/woff2',
  '.woff': 'font/woff',
  '.map': 'application/json; charset=utf-8'
};

// Canned open-state slots (a few future times), times only — mirrors the F13 candidate view contract.
function cannedOpen() {
  const base = Date.UTC(2030, 0, 6, 14, 0, 0); // fixed future date — deterministic, no Date.now()
  const slots = [0, 1, 2, 3].map((i) => {
    const start = new Date(base + i * 24 * 3600 * 1000);
    const end = new Date(start.getTime() + 3600 * 1000);
    return { slotId: String(i), start: start.toISOString(), end: end.toISOString(), zoneId: 'America/New_York' };
  });
  return { status: 'open', zoneHint: 'America/New_York', bookedStart: null, slots };
}

// Canned A1 booked-state booking view (times + capabilities only — no PII, no location).
function cannedBooking() {
  const start = new Date(Date.UTC(2030, 0, 6, 14, 0, 0)); // fixed future date — deterministic, no Date.now()
  return {
    status: 'booked',
    bookedStart: start.toISOString(),
    zoneId: 'America/New_York',
    at: null,
    canReschedule: true,
    canCancel: true,
    rescheduleRemaining: 2
  };
}

// Canned F23 confirm-attendance response (times only — no PII, no location).
function cannedConfirmed() {
  const start = new Date(Date.UTC(2030, 0, 6, 14, 0, 0)); // fixed future date — deterministic, no Date.now()
  return {
    status: 'confirmed',
    bookedStart: start.toISOString(),
    zoneId: 'America/New_York',
    at: new Date(Date.UTC(2030, 0, 5, 9, 0, 0)).toISOString()
  };
}

// Canned A2 open-reschedule slots (times only) — same shape the F13 confirm step consumes.
function cannedReschedule() {
  const base = Date.UTC(2030, 0, 8, 14, 0, 0);
  const slots = [0, 1, 2].map((i) => {
    const startMs = base + i * 24 * 3600 * 1000;
    return {
      slotId: String(i),
      start: new Date(startMs).toISOString(),
      end: new Date(startMs + 3600 * 1000).toISOString(),
      zoneId: 'America/New_York'
    };
  });
  return { rescheduleToken: 'lighthouse-demo-reschedule', zoneHint: 'America/New_York', slots };
}

// Canned F30 candidate status views, keyed by DISTINCT demo tokens so the gate can measure each rendered
// state (no PII — recruiter-authored status text only + the workspace zone). Times-only / enum.
function cannedStatusPublished() {
  return {
    displayState: 'PUBLISHED',
    stage: 'Onsite interview',
    nextStep: 'We are collecting interviewer feedback and will be in touch shortly.',
    expectedDate: '2030-01-20',
    outcome: 'IN_PROGRESS',
    workspaceZone: 'Europe/London'
  };
}

function cannedStatusTerminal() {
  return {
    displayState: 'TERMINAL',
    stage: null,
    nextStep: 'Thank you for your time. We have decided to move forward with other candidates.',
    expectedDate: null,
    outcome: 'COMPLETE_REJECTED',
    workspaceZone: 'Europe/London'
  };
}

function cannedStatusUnderReview() {
  return { displayState: 'UNDER_REVIEW', workspaceZone: 'Europe/London' };
}

// Canned F03 public branding (logo + brand colour only — no PII, no setting/credential).
function cannedBranding() {
  return { brandColor: '#1F2937', logoUrl: '/api/public/workspace/logo' };
}

// Production (Cloudflare Pages) serves text assets gzip/brotli-compressed; the bare stub served them
// RAW, so Lighthouse downloaded the full ~322 KB bundle under simulated throttle and modelled an
// unrealistically slow FCP/LCP. Compress text responses (js/css/html/json/svg) when the client accepts
// gzip so the gate reflects the real, compressed transfer (no new dependency — node:zlib).
const COMPRESSIBLE = new Set(['.html', '.js', '.css', '.json', '.svg', '.map']);

function clientAcceptsGzip(req) {
  return /\bgzip\b/.test(req.headers['accept-encoding'] || '');
}

function sendJson(req, res, code, body) {
  const json = Buffer.from(JSON.stringify(body));
  const headers = { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' };
  if (clientAcceptsGzip(req)) {
    headers['Content-Encoding'] = 'gzip';
    res.writeHead(code, headers);
    return res.end(gzipSync(json));
  }
  res.writeHead(code, headers);
  res.end(json);
}

async function sendFile(req, res, filePath) {
  const data = await readFile(filePath);
  const headers = { 'Content-Type': MIME[extname(filePath)] ?? 'application/octet-stream' };
  if (COMPRESSIBLE.has(extname(filePath)) && clientAcceptsGzip(req)) {
    headers['Content-Encoding'] = 'gzip';
    res.writeHead(200, headers);
    return res.end(gzipSync(data));
  }
  res.writeHead(200, headers);
  res.end(data);
}

const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url, `http://localhost:${PORT}`);
    const path = url.pathname;

    // Stub the candidate scheduling API: view -> canned open-state; confirm -> booked; others -> 400.
    if (path.startsWith('/api/candidate/scheduling/')) {
      if (req.method === 'POST' && path.endsWith('/confirm')) {
        return sendJson(req, res, 200, { status: 'booked', bookedStart: cannedOpen().slots[0].start, zoneId: 'America/New_York' });
      }
      if (path.includes(DEMO_TOKEN) || path.includes('lighthouse-demo-reschedule')) return sendJson(req, res, 200, cannedOpen());
      return sendJson(req, res, 400, { error: 'invalid', message: 'invalid' });
    }

    // Stub the F20 candidate booking-management API: view -> booked; reschedule -> times-only slots;
    // cancel -> cancelled; others -> 400. So the Lighthouse gate measures the real content-bearing state.
    if (path.startsWith('/api/candidate/booking/')) {
      if (req.method === 'POST' && path.endsWith('/reschedule')) {
        if (path.includes(DEMO_TOKEN)) return sendJson(req, res, 200, cannedReschedule());
        return sendJson(req, res, 400, { error: 'invalid', message: 'invalid' });
      }
      if (req.method === 'POST' && path.endsWith('/cancel')) {
        if (path.includes(DEMO_TOKEN)) return sendJson(req, res, 200, { status: 'cancelled', at: new Date(Date.UTC(2030, 0, 5)).toISOString() });
        return sendJson(req, res, 400, { error: 'invalid', message: 'invalid' });
      }
      // F23 confirm-attendance: POST -> canned confirmed response (times only — no PII). So the Lighthouse
      // gate measures the real content-bearing confirmed state, not the SPA-fallback invalid state.
      if (req.method === 'POST' && path.endsWith('/confirm')) {
        if (path.includes(DEMO_TOKEN)) return sendJson(req, res, 200, cannedConfirmed());
        return sendJson(req, res, 400, { error: 'invalid', message: 'invalid' });
      }
      if (path.includes(DEMO_TOKEN)) return sendJson(req, res, 200, cannedBooking());
      return sendJson(req, res, 400, { error: 'invalid', message: 'invalid' });
    }

    // Stub the F30 candidate status API: view -> canned displayState keyed by the demo token; erasure POST
    // -> the constant 202 ack. So the Lighthouse gate measures the real content-bearing PUBLISHED state.
    if (path.startsWith('/api/candidate/status/')) {
      if (req.method === 'POST' && path.endsWith('/erasure-request')) {
        return sendJson(req, res, 202, { status: 'received' });
      }
      if (path.includes('lighthouse-demo-terminal')) return sendJson(req, res, 200, cannedStatusTerminal());
      if (path.includes('lighthouse-demo-review')) return sendJson(req, res, 200, cannedStatusUnderReview());
      if (path.includes(DEMO_TOKEN)) return sendJson(req, res, 200, cannedStatusPublished());
      return sendJson(req, res, 404, { error: 'not_found' });
    }

    // Stub the F32 interviewer scorecard API: GET -> canned blank FORM state (no PII); POST -> SUBMITTED. So
    // the Lighthouse gate measures the real content-bearing form, not the SPA-fallback invalid state.
    if (path.startsWith('/api/feedback/')) {
      if (req.method === 'POST') {
        return sendJson(req, res, 200, { state: 'SUBMITTED' });
      }
      if (path.includes(DEMO_TOKEN)) {
        return sendJson(req, res, 200, {
          state: 'FORM',
          interviewLabel: 'Interview on 2030-01-06',
          recommendationOptions: ['STRONG_YES', 'YES', 'NO', 'STRONG_NO'],
          ratingDimensions: ['Technical skills', 'Communication']
        });
      }
      return sendJson(req, res, 200, { state: 'USED' });
    }

    // Stub the F03 public branding endpoints the candidate status page composes (logo + brand colour only).
    if (path === '/api/public/workspace/branding') {
      return sendJson(req, res, 200, cannedBranding());
    }
    if (path === '/api/public/workspace/logo') {
      // A 1x1 transparent PNG placeholder — decorative, no PII.
      const png = Buffer.from(
        'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M8AAAMBAQDJ/pLvAAAAAElFTkSuQmCC',
        'base64');
      res.writeHead(200, { 'Content-Type': 'image/png', 'Cache-Control': 'no-store' });
      return res.end(png);
    }

    // Static file if it exists; otherwise SPA fallback to index.html.
    const safe = normalize(path).replace(/^(\.\.[/\\])+/, '');
    const candidate = join(DIST, safe);
    if (safe !== '/' && existsSync(candidate) && statSync(candidate).isFile()) {
      return await sendFile(req, res, candidate);
    }
    // F61: directory-index resolution so the generated static /resources and /resources/<slug> pages
    // (emitted as <dir>/index.html) serve at their clean URLs -- mirroring Cloudflare Pages. Without this
    // the Lighthouse gate would fall through to the SPA index.html and audit the wrong page.
    const indexCandidate = join(candidate, 'index.html');
    if (safe !== '/' && existsSync(indexCandidate) && statSync(indexCandidate).isFile()) {
      return await sendFile(req, res, indexCandidate);
    }
    return await sendFile(req, res, join(DIST, 'index.html'));
  } catch (e) {
    res.writeHead(500, { 'Content-Type': 'text/plain' });
    res.end('stub server error');
  }
});

server.listen(PORT, () => console.log(`lighthouse stub server on http://localhost:${PORT} (dist: ${DIST})`));
