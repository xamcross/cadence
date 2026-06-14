// Karma configuration for Cadence frontend unit tests.
// Self-contained browser resolution so `npm test` works with no CHROME_BIN env var on a typical
// Windows dev box (Edge is always present) and stays CI-friendly (containers set CHROME_BIN or
// have a chromium on PATH; the --no-sandbox flag makes headless run inside CI containers).
const fs = require('fs');
const path = require('path');

// Resolve a Chromium-family browser binary if the caller hasn't set CHROME_BIN.
// Preference: existing CHROME_BIN -> Edge (Windows) -> Chrome (Windows).
if (!process.env.CHROME_BIN) {
  const candidates = [
    'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
    'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
    'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
    'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
  ];
  const found = candidates.find((p) => {
    try {
      return fs.existsSync(p);
    } catch {
      return false;
    }
  });
  if (found) {
    process.env.CHROME_BIN = found;
  }
}

module.exports = function (config) {
  config.set({
    basePath: '',
    frameworks: ['jasmine', '@angular-devkit/build-angular'],
    plugins: [
      require('karma-jasmine'),
      require('karma-chrome-launcher'),
      require('karma-jasmine-html-reporter'),
      require('karma-coverage'),
      require('@angular-devkit/build-angular/plugins/karma'),
    ],
    client: {
      jasmine: {},
      clearContext: false, // leave Jasmine Spec Runner output visible in browser
    },
    jasmineHtmlReporter: { suppressAll: true },
    coverageReporter: {
      dir: path.join(__dirname, './coverage/cadence'),
      subdir: '.',
      reporters: [{ type: 'html' }, { type: 'text-summary' }],
    },
    reporters: ['progress', 'kjhtml'],
    browsers: ['EdgeHeadless'],
    customLaunchers: {
      // Chromium-based headless (Edge or Chrome, whichever CHROME_BIN points at).
      // --no-sandbox/--disable-gpu keep it runnable inside CI containers.
      EdgeHeadless: {
        base: 'ChromeHeadless',
        flags: ['--no-sandbox', '--disable-gpu'],
      },
    },
    restartOnFileChange: true,
  });
};
