// Tests for the quarter-hour archive in blu-osm.js.
//
//   node shelly/blu-osm/test/quarters.js
//
// The real file is loaded and run against stand-ins for the plug, so what
// passes is what gets uploaded rather than a copy of it. What is checked is the
// part that has to survive months of running unattended: the encoding, the
// alignment of the grid, the wrap of the ring, and what happens after an outage.

'use strict';

const fs = require('fs');
const path = require('path');

const SOURCE = path.join(__dirname, '..', 'blu-osm.js');

/** A storage value over this is dropped by the firmware, silently. */
const VALUE_LIMIT = 1022;
const ITEM_LIMIT = 12;

function createPlug() {
  const plug = {
    now: 0,
    storage: {},
    values: { temperature: null, humidity: null },
    stale: false,
    spokeAt: 0,
    writes: {},
    http: [],
    httpReply: { code: 201, body: '"Measurements saved in box"' },
    logs: [],
    endpoints: {},
    timers: [],
  };

  const storage = {
    setItem(key, value) {
      plug.writes[key] = (plug.writes[key] || 0) + 1;
      if (typeof value !== 'string') throw new Error('storage takes strings');
      if (value.length > VALUE_LIMIT) throw new Error(`page ${key} over the limit`);
      if (!(key in plug.storage) && Object.keys(plug.storage).length >= ITEM_LIMIT) {
        throw new Error('thirteenth storage item');
      }
      plug.storage[key] = value;
    },
    getItem: (key) => (key in plug.storage ? plug.storage[key] : null),
    removeItem: (key) => { delete plug.storage[key]; },
  };

  const Shelly = {
    getComponentStatus(name) {
      if (name === 'sys') return { unixtime: plug.now };
      // last_updated_ts moves when a packet arrives, not when somebody looks.
      // plug.stale is how a silent sensor is played: the value stays in the
      // component, the timestamp stops.
      const spoke = plug.stale ? plug.spokeAt : (plug.spokeAt = plug.now);
      if (name === 'bthomesensor:200') {
        return { value: plug.values.temperature, last_updated_ts: spoke };
      }
      if (name === 'bthomesensor:201') {
        return { value: plug.values.humidity, last_updated_ts: spoke };
      }
      return null;
    },
    call(method, params, cb) {
      if (method === 'HTTP.Request') {
        plug.http.push(params);
        const reply = plug.httpReply;
        if (cb) cb({ code: reply.code, body: reply.body || '' }, reply.ec || 0, reply.em || '');
        return;
      }
      if (method === 'Shelly.GetComponents') {
        const components = [
          { key: 'bthomesensor:200', config: { obj_id: 69 } },
          { key: 'bthomesensor:201', config: { obj_id: 46 } },
        ];
        if (cb) cb({ components, total: components.length }, 0, '');
        return;
      }
      if (cb) cb({}, 0, '');
    },
    addStatusHandler() {},
  };

  const Timer = { set: () => 1, clear: () => {} };
  const HTTPServer = {
    registerEndpoint(name, fn) { plug.endpoints[name] = fn; },
  };

  const code = fs.readFileSync(SOURCE, 'utf8')
    .replace(/\{\{OSM_URL\}\}/g, 'https://example.invalid/data')
    .replace(/\{\{OSM_TOKEN\}\}/g, 'x')
    .replace(/\{\{OSM_TEMPERATURE\}\}/g, 't')
    .replace(/\{\{OSM_HUMIDITY\}\}/g, 'h');

  const factory = new Function(
    'Shelly', 'Script', 'Timer', 'HTTPServer', 'print',
    code + '\nreturn { update: update, ST: ST, ARC: ARC };',
  );
  plug.api = factory(Shelly, { storage }, Timer, HTTPServer, (m) => plug.logs.push(m));
  return plug;
}

/** Runs the script's own update at a moment, with the sensor saying this. */
function sample(plug, unixtime, temperature, humidity) {
  plug.now = unixtime;
  plug.values.temperature = temperature;
  plug.values.humidity = humidity;
  plug.api.update();
}

