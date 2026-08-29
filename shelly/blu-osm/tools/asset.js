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
// copy in the assets still matches, so a forgotten run fails rather than
// shipping a stale script. It is not checked in -- the directory is ignored on
// purpose, because a generated file that is also committed gets maintained
// twice -- so that test is the only thing standing between a change here and
// an app quietly deploying last month's script.

'use strict';

const fs = require('fs');
const path = require('path');
const { strip } = require('../../power-journal/tools/strip');

const SOURCE = path.join(__dirname, '..', 'blu-osm.js');
const TARGET = path.join(__dirname, '..', '..', '..',
  'app', 'src', 'main', 'assets', 'blu-osm.js');

/** What the firmware accepts. Measured, not taken from the documentation. */
const MAX_SCRIPT_BYTES = 20480;

const PLACEHOLDERS = ['{{OSM_URL}}', '{{OSM_TOKEN}}', '{{OSM_TEMPERATURE}}', '{{OSM_HUMIDITY}}'];

/**
 * The script as a plug will actually hold it.
 *
 * What has to fit is the filled-in copy, not the template: the holes are
 * shorter than what replaces them. A box id is 24 characters, a token 64.
 */
function fill(code) {
  return code
    .replace('{{OSM_URL}}', 'https://api.opensensemap.org/boxes/' + 'x'.repeat(24) + '/data')
    .replace('{{OSM_TOKEN}}', 'x'.repeat(64))
    .replace('{{OSM_TEMPERATURE}}', 'x'.repeat(24))
    .replace('{{OSM_HUMIDITY}}', 'x'.repeat(24));
}

/**
 * The squeezed script, checked but not written anywhere.
 *
 * Returning it rather than writing it is what lets the test suite ask "is the
 * copy in the assets still this?" -- a build() that wrote as it went would
 * repair the very staleness the test exists to catch, and then pass. That is
 * not a hypothetical: the asset sat seventeen days out of date because nothing
 * here could be asked the question. Throwing rather than exiting is the same
 * reasoning; a process.exit inside a test run takes the whole suite with it.
 */
function build() {
  const code = strip(fs.readFileSync(SOURCE, 'utf8'));
  for (const placeholder of PLACEHOLDERS) {
    if (!code.includes(placeholder)) {
      throw new Error('the squeeze lost ' + placeholder);
    }
  }
  const bytes = Buffer.byteLength(fill(code));
  if (bytes > MAX_SCRIPT_BYTES) {
    throw new Error('the filled script is ' + bytes + ' bytes, ' +
      (bytes - MAX_SCRIPT_BYTES) + ' over the ' + MAX_SCRIPT_BYTES + ' a plug accepts');
  }
  return code;
}

module.exports = { build, fill, TARGET, MAX_SCRIPT_BYTES };

if (require.main === module) {
  const source = fs.readFileSync(SOURCE, 'utf8');
  const code = build();
  fs.mkdirSync(path.dirname(TARGET), { recursive: true });
  fs.writeFileSync(TARGET, code);
  const bytes = Buffer.byteLength(fill(code));
  console.log(`${path.basename(SOURCE)}  ${Buffer.byteLength(source)} bytes`);
  console.log(`${path.basename(TARGET)}  ${Buffer.byteLength(code)} bytes ` +
    `(${bytes} filled, ${MAX_SCRIPT_BYTES - bytes} to spare)`);
}
