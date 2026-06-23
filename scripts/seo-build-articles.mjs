// F61 (028-seo-content-library) build-time article-library generator (thin CLI wrapper).
//
// Runs AFTER `ng build`, on the EMITTED dist directory, BEFORE scripts/seo-inject-origin.mjs (which
// substitutes the origin + robots placeholders this generator leaves in place). Mirrors the
// seo-inject-origin.mjs precedent: pure Node, no dependency.
//
//   node scripts/seo-build-articles.mjs <dist-dir>     (default: frontend/dist/cadence/browser)
//
// It reads first-party article SOURCE from frontend/src/content/articles/*/ (meta.json + body.html),
// validates/lints/de-dups via the pure article-build.lib.mjs, and writes the static /resources/ pages
// + regenerates sitemap.xml / llms.txt / resources/feed.xml from the article ALLOW-LIST only (never a
// route or dist scan). All file I/O lives here; the pure lib is string-in/string-out.

import { readFileSync, writeFileSync, mkdirSync, readdirSync, existsSync, statSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  buildArtifacts,
  defaultContext,
  ArticleBuildError
} from '../frontend/src/app/core/seo/article-build.lib.mjs';

const SCRIPT_DIR = dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = join(SCRIPT_DIR, '..');
// CONTENT_DIR / SRC_INDEX are overridable (CADENCE_CONTENT_DIR / CADENCE_SRC_INDEX) so the node:test
// harness can point the generator at a temp fixture; default to the real first-party sources.
const CONTENT_DIR = process.env.CADENCE_CONTENT_DIR || join(REPO_ROOT, 'frontend', 'src', 'content', 'articles');
const LEGAL_DIR = process.env.CADENCE_LEGAL_DIR || join(REPO_ROOT, 'frontend', 'src', 'content', 'legal');
const SRC_INDEX = process.env.CADENCE_SRC_INDEX || join(REPO_ROOT, 'frontend', 'src', 'index.html');

const distDir = process.argv[2] || join('frontend', 'dist', 'cadence', 'browser');
const buildDate = process.env.CADENCE_BUILD_DATE || new Date().toISOString().slice(0, 10);

/** Read the home FAQ questions from index.html's FAQPage JSON-LD (for the anti-duplication gate). */
function readHomeFaqQuestions(indexPath) {
  if (!existsSync(indexPath)) return [];
  const html = readFileSync(indexPath, 'utf8');
  const blocks = [...html.matchAll(/<script type="application\/ld\+json">([\s\S]*?)<\/script>/g)].map((m) => m[1]);
  for (const b of blocks) {
    try {
      const obj = JSON.parse(b);
      if (obj && obj['@type'] === 'FAQPage' && Array.isArray(obj.mainEntity)) {
        return obj.mainEntity.map((q) => q && q.name).filter(Boolean);
      }
    } catch {
      /* ignore non-JSON blocks */
    }
  }
  return [];
}

/** Scan each content/articles subdirectory into an Article (meta.json + body.html). */
function loadArticles(contentDir) {
  if (!existsSync(contentDir)) return [];
  const out = [];
  for (const name of readdirSync(contentDir).sort()) {
    const dir = join(contentDir, name);
    if (!statSync(dir).isDirectory()) continue;
    const metaPath = join(dir, 'meta.json');
    const bodyPath = join(dir, 'body.html');
    if (!existsSync(metaPath) || !existsSync(bodyPath)) {
      throw new ArticleBuildError('incomplete_article: ' + name + ' (needs meta.json + body.html)');
    }
    const meta = JSON.parse(readFileSync(metaPath, 'utf8'));
    if (meta.slug !== name) {
      throw new ArticleBuildError('slug_dir_mismatch: dir "' + name + '" != slug "' + meta.slug + '"');
    }
    out.push({ ...meta, bodyHtml: readFileSync(bodyPath, 'utf8') });
  }
  return out;
}

/** Scan each content/legal subdirectory into a legal page (meta.json + body.html). */
function loadLegalPages(legalDir) {
  if (!existsSync(legalDir)) return [];
  const out = [];
  for (const name of readdirSync(legalDir).sort()) {
    const dir = join(legalDir, name);
    if (!statSync(dir).isDirectory()) continue;
    const metaPath = join(dir, 'meta.json');
    const bodyPath = join(dir, 'body.html');
    if (!existsSync(metaPath) || !existsSync(bodyPath)) {
      throw new ArticleBuildError('incomplete_legal_page: ' + name + ' (needs meta.json + body.html)');
    }
    const meta = JSON.parse(readFileSync(metaPath, 'utf8'));
    if (meta.slug !== name) {
      throw new ArticleBuildError('legal_slug_dir_mismatch: dir "' + name + '" != slug "' + meta.slug + '"');
    }
    out.push({ ...meta, bodyHtml: readFileSync(bodyPath, 'utf8') });
  }
  return out;
}

function writeFileEnsured(path, contents) {
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, contents, 'utf8');
}

function main() {
  if (!existsSync(distDir)) {
    console.error('seo-build-articles: dist dir not found: ' + distDir);
    process.exit(1);
  }
  const homeFaqQuestions = readHomeFaqQuestions(SRC_INDEX);
  const articles = loadArticles(CONTENT_DIR);
  const legalPages = loadLegalPages(LEGAL_DIR);

  // Base llms.txt is the angular-asset-copied src version already in dist (we append the article list).
  const distLlms = join(distDir, 'llms.txt');
  const baseLlms = existsSync(distLlms)
    ? readFileSync(distLlms, 'utf8')
    : readFileSync(join(REPO_ROOT, 'frontend', 'src', 'llms.txt'), 'utf8');

  const ctx = defaultContext(buildDate, homeFaqQuestions);

  let artifacts;
  try {
    artifacts = buildArtifacts(articles, baseLlms, ctx, legalPages);
  } catch (e) {
    if (e instanceof ArticleBuildError) {
      console.error('seo-build-articles: ' + e.message);
      process.exit(1);
    }
    throw e;
  }

  for (const page of artifacts.pages) {
    writeFileEnsured(join(distDir, 'resources', page.slug, 'index.html'), page.html);
  }
  writeFileEnsured(join(distDir, 'resources', 'index.html'), artifacts.indexHtml);
  writeFileEnsured(join(distDir, 'resources', 'feed.xml'), artifacts.feed);
  // 031 (terms-privacy): emit the static /terms and /privacy pages (served ahead of the SPA fallback).
  for (const lp of artifacts.legalPages) {
    writeFileEnsured(join(distDir, lp.slug, 'index.html'), lp.html);
  }
  // Overwrite the angular-asset-copied sitemap.xml + llms.txt in dist (generator owns them).
  writeFileEnsured(join(distDir, 'sitemap.xml'), artifacts.sitemap);
  writeFileEnsured(join(distDir, 'llms.txt'), artifacts.llms);

  console.log(
    'seo-build-articles: ' + articles.length + ' article(s) + ' + legalPages.length + ' legal page(s) -> ' +
    distDir + '/ (+ resources/, sitemap.xml, llms.txt, resources/feed.xml), buildDate=' + buildDate
  );
}

main();