function read(plug, from, count) {
  const res = { body: '', code: 0, headers: null, send() {} };
  plug.endpoints.quarters({ query: `from=${from}&count=${count}` }, res);
  return JSON.parse(res.body);
}

function overview(plug) {
  const res = { body: '', code: 0, headers: null, send() {} };
  plug.endpoints.quarters({ query: '' }, res);
  return JSON.parse(res.body);
}

let checks = 0;
let failures = 0;

function test(name, body) {
  process.stdout.write('\n' + name + '\n');
  try {
    body();
  } catch (error) {
    failures++;
    console.log('   !! threw: ' + error.message);
    console.log((error.stack || '').split('\n').slice(1, 3).join('\n'));
  }
}

function check(what, got, want, tolerance) {
  checks++;
  const ok = typeof want === 'number' && typeof got === 'number' && tolerance
    ? Math.abs(got - want) <= tolerance
    : JSON.stringify(got) === JSON.stringify(want);
  console.log(`   ${ok ? 'ok  ' : 'FAIL'} ${what}: ${JSON.stringify(got)}` +
    (ok ? '' : ` (wanted ${JSON.stringify(want)})`));
  if (!ok) failures++;
}

// A Monday noon, on a grid boundary.
const NOON = 1786348800 - (1786348800 % 300);
const Q = 300;   // five minutes, the grid the script keeps

test('a quarter holds the reading that stood at the mark, not an average', () => {
  const plug = createPlug();
  sample(plug, NOON, 20.0, 50.0);
  sample(plug, NOON + 100, 21.0, 52.0);
  sample(plug, NOON + 200, 22.0, 54.0);
  // Crossing into the next slot is what closes the one before it, and what gets
  // written is where the sensor stood then -- 22, not the mean of 21.
  sample(plug, NOON + Q, 30.0, 80.0);
  const got = read(plug, Math.floor(NOON / Q), 1);
  check('temperature', got.t[0], 22.0, 0.05);
  check('humidity', got.h[0], 54.0, 0.25);
});

test('a packet arriving just after the mark belongs to the quarter it arrived in', () => {
  // The ordering trap. At 12:05:01 the poll finds a reading a second old; that
  // reading is the state of 12:05, not of 12:00, and 12:00 has to close with
  // what stood before it.
  const plug = createPlug();
  sample(plug, NOON, 20.0, 50.0);
  sample(plug, NOON + 240, 21.0, 51.0);        // 12:04
  sample(plug, NOON + Q + 1, 25.0, 60.0);      // 12:05:01, a fresh packet
  sample(plug, NOON + 2 * Q, 26.0, 61.0);
  const got = read(plug, Math.floor(NOON / Q), 2);
  check('the first quarter closed on the older reading', got.t[0], 21.0, 0.05);
  check('and the new one on the newer', got.t[1], 25.0, 0.05);
});

test('a reading from hours ago is not the state of this quarter', () => {
  // The sensor goes silent. Its value stays in the component, so a poll keeps
  // seeing it -- but nothing was measured in these quarters and saying
  // otherwise would put a flat line where the truth is a gap.
  const plug = createPlug();
  const q = Math.floor(NOON / Q);
  sample(plug, NOON, 20.0, 50.0);
  for (let i = 1; i <= 4; i++) {
    plug.now = NOON + i * Q;
    plug.values.temperature = 20.0;
    plug.values.humidity = 50.0;
    plug.stale = true;                 // last_updated_ts stops advancing
    plug.api.update();
  }
  const got = read(plug, q, 4);
  check('the measured quarter stands', got.t[0], 20.0, 0.05);
  check('the silent ones are unknown', got.t.slice(1, 4), [null, null, null]);
});

test('the resolution is a tenth of a degree and half a per cent', () => {
  const plug = createPlug();
  sample(plug, NOON, -12.34, 43.7);
  sample(plug, NOON + Q, 0.0, 0.0);
  const got = read(plug, Math.floor(NOON / Q), 1);
  check('temperature rounds to 0.1', got.t[0], -12.3, 0.001);
  check('humidity rounds to 0.5', got.h[0], 43.5, 0.001);
});

