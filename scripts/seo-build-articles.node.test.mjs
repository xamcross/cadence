// F61 (028-seo-content-library) Node end-to-end test for the generator CLI (fs/scan/emit) +
// the non-prod/prod robots injection on a /resources page. Pure node:test, no dependency.
//
//   node --test scripts/seo-build-articles.node.test.mjs
//
// It builds a temp fixture content tree, runs the real CLI against a temp dist, and asserts the
// emitted artifacts; then runs seo-inject-origin.mjs (preview + production) and asserts a real
// dist/resources/<slug>/index.html flips noindex/index accordingly (FR-010/SC-008, T035).

import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, existsSync, rmSync, cpSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';

const SCRIPT_DIR = dirname(fileURLToPath(import.meta.url));
const GEN = join(SCRIPT_DIR, 'seo-build-articles.mjs');
const INJECT = join(SCRIPT_DIR, 'seo-inject-origin.mjs');

function scaffold(articles) {
  const root = mkdtempSync(join(tmpdir(), 'f61-'));
  const content = join(root, 'content');
  const dist = join(root, 'dist');
  mkdirSync(dist, { recursive: true });
  // minimal dist artifacts the pipeline expects
  writeFileSync(join(dist, 'llms.txt'), '# Cadence\n\n> base summary\n', 'utf8');
  writeFileSync(join(dist, 'robots.txt'), 'User-agent: *\nAllow: /resources/\nDisallow: /\nSitemap: https://__CADENCE_PUBLIC_ORIGIN__/sitemap.xml\n', 'utf8');
  writeFileSync(join(dist, 'sitemap.xml'), '<?xml version="1.0"?><urlset></urlset>', 'utf8');
  writeFileSync(join(dist, 'index.html'),
    '<!doctype html><html lang="en"><head><meta name="robots" content="__CADENCE_ROBOTS__">' +
    '<meta name="cadence-index" content="__CADENCE_INDEX__">' +
    '<script type="application/ld+json">{"@type":"FAQPage","mainEntity":[{"@type":"Question","name":"What is Cadence?"}]}</script>' +
    '</head><body><app-root></app-root></body></html>', 'utf8');
  writeFileSync(join(dist, '_headers'), '/*\n  X-Content-Type-Options: nosniff\n', 'utf8');
  // a minimal src index.html the generator reads home FAQ from
  const srcIndex = join(root, 'index.html');
  writeFileSync(srcIndex, '<script type="application/ld+json">{"@type":"FAQPage","mainEntity":[{"@type":"Question","name":"What is Cadence?"}]}</script>', 'utf8');
  for (const a of articles) {
    const dir = join(content, a.slug);
    mkdirSync(dir, { recursive: true });
    const { bodyHtml, ...meta } = a;
    writeFileSync(join(dir, 'meta.json'), JSON.stringify(meta), 'utf8');
    writeFileSync(join(dir, 'body.html'), bodyHtml, 'utf8');
  }
  return { root, content, dist, srcIndex };
}

function runGen(env, dist) {
  return execFileSync('node', [GEN, dist], { env: { ...process.env, ...env }, encoding: 'utf8' });
}

const A1 = {
  slug: 'reducing-no-shows', title: 'Reducing no-shows', summary: 'A short lead answer.',
  datePublished: '2026-06-01', theme: 'no-shows', related: ['candidate-care'],
  bodyHtml: '<h2>Why</h2><p>Body with a <a href="/resources/candidate-care">link</a>.</p>'
};
const A2 = {
  slug: 'candidate-care', title: 'Candidate care', summary: 'Another short lead answer.',
  datePublished: '2026-06-02', theme: 'candidate-experience', related: [],
  bodyHtml: '<h2>Care</h2><p>Body.</p>'
};

