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

function sendJson(res, code, body) {
  const json = JSON.stringify(body);
  res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(json);
}

async function sendFile(res, filePath) {
  const data = await readFile(filePath);
  res.writeHead(200, { 'Content-Type': MIME[extname(filePath)] ?? 'application/octet-stream' });
  res.end(data);
}

const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url, `http://localhost:${PORT}`);
    const path = url.pathname;

    // Stub the candidate scheduling API: view -> canned open-state; confirm -> booked; others -> 400.
    if (path.startsWith('/api/candidate/scheduling/')) {
      if (req.method === 'POST' && path.endsWith('/confirm')) {
        return sendJson(res, 200, { status: 'booked', bookedStart: cannedOpen().slots[0].start, zoneId: 'America/New_York' });
      }
      if (path.includes(DEMO_TOKEN) || path.includes('lighthouse-demo-reschedule')) return sendJson(res, 200, cannedOpen());
      return sendJson(res, 400, { error: 'invalid', message: 'invalid' });
    }

    // Stub the F20 candidate booking-management API: view -> booked; reschedule -> times-only slots;
    // cancel -> cancelled; others -> 400. So the Lighthouse gate measures the real content-bearing state.
    if (path.startsWith('/api/candidate/booking/')) {
      if (req.method === 'POST' && path.endsWith('/reschedule')) {
        if (path.includes(DEMO_TOKEN)) return sendJson(res, 200, cannedReschedule());
        return sendJson(res, 400, { error: 'invalid', message: 'invalid' });
      }
      if (req.method === 'POST' && path.endsWith('/cancel')) {
        if (path.includes(DEMO_TOKEN)) return sendJson(res, 200, { status: 'cancelled', at: new Date(Date.UTC(2030, 0, 5)).toISOString() });
        return sendJson(res, 400, { error: 'invalid', message: 'invalid' });
      }
      // F23 confirm-attendance: POST -> canned confirmed response (times only — no PII). So the Lighthouse
      // gate measures the real content-bearing confirmed state, not the SPA-fallback invalid state.
      if (req.method === 'POST' && path.endsWith('/confirm')) {
        if (path.includes(DEMO_TOKEN)) return sendJson(res, 200, cannedConfirmed());
        return sendJson(res, 400, { error: 'invalid', message: 'invalid' });
      }
      if (path.includes(DEMO_TOKEN)) return sendJson(res, 200, cannedBooking());
      return sendJson(res, 400, { error: 'invalid', message: 'invalid' });
    }

    // Static file if it exists; otherwise SPA fallback to index.html.
    const safe = normalize(path).replace(/^(\.\.[/\\])+/, '');
    const candidate = join(DIST, safe);
    if (safe !== '/' && existsSync(candidate) && statSync(candidate).isFile()) {
      return await sendFile(res, candidate);
    }
    return await sendFile(res, join(DIST, 'index.html'));
  } catch (e) {
    res.writeHead(500, { 'Content-Type': 'text/plain' });
    res.end('stub server error');
  }
});

server.listen(PORT, () => console.log(`lighthouse stub server on http://localhost:${PORT} (dist: ${DIST})`));
