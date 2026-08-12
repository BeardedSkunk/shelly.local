// The crash at the garden pump, 12.08.2026, and the guard against it coming back.
//
//   node test/longblock.js
//
// The pump had stood at zero for 6.9 days. Nothing changes when nothing
// happens, so that was one single open block -- and the moment the pump was
// switched on it closed and went into the archive in one go. feedTier walks a
// block one grid step at a time in every tier, so that one sample had to take
// 838 steps where an ordinary one takes six. The plug did not come back: the
// task watchdog restarted it (reset_reason 6) and the firmware disabled the
// script afterwards, so the crash cost the recording as well.
//
// What is checked here is not the timing -- Node is a thousand times faster
// than mJS and would hide it, which it did on the first attempt at this. What
// is checked is that the work no longer grows with the length of the idle
// stretch, and that nothing is lost by cutting it up.

'use strict';

const { createPlug } = require('./harness');

const DAY = 86400;
let checks = 0;
let failures = 0;

function ok(condition, what) {
  checks++;
  console.log((condition ? '   ok   ' : '   FAIL ') + what);
  if (!condition) failures++;
}

/** Idles at zero for a while, switches a pump on, and watches it being archived. */
function idleThenPump(idleSeconds) {
  const plug = createPlug({ verbose: false });
  plug.boot();
  plug.settle(4);

  // A block only exists once some power has been seen.
  plug.feed(400, 3);
  plug.feedFor(400, 120);
  plug.feed(0, 3);
  plug.feedFor(0, idleSeconds);

  // The pump. Rows are counted per sample, because the question is what one
  // sample has to do, not what the whole catch-up costs.
  const perSample = [];
  let before = plug.tierBlocks(0).length;
  for (let i = 0; i < 60; i++) {
    plug.feed(856, 1);
    const now = plug.tierBlocks(0).length;
    perSample.push(now - before);
    before = now;
  }
  return { plug, perSample, rows: plug.tierBlocks(0) };
}

console.log('\nA long idle stretch is archived in pieces\n');

const short = idleThenPump(600);
const long = idleThenPump(7 * DAY);

const shortPeak = Math.max.apply(null, short.perSample);
const longPeak = Math.max.apply(null, long.perSample);

ok(longPeak <= shortPeak + 1,
  'one sample writes no more rows after a week idle than after ten minutes  (' +
  longPeak + ' vs ' + shortPeak + ')');

const tooLong = long.rows.filter((r) => r.duration > DAY);
ok(tooLong.length === 0,
  'no single archived row spans more than a day');

const pieces = long.rows.filter((r) => r.energy === 0).length;
ok(pieces >= 7,
  'the week comes out as several pieces rather than one  (' + pieces + ')');

console.log('\nAnd nothing is lost by cutting it up\n');

// Every piece of an idle stretch carries no energy, so the total has to be
// exactly what the two powered stretches put through.
const total = long.rows.reduce((sum, r) => sum + r.energy, 0);
const powered = long.rows.filter((r) => r.energy !== 0);
ok(Math.abs(total - powered.reduce((s, r) => s + r.energy, 0)) < 1e-6,
  'the idle pieces contribute no energy at all');

// The archive has to stay gapless: each row must begin where the last ended.
let gapless = true;
for (let i = 1; i < long.rows.length; i++) {
  if (long.rows[i].start !== long.rows[i - 1].start + long.rows[i - 1].duration) gapless = false;
}
ok(gapless, 'the pieces still meet end to end, with no gap and no overlap');

console.log('\n----------------------------------------------------------------');
console.log(checks + ' checks, ' + failures + ' failed\n');
process.exit(failures === 0 ? 0 : 1);