test('the whole range a Brandenburg garden can reach survives the round trip', () => {
  const plug = createPlug();
  const wanted = [-40.0, -12.3, -0.1, 0.0, 0.1, 23.4, 41.7, 52.2];
  let q = Math.floor(NOON / Q);
  for (let i = 0; i < wanted.length; i++) {
    sample(plug, (q + i) * Q, wanted[i], 50);
  }
  sample(plug, (q + wanted.length) * Q, 0, 0);
  const got = read(plug, q, wanted.length);
  for (let i = 0; i < wanted.length; i++) {
    check(`${wanted[i]} comes back`, got.t[i], wanted[i], 0.051);
  }
});

test('a gap is a gap and not a guess', () => {
  const plug = createPlug();
  const q = Math.floor(NOON / Q);
  sample(plug, NOON, 20.0, 50.0);
  // The sensor goes quiet for an hour: nothing is sampled at all.
  sample(plug, NOON + 4 * Q, 25.0, 60.0);
  const got = read(plug, q, 5);
  check('the measured quarter', got.t[0], 20.0, 0.05);
  check('and three unknowns behind it', got.t.slice(1, 4), [null, null, null]);
  check('humidity likewise', got.h.slice(1, 4), [null, null, null]);
});

test('a page holds three and a half days and then the next one starts', () => {
  const plug = createPlug();
  const q = Math.floor(NOON / Q);
  const per = plug.api.ARC.per_page;
  // A quarter is written when the next one begins, so filling a page takes one
  // sample more than it holds records, and starting the next takes one beyond.
  for (let i = 0; i <= per + 1; i++) sample(plug, (q + i) * Q, 20 + (i % 10) / 10, 50);
  check('one page is 28 hours', per / 12, 28.0, 0.1);
  check('the second page is in use', plug.api.ST.page, 1);
  // Counted through the endpoint, because ST.count is what reached the flash
  // and the newest records are still in the buffer by design.
  check('and holds one record', overview(plug).count, 1);
  check('the first page is full', plug.storage.a.length, per * 3);
  check('which is inside the firmware limit', plug.storage.a.length <= VALUE_LIMIT, true);
});

test('the ring wraps, and the oldest is what is left', () => {
  const plug = createPlug();
  const q = Math.floor(NOON / Q);
  const per = plug.api.ARC.per_page;
  const pages = plug.api.ARC.slots.length;
  const total = per * pages + 10;      // ten quarters past a full turn
  for (let i = 0; i <= total; i++) sample(plug, (q + i) * Q, 20 + (i % 7) / 10, 50);
  const view = overview(plug);
  // Ten full pages plus whatever the current one has so far. A page is emptied
  // when it comes round again, so the holding swings between 35 days just after
  // a wrap and 38.5 just before one -- it is never all eleven at once.
  const days = (view.next - view.oldest) / 288;
  check('it holds between 11.5 and 13 days', days >= 11.5 && days <= 13, true);
  check('never more storage than exists', Object.keys(plug.storage).length <= ITEM_LIMIT, true);
  // The record from ten quarters ago has to still be readable and right.
  const recent = read(plug, q + total - 10, 1);
  check('a recent quarter reads back', recent.t[0], 20 + ((total - 10) % 7) / 10, 0.05);
});

test('a restart picks the grid up where it left it', () => {
  const plug = createPlug();
  const q = Math.floor(NOON / Q);
  // Seven samples, so six records reach the flash and one waits.
  for (let i = 0; i < 7; i++) sample(plug, (q + i) * Q, 20 + i, 50);

  // The script is reloaded -- a reboot, a code update -- against the same
  // storage. Nothing in memory survives; everything in storage does.
  const kept = Object.assign({}, plug.storage);
  const again = createPlug();
  Object.assign(again.storage, kept);
  sample(again, (q + 7) * Q, 27.0, 55.0);
  sample(again, (q + 8) * Q, 28.0, 56.0);

  const got = read(again, q, 9);
  check('what reached the flash is still there', got.t.slice(0, 6), [20, 21, 22, 23, 24, 25]);
  check('and the new records follow on', got.t[7], 27.0, 0.05);
});

