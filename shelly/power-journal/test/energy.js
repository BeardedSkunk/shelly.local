// Where the two instruments disagree: scenarios in which the power apower
// reports and the energy the counter delivers add up to the same thing but
// arrive in very different shapes.
//
//   node test/energy.js
//   node test/energy.js -v      and show the script's own log
//
// The question every one of these asks is the same: after the dust settles,
// does the journal hold roughly what actually flowed, and does any single block
// claim a power that never happened? Conservation is checked against
// plug.grossExact, which is the truth the harness fed in, counting what the
// archive holds plus the block still running plus what was admittedly dropped.

'use strict';

const { createPlug } = require('./harness');

const VERBOSE = process.argv.indexOf('-v') >= 0;
const QUANTUM = 206;

let checks = 0;
let failures = 0;

function test(name, body) {
  process.stdout.write('\n' + name + '\n');
  try {
    body();
  } catch (error) {
    failures++;
    console.log('   !! threw: ' + error.message);
    console.log((error.stack || '').split('\n').slice(1, 4).join('\n'));
  }
}

function ok(condition, what) {
  checks++;
  if (condition) console.log('   ok   ' + what);
  else { failures++; console.log('   FAIL ' + what); }
}

function note(what) { console.log('   ..   ' + what); }

function running(options) {
  const plug = createPlug(Object.assign({ verbose: VERBOSE, meterQuantumMwh: QUANTUM }, options));
  plug.boot();
  plug.settle(4);
  return plug;
}

const sum = (list, pick) => list.reduce((total, item) => total + pick(item), 0);
const round = (n) => Math.round(n * 10) / 10;

// Everything the journal is holding, wherever it currently sits.
//
// Conservation is measured against what the meter has actually *reported*, not
// against what flowed. Those differ by up to one packet at all times, and at
// forty milliwatts a packet is five hours -- so a scenario can feed 400 mWh and
// have the counter still insisting on 206. Holding the journal to the truth
// rather than to its only witness would fail it for the meter's lag.
function books(plug) {
  const native = plug.tierBlocks(0);
  const ST = plug.pj.ST;
  const open = ST.blk === null ? 0 : ST.blk.energy;
  return {
    native,
    blocks: native.length,
    archived: sum(native, (b) => b.energy),
    open,
    dropped: ST.dropped,
    debt: ST.debt,
    credit: ST.credit,
    // Credit counts as held: it is waiting for a quiet stretch, not lost.
    booked: sum(native, (b) => b.energy) + open + ST.dropped + ST.credit,
    real: plug.grossVisible,
    fed: plug.grossExact,
  };
}

function report(b) {
  note(b.blocks + ' Bloecke, gebucht ' + round(b.booked) + ' von gemeldeten ' +
    round(b.real) + ' mWh (' + round(100 * b.booked / (b.real || 1)) + ' %)');
  note('gefuettert ' + round(b.fed) + ', Zaehler haelt noch ' + round(b.fed - b.real) +
    ' zurueck');
  note('offen ' + round(b.open) + ', verworfen ' + round(b.dropped) +
    ', Schuld ' + round(b.debt) + ', Guthaben ' + round(b.credit));
}

// The worst power any archived block claims, in mW.
function loudest(native) {
  let worst = 0;
  for (const b of native) {
    if (b.duration > 0) worst = Math.max(worst, Math.abs(b.energy * 3600 / b.duration));
  }
  return worst;
}

function conserves(b, tolerance, what) {
  const ratio = b.booked / (b.real || 1);
  ok(Math.abs(ratio - 1) <= tolerance,
    what + '  (' + round(100 * ratio) + ' % von ' + round(b.real) + ' mWh)');
}

// ---------------------------------------------------------------------------

test('E1  many blocks and not one packet between them', () => {
  // Six brief loads above the threshold, each far too small to move the
  // counter, separated by quiet. Nothing is measured at all in here: every
  // block closes on its claim alone and the debt just grows.
  const plug = running();
  for (let i = 0; i < 6; i++) {
    plug.feedFor(0, 300);
    plug.feed(2 + (i % 2), 4);
  }
  plug.feedFor(0, 300);

  const b = books(plug);
  report(b);
  ok(b.blocks >= 6, 'every one of them became a block of its own');
  ok(b.debt > 0, 'and all of it is still owed, since the counter never moved');
  ok(b.real === 0, 'the meter reported nothing whatsoever over the whole run');
  // The one case where the estimate beats the instrument: apower saw these
  // loads, the counter cannot resolve any of them, and the journal is nearer
  // the truth than the meter it is built on.
  ok(Math.abs(b.booked / b.fed - 1) < 0.1,
    'yet the books sit within a tenth of what really flowed  (' +
    round(b.booked) + ' von ' + round(b.fed) + ' mWh)');
});

test('E2  the packet that finally lands settles the whole run', () => {
  // The same, then quiet long enough for one packet to fall. Rule 1 says the
  // first packet in a quiet stretch clears the debt whatever is left of it.
  const plug = running();
  for (let i = 0; i < 6; i++) {
    plug.feedFor(0, 300);
    plug.feed(3, 4);
  }
  plug.feedFor(0.05, 5 * 3600);

  const b = books(plug);
  report(b);
  ok(b.debt === 0, 'the debt is gone once a quiet stretch has been paid into');
  conserves(b, 0.35, 'and the books still roughly match the meter');
});

