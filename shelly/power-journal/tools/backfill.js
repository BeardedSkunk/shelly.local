// Writes daily figures from before a plug existed into its attic.
//
//   node tools/backfill.js <ip> <csv> [--scale 1.149] [--write]
//
// The attic is a script that never runs; its source is the last writable space
// on a plug, and day pages that fall out of storage are appended to it as
// comments. Nothing on the plug ever reads it back -- the app does that -- so
// putting history there is a matter of writing pages in the same shape the
// script would have written them.
//
// Without --write nothing is sent. The attic can only be appended to, so a
// wrong run is not undone by another run: it is undone by rewriting the whole
// script, which throws away whatever else was in it.
//
// The CSV is `date;kwh`, one line per day, '#' for comments. Days are cut on
// the plug's own midnights, which is where its day tier sits -- the same figure
// filed against the wrong midnight would be off by however different two
// neighbouring days were.

'use strict';

const fs = require('fs');
const http = require('http');

const A64 = '#$%&\'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[]^_`abc';
const DAY = 86400;
const DAY_UNIT_MWH = 10000;   // what one unit of the day tier is worth
const PAGE_LIMIT = 1010;
const ATTIC_NAME = 'pj-attic';
const ATTIC_LIMIT = 19800;

const host = process.argv[2];
const csvPath = process.argv[3];
const flags = process.argv.slice(4);
const write = flags.includes('--write');
const scaleAt = flags.indexOf('--scale');
const scale = scaleAt >= 0 ? Number(flags[scaleAt + 1]) : 1;

if (!host || !csvPath) {
  console.error('usage: node tools/backfill.js <ip> <csv> [--scale 1.149] [--write]');
  process.exit(1);
}
if (!Number.isFinite(scale) || scale <= 0) {
  console.error('--scale wants a positive number');
  process.exit(1);
}

function enc(n) {
  n = Math.round(n);
  if (!(n >= 0)) n = 0;
  let out = '';
  for (;;) {
    let g = n % 32;
    n = Math.floor(n / 32);
    if (n > 0) g += 32;
    out += A64[g];
    if (n === 0) return out;
  }
}

const encZ = (n) => enc(n < 0 ? -Math.round(n) * 2 - 1 : Math.round(n) * 2);

function rpc(method, params) {
  return new Promise((resolve, reject) => {
    const body = JSON.stringify({ id: 1, method, params: params || {} });
    const req = http.request({
      host, port: 80, path: '/rpc', method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) },
      timeout: 20000,
    }, (res) => {
      let text = '';
      res.on('data', (c) => { text += c; });
      res.on('end', () => {
        try {
          const parsed = JSON.parse(text);
          if (parsed.error) reject(new Error(method + ': ' + JSON.stringify(parsed.error)));
          else resolve(parsed.result);
        } catch (e) { reject(new Error(method + ': ' + text.slice(0, 120))); }
      });
    });
    req.on('error', reject);
    req.on('timeout', () => { req.destroy(new Error(method + ': timed out')); });
    req.end(body);
  });
}

/**
 * Local midnight for a date, in the zone the plug keeps.
 *
 * Taken from the plug rather than assumed, because the day tier is cut on the
 * plug's midnights and a backfill filed against the phone's would sit hours
 * beside the record it is meant to continue.
 */
function midnight(dateText, offsetSec) {
  const [y, m, d] = dateText.split('-').map(Number);
  return Date.UTC(y, m - 1, d) / 1000 - offsetSec;
}

function readCsv(path) {
  const out = [];
  for (const raw of fs.readFileSync(path, 'utf8').split('\n')) {
    const line = raw.trim();
    if (!line || line.startsWith('#') || line.startsWith('datum')) continue;
    const [date, kwh] = line.split(';');
    out.push({ date, kwh: Number(kwh) });
  }
  return out;
}

/** Blocks packed into pages, a new one wherever the days stop being consecutive. */
function pages(blocks) {
  const out = [];
  let page = '';
  let expected = -1;
  for (const b of blocks) {
    const field = enc(1) + encZ(b.units);
    if (page === '' || b.start !== expected || page.length + field.length > PAGE_LIMIT) {
      if (page !== '') out.push(page);
      page = '3' + enc(b.start);
    }
    page += field;
    expected = b.start + DAY;
  }
  if (page !== '') out.push(page);
  return out;
}

async function main() {
  const info = await rpc('Shelly.GetDeviceInfo');
  const sys = await rpc('Shelly.GetStatus');
  const offset = sys.sys.utc_offset;
  console.log(`${host}: ${info.model || info.app}, utc_offset ${offset} s`);

  const scripts = (await rpc('Script.List')).scripts;
  const attic = scripts.find((s) => s.name === ATTIC_NAME);
  if (!attic) throw new Error('no attic script on this plug -- deploy the journal first');
  const existing = (await rpc('Script.GetCode', { id: attic.id })).data || '';
  console.log(`attic is script ${attic.id}, holding ${existing.length} of ${ATTIC_LIMIT} bytes`);

  const rows = readCsv(csvPath);
  const blocks = rows.map((r) => ({
    date: r.date,
    start: midnight(r.date, offset),
    units: Math.round((r.kwh * scale * 1000000) / DAY_UNIT_MWH),
  }));
  blocks.sort((a, b) => a.start - b.start);

  const written = pages(blocks);
  const bytes = written.reduce((n, p) => n + p.length + 3, 0);
  const total = blocks.reduce((n, b) => n + b.units, 0) * DAY_UNIT_MWH / 1000000;

  console.log(`\n${rows.length} Tage ${rows[0].date} .. ${rows[rows.length - 1].date}`);
  console.log(`Faktor ${scale}, Summe danach ${total.toFixed(1)} kWh`);
  console.log(`${written.length} Seiten, ${bytes} Byte -- Dachboden danach ` +
    `${existing.length + bytes} von ${ATTIC_LIMIT}`);
  if (existing.length + bytes > ATTIC_LIMIT) throw new Error('that would overflow the attic');

  // Read back what the first page says, as the app will read it, so a wrong
  // midnight or a wrong unit shows here rather than in a chart weeks later.
  console.log(`\nerste Seite: ${written[0].slice(0, 40)}...`);
  console.log(`erster Tag: ${blocks[0].date} -> start ${blocks[0].start} ` +
    `(${new Date(blocks[0].start * 1000).toISOString()}), ${blocks[0].units * DAY_UNIT_MWH / 1000000} kWh`);
  console.log(`letzter Tag: ${blocks[blocks.length - 1].date} -> ` +
    `${blocks[blocks.length - 1].units * DAY_UNIT_MWH / 1000000} kWh`);

  if (!write) {
    console.log('\nProbelauf. Mit --write geht es wirklich auf die Dose.');
    return;
  }
  for (let i = 0; i < written.length; i++) {
    await rpc('Script.PutCode', { id: attic.id, code: '\n//' + written[i], append: true });
    console.log(`Seite ${i + 1}/${written.length} geschrieben`);
  }
  const after = (await rpc('Script.GetCode', { id: attic.id })).data || '';
  console.log(`Dachboden haelt jetzt ${after.length} Byte`);
}

main().catch((e) => { console.error(String(e.message || e)); process.exit(1); });