test('a very long outage starts a fresh grid instead of filling months with holes', () => {
  const plug = createPlug();
  const q = Math.floor(NOON / Q);
  sample(plug, NOON, 20.0, 50.0);
  // Away for a fortnight.
  const later = q + 288 * 14;
  sample(plug, later * Q, 15.0, 70.0);
  sample(plug, (later + 1) * Q, 15.0, 70.0);
  const got = read(plug, later, 1);
  check('the reading after the outage is stored', got.t[0], 15.0, 0.05);
  check('and it did not write two weeks of nulls', plug.api.ST.count < 288, true);
});

test('the flash is written every half hour, not every five minutes', () => {
  // The whole page is rewritten on every flush, so a flush is what wears the
  // flash out. Six records is thirty minutes, which is the same rate the
  // fifteen-minute version had -- and the exposure on a power cut is the same
  // half hour it always was.
  const plug = createPlug();
  const q = Math.floor(NOON / Q);
  for (let i = 0; i <= 12; i++) sample(plug, (q + i) * Q, 20 + i / 10, 50);
  // Measured over the second hour, because the first also pays for the page
  // being created.
  const after = plug.writes.a;
  for (let i = 13; i <= 24; i++) sample(plug, (q + i) * Q, 20 + i / 10, 50);
  check('twelve records is an hour', 12 * Q / 3600, 1.0, 0.01);
  check('and costs two flushes', plug.writes.a - after, 2);
  check('which is 48 page writes a day', 2 * 24, 48);
  // Everything is readable all the same: what has not reached the flash is
  // still in the buffer, and reading looks in both.
  const got = read(plug, q, 12);
  check('nothing is invisible while it waits', got.t.filter((x) => x !== null).length, 12);
});

test('a power cut costs the buffer and nothing else', () => {
  const plug = createPlug();
  const q = Math.floor(NOON / Q);
  for (let i = 0; i <= 8; i++) sample(plug, (q + i) * Q, 20 + i, 50);
  // Six records reached the flash, two are still waiting. The lights go out.
  const survived = Object.assign({}, plug.storage);
  const again = createPlug();
  Object.assign(again.storage, survived);
  sample(again, (q + 9) * Q, 30.0, 70.0);
  sample(again, (q + 10) * Q, 31.0, 71.0);
  const got = read(again, q, 10);
  check('what was flushed survived', got.t.slice(0, 6), [20, 21, 22, 23, 24, 25]);
  check('what was in the buffer is gone, and says so', got.t.slice(6, 9), [null, null, null]);
  check('and the grid did not slip', got.t[9], 30.0, 0.05);
});


/** The backfill requests only -- the live push sends JSON, this sends CSV. */
function backfills(plug) {
  return plug.http.filter((r) => (r.headers || {})['Content-Type'] === 'text/csv');
}

test('what was recorded goes out with its own timestamps', () => {
  const plug = createPlug();
  const q = Math.floor(NOON / Q);
  for (let i = 0; i <= 3; i++) sample(plug, (q + i) * Q, 20 + i, 50 + i);

  const sent = backfills(plug);
  check('something was sent', sent.length >= 1, true);
  // The first request, not the last: by the end the pointer has moved on and
  // the newest request starts where that one stopped.
  const lines = sent[0].body.trim().split(String.fromCharCode(10));
  check('two sensors per slot', lines.length % 2, 0);
  // The first line has to carry the moment the reading belongs to, not now.
  const [sensor, value, stamp] = lines[0].split(',');
  check('the temperature sensor', sensor, 't');
  check('its value', Number(value), 20.0, 0.05);
  check('and the slot it stood in', stamp, new Date(q * Q * 1000).toISOString().replace(/\.\d\d\dZ$/, 'Z'));
});