test('generator emits article pages, index, sitemap, llms, feed', () => {
  const { root, content, dist, srcIndex } = scaffold([A1, A2]);
  try {
    runGen({ CADENCE_CONTENT_DIR: content, CADENCE_SRC_INDEX: srcIndex, CADENCE_BUILD_DATE: '2026-06-22' }, dist);
    assert.ok(existsSync(join(dist, 'resources', 'reducing-no-shows', 'index.html')), 'article page emitted');
    assert.ok(existsSync(join(dist, 'resources', 'candidate-care', 'index.html')), 'second article emitted');
    assert.ok(existsSync(join(dist, 'resources', 'index.html')), 'library index emitted');
    assert.ok(existsSync(join(dist, 'resources', 'feed.xml')), 'feed emitted');
    const sitemap = readFileSync(join(dist, 'sitemap.xml'), 'utf8');
    assert.match(sitemap, /\/resources\/reducing-no-shows/);
    assert.match(sitemap, /<lastmod>/);
    const llms = readFileSync(join(dist, 'llms.txt'), 'utf8');
    assert.match(llms, /\/resources\/reducing-no-shows/);
    const page = readFileSync(join(dist, 'resources', 'reducing-no-shows', 'index.html'), 'utf8');
    assert.match(page, /<h1>Reducing no-shows<\/h1>/);
    assert.match(page, /"BlogPosting"/);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('duplicate slug (via two dirs declaring the same slug) fails the build', () => {
  const { root, content, dist, srcIndex } = scaffold([A1]);
  try {
    // second dir whose meta.json declares the SAME slug -> caught as a slug/dir mismatch or duplicate
    const dir = join(content, 'dupe-dir');
    mkdirSync(dir, { recursive: true });
    writeFileSync(join(dir, 'meta.json'), JSON.stringify({ ...A1, related: [] }), 'utf8');
    writeFileSync(join(dir, 'body.html'), '<h2>x</h2><p>y</p>', 'utf8');
    let failed = false;
    try {
      runGen({ CADENCE_CONTENT_DIR: content, CADENCE_SRC_INDEX: srcIndex, CADENCE_BUILD_DATE: '2026-06-22' }, dist);
    } catch (e) {
      failed = true;
    }
    assert.ok(failed, 'generator should exit non-zero on a duplicate slug / dir mismatch');
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('non-prod inject -> a /resources page is noindex; prod inject -> index,follow (FR-010/SC-008)', () => {
  const { root, content, dist, srcIndex } = scaffold([A1, A2]);
  try {
    runGen({ CADENCE_CONTENT_DIR: content, CADENCE_SRC_INDEX: srcIndex, CADENCE_BUILD_DATE: '2026-06-22' }, dist);
    const articlePath = join('resources', 'reducing-no-shows', 'index.html');

    // preview (non-prod): copy the generated dist, inject, assert noindex on the article page
    const preview = join(root, 'preview');
    cpSync(dist, preview, { recursive: true });
    execFileSync('node', [INJECT, preview], {
      env: { ...process.env, CADENCE_PUBLIC_ORIGIN: 'app.example.com', CADENCE_PUBLIC_ENV: 'preview' }, encoding: 'utf8'
    });
    const previewPage = readFileSync(join(preview, articlePath), 'utf8');
    assert.match(previewPage, /name="robots" content="noindex,nofollow"/, 'non-prod article must be noindex');
    assert.ok(!previewPage.includes('__CADENCE_PUBLIC_ORIGIN__'), 'origin placeholder substituted');

    // production: inject the original dist, assert index,follow on the article page
    execFileSync('node', [INJECT, dist], {
      env: { ...process.env, CADENCE_PUBLIC_ORIGIN: 'app.example.com', CADENCE_PUBLIC_ENV: 'production' }, encoding: 'utf8'
    });
    const prodPage = readFileSync(join(dist, articlePath), 'utf8');
    assert.match(prodPage, /name="robots" content="index,follow"/, 'prod article must be index,follow');
    assert.match(prodPage, /https:\/\/app\.example\.com\/resources\/reducing-no-shows/, 'canonical origin substituted');
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
