// Browser runs (-Pbrowser=true): the tests' fixture files, bundled into one JSON file by the Gradle task
// bundleBrowserFixtures, served by Karma at /absolute<path>. This file is inlined into the generated karma.conf.js in
// build/wasm/packages/<project>-test, four levels below the project directory.
const path = require('path');
const projectDir = path.resolve(__dirname, '../../../..');
config.files.push({
  pattern: path.join(projectDir, 'build/browser-fixtures/fixtures.json'),
  included: false,
  served: true,
  watched: false,
  nocache: true,
});
// WHO's suite takes minutes; Karma's defaults give up after seconds of silence.
config.browserNoActivityTimeout = 2 * 60 * 60 * 1000;
config.browserDisconnectTimeout = 2 * 60 * 60 * 1000;
config.captureTimeout = 5 * 60 * 1000;
config.client = config.client || {};
config.client.mocha = Object.assign(config.client.mocha || {}, { timeout: 0 });

// Firefox's updater, started from Terminal, tries to update Firefox.app, which macOS reports as Terminal asking to
// change another app. The same Firefox, with updates off in its test profile (as Firefox's own test harnesses do).
config.customLaunchers = Object.assign(config.customLaunchers || {}, {
  FirefoxHeadlessNoUpdate: {
    base: 'FirefoxHeadless',
    prefs: {
      'app.update.disabledForTesting': true,
      'app.update.auto': false,
      'app.update.background.enabled': false,
      'app.update.staging.enabled': false,
      'app.update.checkInstallTime': false,
    },
  },
});
config.browsers = config.browsers.map((b) => (b === 'FirefoxHeadless' ? 'FirefoxHeadlessNoUpdate' : b));
