// Cadence favicon generator (pure Node, no dependencies, no downloads -- Principle X).
//
// Source of truth: frontend/src/assets/favicon.svg (blue #1c5fd8 rounded square + 3 white bars).
// The default Angular favicon.ico shipped unchanged; this rasterizes the Cadence mark to a
// multi-size favicon.ico (16/32/48) + an opaque apple-touch-icon.png (180), so every consumer
// (browser tab, Google SERP via /favicon.ico, iOS bookmark) shows the brand, not the Angular shield.
//
//   node scripts/gen-favicon.mjs
//
// Rasterized in-process with 4x4 supersampled coverage of rounded-rect SDFs; PNG encoded via the
// built-in zlib; PNGs wrapped into an ICO container (PNG-in-ICO, supported by all modern browsers).

import { writeFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { deflateSync } from 'node:zlib';

const REPO_ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const SRC = join(REPO_ROOT, 'frontend', 'src');

// --- geometry (viewBox 0 0 32 32), mirrors assets/favicon.svg exactly ---
const BG = { x: 0, y: 0, w: 32, h: 32, r: 7, fill: [0x1c, 0x5f, 0xd8] };
const BARS = [
  { x: 6, y: 12, w: 4.4, h: 14, r: 2.2 },
  { x: 13.8, y: 6, w: 4.4, h: 20, r: 2.2 },
  { x: 21.6, y: 17, w: 4.4, h: 9, r: 2.2 }
];
const WHITE = [0xff, 0xff, 0xff];

// Signed-distance inside-test for an axis-aligned rounded rect (<=0 means inside).
function insideRoundRect(px, py, R) {
  const cx = R.x + R.w / 2, cy = R.y + R.h / 2;
  const qx = Math.abs(px - cx) - (R.w / 2 - R.r);
  const qy = Math.abs(py - cy) - (R.h / 2 - R.r);
  const outside = Math.hypot(Math.max(qx, 0), Math.max(qy, 0));
  const inside = Math.min(Math.max(qx, qy), 0);
  return outside + inside - R.r <= 0;
}

// Rasterize the mark to an RGBA buffer. `roundedBg` true => transparent outside the rounded bg
// (favicon); false => opaque full-bleed blue square (apple-touch, iOS masks the corners itself).
function rasterize(size, roundedBg) {
  const SS = 4; // 4x4 supersampling
  const scale = size / 32;
  const out = Buffer.alloc(size * size * 4);
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      let aAcc = 0, rAcc = 0, gAcc = 0, bAcc = 0;
      for (let sy = 0; sy < SS; sy++) {
        for (let sx = 0; sx < SS; sx++) {
          const vx = (x + (sx + 0.5) / SS) / scale;
          const vy = (y + (sy + 0.5) / SS) / scale;
          let col = null;
          if (BARS.some((b) => insideRoundRect(vx, vy, b))) col = WHITE;
          else if (roundedBg ? insideRoundRect(vx, vy, BG) : true) col = BG.fill;
          if (col) { aAcc += 1; rAcc += col[0]; gAcc += col[1]; bAcc += col[2]; }
        }
      }
      const n = SS * SS;
      const i = (y * size + x) * 4;
      const alpha = aAcc / n;
      out[i] = aAcc ? Math.round(rAcc / aAcc) : 0;
      out[i + 1] = aAcc ? Math.round(gAcc / aAcc) : 0;
      out[i + 2] = aAcc ? Math.round(bAcc / aAcc) : 0;
      out[i + 3] = Math.round(alpha * 255);
    }
  }
  return out;
}

// --- PNG encoding (8-bit RGBA) ---
const CRC_TABLE = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c >>> 0;
  }
  return t;
})();
function crc32(buf) {
  let c = 0xffffffff;
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}
function chunk(type, data) {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length, 0);
  const typeBuf = Buffer.from(type, 'ascii');
  const crcBuf = Buffer.alloc(4); crcBuf.writeUInt32BE(crc32(Buffer.concat([typeBuf, data])), 0);
  return Buffer.concat([len, typeBuf, data, crcBuf]);
}
function encodePng(size, rgba) {
  const sig = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(size, 0); ihdr.writeUInt32BE(size, 4);
  ihdr[8] = 8; ihdr[9] = 6; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0; // 8-bit, RGBA
  const raw = Buffer.alloc((size * 4 + 1) * size);
  for (let y = 0; y < size; y++) {
    raw[y * (size * 4 + 1)] = 0; // filter: none
    rgba.copy(raw, y * (size * 4 + 1) + 1, y * size * 4, (y + 1) * size * 4);
  }
  return Buffer.concat([sig, chunk('IHDR', ihdr), chunk('IDAT', deflateSync(raw, { level: 9 })), chunk('IEND', Buffer.alloc(0))]);
}

// --- ICO assembly (PNG-in-ICO) ---
function buildIco(pngs) {
  const count = pngs.length;
  const header = Buffer.alloc(6);
  header.writeUInt16LE(0, 0); header.writeUInt16LE(1, 2); header.writeUInt16LE(count, 4);
  const entries = Buffer.alloc(16 * count);
  let offset = 6 + 16 * count;
  pngs.forEach((p, idx) => {
    const e = idx * 16;
    entries[e] = p.size >= 256 ? 0 : p.size;     // width  (0 => 256)
    entries[e + 1] = p.size >= 256 ? 0 : p.size; // height
    entries[e + 2] = 0; entries[e + 3] = 0;      // palette, reserved
    entries.writeUInt16LE(1, e + 4);             // planes
    entries.writeUInt16LE(32, e + 6);            // bit depth
    entries.writeUInt32LE(p.data.length, e + 8); // bytes in resource
    entries.writeUInt32LE(offset, e + 12);       // image offset
    offset += p.data.length;
  });
  return Buffer.concat([header, entries, ...pngs.map((p) => p.data)]);
}

// --- emit ---
const icoSizes = [16, 32, 48];
const icoPngs = icoSizes.map((s) => ({ size: s, data: encodePng(s, rasterize(s, true)) }));
writeFileSync(join(SRC, 'favicon.ico'), buildIco(icoPngs));

const apple = encodePng(180, rasterize(180, false));
writeFileSync(join(SRC, 'assets', 'apple-touch-icon.png'), apple);

console.log('gen-favicon: wrote frontend/src/favicon.ico (' + icoSizes.join('/') + ') + assets/apple-touch-icon.png (180)');
