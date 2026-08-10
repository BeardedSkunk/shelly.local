// Puts the squeezed sensor script where the Android app can bundle it.
//
//   node shelly/blu-osm/tools/asset.js
//
// The commented source is what anybody has to read to understand the archive;
// the plug stores the file exactly as given and counts every byte of it,
// comments and indentation included. So the repository keeps the explanation
// and the device gets the code.
//
// The placeholders survive the squeeze. They are filled in by whoever deploys
// -- the app from the box it was told about, or tools/deploy from what is
// already on the plug -- and a squeezer that renamed them would break both.
//
// Run this after every change to blu-osm.js. The test suite checks that the
// committed asset still matches, so a forgotten run fails rather than shipping
// a stale script.

'use strict';

const fs = require('fs');
const path = require('path');
const { strip } = require('../../power-journal/tools/strip');

const SOURCE = path.join(__dirname, '..', 'blu-osm.js');
const TARGET = path.join(__dirname, '..', '..', '..',
  'app', 'src', 'main', 'assets', 'blu-osm.js');

/** What the firmware accepts. Measured, not taken from the documentation. */
const MAX_SCRIPT_BYTES = 20480;

function build() {
  const source = fs.readFileSync(SOURCE, 'utf8');
  const code = strip(source);

  // The filled-in script is what actually has to fit, and the placeholders are
  // shorter than what replaces them. A box id is 24 characters, a token 64.
  const filled = code
    .replace('{{OSM_URL}}', 'https://api.opensensemap.org/boxes/' + 'x'.repeat(24) + '/data')
    .replace('{{OSM_TOKEN}}', 'x'.repeat(64))
    .replace('{{OSM_TEMPERATURE}}', 'x'.repeat(24))
    .replace('{{OSM_HUMIDITY}}', 'x'.repeat(24));

  const bytes = Buffer.byteLength(filled);
  if (bytes > MAX_SCRIPT_BYTES) {
    console.error(`filled script is ${bytes} bytes, ${bytes - MAX_SCRIPT_BYTES} over the limit`);
    process.exit(1);
  }
  for (const placeholder of ['{{OSM_URL}}', '{{OSM_TOKEN}}', '{{OSM_TEMPERATURE}}', '{{OSM_HUMIDITY}}']) {
    if (!code.includes(placeholder)) {
      console.error(`the squeeze lost ${placeholder}`);
      process.exit(1);
    }
  }

  fs.writeFileSync(TARGET, code);
  console.log(`${path.basename(SOURCE)}  ${Buffer.byteLength(source)} bytes`);
  console.log(`${path.basename(TARGET)}  ${Buffer.byteLength(code)} bytes ` +
    `(${bytes} filled, ${MAX_SCRIPT_BYTES - bytes} to spare)`);
}

build();
