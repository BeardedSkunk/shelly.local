// The doorbell plug, 10.–19.08.2026, and the guard against it coming back.
//
//   node test/relay.js
//
// A doorbell transformer idles at about a watt. The plug switches off at 22:00
// and back on in the morning, so the load alternates between one watt and none
// at all -- and both of those are under low_mw, where the script deliberately
// stops telling one level from another because apower cannot either.
//
// Nothing else on a doorbell ever crosses that threshold, so nothing ever ended
// the block. One ran from 10.08. 18:30 to 19.08. 21:40: nine days, 137 Wh, and
// the reader spread that flat across the whole span at 627 mW -- through nine
// nights when the relay was open and nothing at all could flow. The nightly
// switch-off left no mark anywhere in the record.
//
// It cost more than the picture. That nine-day block is the same shape as the
// 6.9-day one in longblock.js: when it finally closed, the archive had to walk
// nine days of grid steps in a single sample, the task watchdog restarted the
// plug, and the firmware disabled the script afterwards.
//
// So an open relay is now its own kind of stretch. It is not a level too small
// to measure -- it is a fact that nothing can flow, and it cuts a block in both
// directions.

'use strict';

const { createPlug } = require('./harness');

const HOUR = 3600;
let checks = 0;
let failures = 0;

function ok(condition, what, detail) {
  checks++;
  console.log((condition ? '   ok   ' : '   FAIL ') + what + (detail ? '  (' + detail + ')' : ''));
  if (!condition) failures++;
}

function note(what) {
  console.log('   ..   ' + what);
}

/** Every block the finest tier holds, oldest first. */
function blocks(plug) {
  return plug.tierBlocks(0);
}

function watt(block) {
  return block.duration > 0 ? Math.round((block.energy * 3600) / block.duration) : 0;
}

// --------------------------------------------------------------------------

console.log('\nR1  the evening switch-off ends the block, the morning one starts a new');
{
  const plug = createPlug({ verbose: false });
  plug.boot();
  plug.settle(4);

  // A doorbell day: the transformer idling at a watt, then the relay open all
  // night, then idling again. Nothing here ever reaches low_mw.
  plug.feed(1.1, 3);
  plug.feedFor(1.1, 6 * HOUR);
  plug.offFor(9 * HOUR);
  plug.feedFor(1.2, 6 * HOUR);
  // Something above the threshold, so the last block closes and is archived.
  plug.feed(600, 3);
  plug.feedFor(600, 600);
  plug.feed(0, 3);
  plug.feedFor(0, 600);

  const rows = blocks(plug);
  note(rows.length + ' Bloecke: ' + rows.map((b) => watt(b) + ' mW/' + Math.round(b.duration / 60) + 'm').join(', '));

  // The night has to be in there as a stretch of its own, roughly nine hours
  // long and carrying nothing at all.
  const night = rows.filter((b) => b.energy === 0 && b.duration >= 8 * HOUR);
  ok(night.length === 1, 'the night is one block of its own', night.length + ' gefunden');
  if (night.length === 1) {
    ok(Math.abs(night[0].duration - 9 * HOUR) < 120, 'and it is as long as the relay was open',
      night[0].duration + ' s');
    ok(night[0].energy === 0, 'and it reports nothing at all', night[0].energy + ' mWh');
  }

  // And the day either side of it must not have been flattened into the night.
  const day = rows.filter((b) => b.energy > 0 && b.duration >= HOUR);
  ok(day.length >= 2, 'the idling hours are their own blocks, on both sides of it',
    day.length + ' gefunden');
  const flat = rows.find((b) => b.duration > 20 * HOUR);
  ok(flat === undefined, 'and nothing runs across the whole day in one piece',
    flat ? Math.round(flat.duration / HOUR) + ' h' : 'keiner');
}

