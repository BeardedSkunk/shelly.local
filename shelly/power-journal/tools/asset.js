// Puts the squeezed script where the Android app can bundle it.
//
//   node tools/asset.js
//
// The app deploys the journal itself, so it needs the same bytes upload.js
// sends to a plug. Generating it at build time would put Node in the way of a
// Gradle build, so the result is checked in and this regenerates it. Run it
// after every change to power-journal.js -- the test suite checks that the
// committed asset still matches, so a forgotten run fails rather than ships a
// stale script.

'use strict';

const fs = require('fs');
const path = require('path');
const { strip } = require('./strip');

const SOURCE = path.join(__dirname, '..', 'power-journal.js');
const TARGET = path.join(__dirname, '..', '..', '..',
  'app', 'src', 'main', 'assets', 'power-journal.min.js');
const MAX_SCRIPT_BYTES = 20480;

function build() {
  const code = strip(fs.readFileSync(SOURCE, 'utf8'));
  if (Buffer.byteLength(code) > MAX_SCRIPT_BYTES) {
    throw new Error('the squeezed script is ' + Buffer.byteLength(code) +
      ' bytes, over the ' + MAX_SCRIPT_BYTES + ' a plug accepts');
  }
  return code;
}

module.exports = { build, TARGET };

if (require.main === module) {
  const code = build();
  fs.mkdirSync(path.dirname(TARGET), { recursive: true });
  fs.writeFileSync(TARGET, code);
  console.log('wrote ' + Buffer.byteLength(code) + ' bytes to ' +
    path.relative(path.join(__dirname, '..', '..', '..'), TARGET));
}