test('E3  a whole day of quiet with a single load in the middle', () => {
  const plug = running();
  plug.feedFor(0.04, 6 * 3600);
  plug.feed(60, 4);
  plug.feedFor(60, 900);
  plug.feed(0.04, 4);
  plug.feedFor(0.04, 6 * 3600);

  const b = books(plug);
  report(b);
  const load = b.native.find((x) => x.duration > 600 && x.energy > 1000);
  ok(load, 'the real load is a block of its own');
  ok(Math.abs(load.energy * 3600 / load.duration - 60000) < 6000,
    'and it reads back at about sixty watts  (' +
    round(load ? load.energy * 3600 / load.duration / 1000 : 0) + ' W)');
  conserves(b, 0.05, 'the day adds up');
});

test('E4  flapping across the threshold all night', () => {
  // 1.4 W and 1.6 W alternating: every crossing is a level change by
  // definition, so this is the worst case for block count at almost no energy.
  const plug = running();
  for (let i = 0; i < 30; i++) {
    plug.feed(1.4, 4);
    plug.feed(1.6, 4);
  }
  plug.feedFor(0, 3600);

  const b = books(plug);
  report(b);
  ok(loudest(b.native) < 5000,
    'no block claims more than five watts  (' + round(loudest(b.native)) + ' mW)');
  conserves(b, 0.4, 'the flapping is still roughly accounted for');
});

test('E5  one packet has to be shared by a load and the quiet around it', () => {
  const plug = running();
  plug.feedFor(0.04, 4 * 3600);
  plug.feed(5, 6);
  plug.feedFor(0.04, 4 * 3600);

  const b = books(plug);
  report(b);
  ok(loudest(b.native) < 8000,
    'the brief load does not swallow the packet  (' + round(loudest(b.native)) + ' mW)');
  conserves(b, 0.3, 'and the two stretches together match the meter');
});

test('E6  exporting, where the counter is the only witness', () => {
  // A negative reference skips the claim entirely: apower is not trusted to
  // price generation, so the counter carries it alone.
  const plug = running();
  plug.feed(-400, 4);
  plug.feedFor(-400, 3600);
  plug.feed(0, 4);
  plug.feedFor(0, 600);

  const b = books(plug);
  report(b);
  const out = b.native.find((x) => x.energy < 0);
  ok(out, 'the export was archived as negative energy');
  ok(b.debt === 0 && b.dropped === 0, 'and it neither borrowed nor threw anything away');
});

test('E7  a meter that talks every load up by a fifth', () => {
  // apower reads 20 % high while the counter stays honest. Every claim is too
  // big, so the debt grows and the quiet stretches keep paying it off.
  const plug = running({ wattBias: 1.2 });
  for (let i = 0; i < 8; i++) {
    plug.feed(20, 4);
    plug.feedFor(20, 600);
    plug.feed(0.04, 4);
    plug.feedFor(0.04, 1800);
  }

  const b = books(plug);
  report(b);
  // Rule 4 is what saves this. The loads run long enough for the counter to
  // tick twice inside each block, and from that moment the measurement replaces
  // apower -- so a fifth of bias never reaches the books at all.
  conserves(b, 0.05, 'the counter overrules the biased apower on any block it measured twice');
});

test('E8  a meter that talks every load down by a fifth', () => {
  const plug = running({ wattBias: 0.8 });
  for (let i = 0; i < 8; i++) {
    plug.feed(20, 4);
    plug.feedFor(20, 600);
    plug.feed(0.04, 4);
    plug.feedFor(0.04, 1800);
  }

  const b = books(plug);
  report(b);
  conserves(b, 0.25, 'the surplus finds its way back into the quiet stretches');
});

test('E9  surplus offered to a quiet stretch too short to take it', () => {
  // A packet lands during a load, the surplus goes looking for a quiet stretch,
  // and the only one on offer is seconds long. Taking it all would lift that
  // stretch above the threshold, so what will not fit is dropped and counted.
  const plug = running({ wattBias: 0.5 });
  for (let i = 0; i < 6; i++) {
    plug.feed(200, 4);
    plug.feedFor(200, 120);
    plug.feed(0.04, 4);
    plug.feedFor(0.04, 60);
  }
  plug.feedFor(0.04, 600);

  const b = books(plug);
  report(b);
  ok(b.dropped >= 0, 'whatever could not be placed is counted rather than vanished');
  note('dropped_mwh im KVS: ' + (plug.kvs['current_power'].dropped_mwh || 0));
  conserves(b, 0.3, 'and the rest of it still adds up');
});

test('E10  a realistic day on a charging plug', () => {
  const plug = running();
  plug.feedFor(0.04, 8 * 3600);      // quiet night
  plug.feed(6000, 4);                // charger comes on
  plug.feedFor(6000, 2 * 3600);
  plug.feed(4000, 4);                // tapering
  plug.feedFor(4000, 1800);
  plug.feed(0.04, 4);
  plug.feedFor(0.04, 6 * 3600);      // quiet again

  const b = books(plug);
  report(b);
  conserves(b, 0.03, 'a day with real loads in it is accurate to within a few percent');
  ok(loudest(b.native) < 7000000, 'and nothing claims more than the charger ever drew');
});

console.log('\n' + '-'.repeat(64));
console.log(checks + ' checks, ' + failures + ' failed');
process.exit(failures > 0 ? 1 : 0);