console.log('\nR2  the energy of the hours that were on stays with those hours');
{
  const plug = createPlug({ verbose: false });
  plug.boot();
  plug.settle(4);

  plug.feed(1.0, 3);
  plug.feedFor(1.0, 10 * HOUR);
  plug.offFor(10 * HOUR);
  plug.feed(600, 3);
  plug.feedFor(600, 600);
  plug.feed(0, 3);
  plug.feedFor(0, 600);

  const rows = blocks(plug);
  const dark = rows.filter((b) => b.energy === 0 && b.duration >= 9 * HOUR);
  const lit = rows.filter((b) => b.energy > 0 && b.duration >= HOUR);
  const litMwh = lit.reduce((sum, b) => sum + b.energy, 0);
  const litSec = lit.reduce((sum, b) => sum + b.duration, 0);
  note('an: ' + Math.round(litSec / HOUR) + ' h mit ' + litMwh + ' mWh, aus: '
    + dark.reduce((s, b) => s + b.duration, 0) / HOUR + ' h mit '
    + dark.reduce((s, b) => s + b.energy, 0) + ' mWh');

  ok(dark.length > 0 && dark.every((b) => b.energy === 0),
    'the dark hours carry no energy');
  // Ten hours at a watt is about 10 Wh. Read across twenty hours it would be
  // half that per hour, which is the error this whole change is about.
  const rate = litSec > 0 ? (litMwh * 3600) / litSec : 0;
  ok(rate > 700 && rate < 1400, 'and the lit hours still read as about a watt',
    Math.round(rate) + ' mW');
}

console.log('\nR3  an open relay survives a power cut as an open relay');
{
  const plug = createPlug({ verbose: false });
  plug.boot();
  plug.settle(4);

  plug.feed(1.1, 3);
  plug.feedFor(1.1, 2 * HOUR);
  plug.offFor(2 * HOUR);
  // The plug loses power in the middle of the night and comes back with the
  // relay still open. If the block resumed as an ordinary quiet stretch, the
  // morning switch-on would not cut it -- a watt and nought are both low.
  plug.powerCut(600);
  plug.boot();
  plug.settle(4);
  plug.offFor(2 * HOUR);
  plug.feedFor(1.2, 2 * HOUR);
  plug.feed(600, 3);
  plug.feedFor(600, 600);
  plug.feed(0, 3);
  plug.feedFor(0, 600);

  const rows = blocks(plug);
  note(rows.length + ' Bloecke: ' + rows.map((b) => watt(b) + ' mW/' + Math.round(b.duration / 60) + 'm').join(', '));
  const morning = rows.filter((b) => b.energy > 0 && b.duration >= HOUR);
  ok(morning.length >= 2, 'the morning after the cut is a block of its own again',
    morning.length + ' Blöcke mit Energie');
  const straddler = rows.find((b) => b.energy > 0 && b.duration > 5 * HOUR);
  ok(straddler === undefined, 'and nothing reaches across the night that followed it',
    straddler ? Math.round(straddler.duration / HOUR) + ' h' : 'keiner');
}

console.log('\nR4  a plug that is never switched is unchanged by any of this');
{
  const plug = createPlug({ verbose: false });
  plug.boot();
  plug.settle(4);

  // The charging-plug pattern: idle, a load, idle again, relay always closed.
  plug.feed(0.9, 3);
  plug.feedFor(0.9, 2 * HOUR);
  plug.feedFor(420, HOUR);
  plug.feedFor(0.9, 2 * HOUR);
  plug.feed(600, 3);
  plug.feedFor(600, 600);
  plug.feed(0, 3);
  plug.feedFor(0, 600);

  const rows = blocks(plug);
  const zero = rows.filter((b) => b.energy === 0 && b.duration >= HOUR);
  ok(zero.length === 0, 'no empty stretch is invented where the relay never opened',
    zero.length + ' gefunden');
  const load = rows.find((b) => watt(b) > 300);
  ok(load !== undefined, 'and the load is still its own block',
    load ? watt(load) + ' mW ueber ' + Math.round(load.duration / 60) + ' min' : 'keiner');
}

console.log('\n----------------------------------------------------------------');
console.log(checks + ' checks, ' + failures + ' failed');
process.exit(failures === 0 ? 0 : 1);