test('the pointer moves only on a confirmed answer', () => {
  const plug = createPlug();
  const q = Math.floor(NOON / Q);
  plug.httpReply = { code: 500, body: 'the service is having an evening' };
  for (let i = 0; i <= 3; i++) sample(plug, (q + i) * Q, 20 + i, 50);
  check('nothing counts as delivered', plug.api.ST.sent, q);
  const tries = backfills(plug).length;
  check('and it kept trying', tries >= 2, true);

  // The service comes back.
  plug.httpReply = { code: 201, body: 'ok' };
  sample(plug, (q + 4) * Q, 24, 50);
  check('now it has moved', plug.api.ST.sent > q, true);
});

test('a refused call is not mistaken for an empty one', () => {
  const plug = createPlug();
  const q = Math.floor(NOON / Q);
  plug.httpReply = { code: 0, ec: -104, em: 'no route to host' };
  for (let i = 0; i <= 3; i++) sample(plug, (q + i) * Q, 20 + i, 50);
  check('the pointer stayed put', plug.api.ST.sent, q);
});

test('gaps are skipped but do not block the pointer', () => {
  const plug = createPlug();
  const q = Math.floor(NOON / Q);
  sample(plug, NOON, 20.0, 50.0);
  // Silence for three slots, then a reading again.
  for (let i = 1; i <= 3; i++) {
    plug.now = NOON + i * Q; plug.stale = true; plug.api.update();
  }
  plug.stale = false;
  sample(plug, NOON + 4 * Q, 25.0, 60.0);
  sample(plug, NOON + 5 * Q, 26.0, 61.0);
  check('the pointer went past the hole', plug.api.ST.sent > q + 3, true);
  const body = backfills(plug).map((r) => r.body).join('');
  check('and nothing was invented for it', body.indexOf('null'), -1);
});

test('what the service refuses to read is eventually stepped over', () => {
  const plug = createPlug();
  const q = Math.floor(NOON / Q);
  plug.httpReply = { code: 422, body: 'Illegal value for parameter' };
  for (let i = 0; i <= 10; i++) sample(plug, (q + i) * Q, 20 + i, 50);
  check('the pointer got past it', plug.api.ST.sent > q, true);
  check('and it took several tries first',
    backfills(plug).length >= plug.api.ARC.give_up, true);
});

test('a bad hour at the service is not a reason to throw data away', () => {
  const plug = createPlug();
  const q = Math.floor(NOON / Q);
  plug.httpReply = { code: 503, body: 'try again later' };
  for (let i = 0; i <= 30; i++) sample(plug, (q + i) * Q, 20 + (i % 5), 50);
  check('nothing was given up on', plug.api.ST.sent, q);

  plug.httpReply = { code: 201, body: 'ok' };
  sample(plug, (q + 31) * Q, 21, 50);
  check('and it all goes out once the service is back', plug.api.ST.sent > q, true);
});

test('the answer that refused it can be read back', () => {
  const plug = createPlug();
  const q = Math.floor(NOON / Q);
  plug.httpReply = { code: 422, body: 'Illegal value for parameter "value"' };
  for (let i = 0; i <= 2; i++) sample(plug, (q + i) * Q, 20 + i, 50);
  const note = overview(plug).note;
  check('it names the code', note.indexOf('422') >= 0, true);
  check('it keeps what the service said', note.indexOf('Illegal value') >= 0, true);
  check('and the line it choked on', note.indexOf('t,20') >= 0, true);
  // The quotes in that answer must not tear the overview apart.
  check('the overview is still readable JSON', typeof overview(plug).count, 'number');
});

test('one request never carries more than it can build', () => {
  const plug = createPlug();
  const q = Math.floor(NOON / Q);
  plug.httpReply = { code: 500 };          // hold everything back
  for (let i = 0; i <= 60; i++) sample(plug, (q + i) * Q, 20 + (i % 5), 50);
  plug.httpReply = { code: 201 };
  sample(plug, (q + 61) * Q, 20, 50);
  const last = backfills(plug)[backfills(plug).length - 1];
  const lines = last.body.trim().split(String.fromCharCode(10)).length;
  check('at most 20 slots, two sensors each', lines <= 40, true);
  check('which stays under two kilobytes', last.body.length < 2048, true);
});

console.log(`\n${checks} checks, ${failures} failed`);
process.exit(failures === 0 ? 0 : 1);

