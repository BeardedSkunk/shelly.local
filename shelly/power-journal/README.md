# Power journal

A Shelly script that records what a Plug M Gen3 consumes, entirely on the
device. No cloud, no broker, no server, no outbound connection at all.

Stretches of roughly constant power become *blocks*. The block that is still
running lives in the device KVS where anything on the network can read it;
finished blocks go into the script's own storage. Eventually shelly.local will
collect them from there and draw the curves, which is also what keeps the
archive from ever having to summarise itself.

The script never writes to the switch. It only reads `switch:0`, so it cannot
interfere with whatever else is driving the relay.

## Install

```bash
node tools/upload.js 192.168.178.21
```

Uploads, verifies the code by reading it back, enables the script so it
survives a reboot, and starts it. Then:

```bash
node tools/upload.js 192.168.178.21 --status
node tools/upload.js 192.168.178.21 --remove
```

The upload strips the comments first. A script may be 20480 bytes on the
device and comments count towards that, so the repository keeps the
explanation and the plug gets the code — 28.7 KB of source, 17.0 KB uploaded.

## Reading it

The running block, from anywhere on the network:

```bash
curl -s http://192.168.178.21/rpc/KVS.Get?key=pj/current
```

```json
{"version":1,"start_time":1785870000,"duration_sec":1800,"energy_mwh":1750,
 "meter_total_mwh":184800,"watt":3.5,"reference_watt":3.6}
```

- `energy_mwh` — what **this block** has drawn.
- `meter_total_mwh` — where the **plug's lifetime counter** stood when this was
  written. Energy is never measured directly, only as a difference of that
  counter, so recovery needs the bookmark to work out how much flowed while the
  script was away, and to notice a counter that was reset.
- `watt` — the block's **average**, not the current draw. A live value would
  mean a flash write every ten seconds. Whoever wants it reads
  `Switch.GetStatus`, which costs nothing.
- `reference_watt` — the level the block was **opened** at, from `apower`.
- `duration_sec` — how much of the elapsed time is already accounted for in
  `energy_mwh`. The entry can be up to half an hour old, so without it a reader
  could not tell.

### Why there are two watt values

`aenergy.total` does not advance continuously on this plug. It stands still for
minutes and then jumps — measured on the device at about 207 mWh a step, well
over two minutes apart. So a young block has counted no energy yet and its
average honestly reads 0 W for the first minutes, while `reference_watt` is
right immediately.

That is not just cosmetic. A block resumed after a script restart has to get
its reference back from somewhere, and a reference of zero on a live load would
disagree with the very next sample and split the block in three. So the level
is stored rather than inferred from the average.

Over any real distance the counter is exact: the first block archived on the
device came out at 413 mWh over 423 s — 3.51 W against 3.5–3.6 W measured. The
average simply converges from below, never short by more than one step's worth.

A null block — nothing drawn — needs none of that and says so by leaving it
out:

```json
{"version":1,"start_time":1785870000,"watt":0}
```

The archive is **not** reachable over RPC: `Script.storage` has no RPC methods
at all, it exists only inside the script. So the script serves it over HTTP:

```bash
curl -s http://192.168.178.21/script/1/journal
curl -s "http://192.168.178.21/script/1/journal?page=p3"
curl -s "http://192.168.178.21/script/1/journal?page=p3&raw=1"
```

The index gives the page list, how far the archive reaches, and the live block.
A page expands into `[start_time, duration_sec, energy_mwh]` triples with the
field names given once alongside — repeating them per block would treble the
response, and there are about 25 KB of script memory to build it in. Average
watt is `energy_mwh * 3600 / duration_sec`.

## Resetting it

```bash
curl -s -X POST -d '{"key":"pj/current"}' http://192.168.178.21/rpc/KVS.Delete
node tools/upload.js 192.168.178.21 --remove
```

Deleting the script takes its storage with it, archive included. Removing only
the KVS entry leaves the archive and starts a fresh block.

## Storage formats

### Archive page

```
1785870000|10800,10500|900,3000|43200
```

The first field is the page's start. Then one field per block:
`duration,energy in mWh`, or a bare `duration` when nothing was drawn — a null
block has no energy, so it carries no second number and says what it is by its
own shape.

Only the page start is stored. Every block begins where the one before it
ended, which holds because the archive is **gapless by construction**: any
stretch nobody accounted for is filed as a null block.

This is flat text rather than JSON on purpose. `JSON.parse()` on a damaged
string raises an uncatchable `SyntaxError` that takes the whole script down —
`try`/`catch` parse fine on firmware 2.0.0 but do not catch it, verified on the
device. A plain scanning loop cannot throw. The KVS entry stays JSON because
`KVS.Get` hands it back already decoded, so nothing has to parse that either.

### Metadata

```
1|17|p3,p4,p8
```

Version, generation, and the pages in chronological order. Slots not named here
are stale or half written, and are ignored rather than trusted. If the metadata
itself is unreadable it is rebuilt by reading every slot and sorting by start
time — nothing is deleted to recover, ever.

## How a block ends

A sample has to disagree with the running block by at least the tolerance,
`max(10% of the reference power, 200 mW)`, in three samples in a row. The old
block then ends at the **first** of those three, and the new one begins there.

The tolerance is measured against a reference power fixed when the block
opened, **not** against the block's running average. An average dragged along
by every sample it accepts never notices a slow ramp, and a phone tapering off
at the end of a charge is exactly such a ramp — the whole curve would collapse
into one meaningless block.

The new block takes its reference from the **last** of the three confirming
samples, not the first: the first is often still half inside the old level, or
the spike that started the run. At startup, where nothing has just happened,
the reference is the mean of the first three samples instead.

Crossing between drawing nothing and drawing something always counts, however
small the something is. 0.1 W is a non-null block, and at that level the
tolerance floor alone would not have noticed.

## What it costs in flash

| | when |
|---|---|
| KVS | block opens, then every 30 min, and at recovery |
| KVS, null block | once, when it opens — never again |
| storage | only when a block closes |

Nothing is written every ten seconds. A null block that lasts twelve hours
costs exactly one write.

An archive append is three storage writes, not one: the new page goes to a
spare slot, then the metadata switches over, then the old slot is released.
Losing power before the metadata write leaves the old page valid, losing it
after leaves the new one valid; there is no moment at which the archive is
neither.

## When the archive fills

Eleven page slots exist and one always stays free for that copy-on-write, so
ten are in use — roughly 700 blocks. When they are full the **oldest page is
dropped**, and the log says so.

There is deliberately no compaction stage. Summarising old blocks on a device
with 25 KB of script memory means holding several pages parsed at once, which
does not fit; shelly.local is meant to carry the history off long before the
archive wraps.

## Tests

```bash
node test/acceptance.js       # the acceptance tests from the specification
node test/acceptance.js -v    # and the script's own log
PJ_STRIPPED=1 node test/acceptance.js   # against what the device actually gets
```

The tests load the real `power-journal.js` and run it against a simulated plug
— nothing is reimplemented in the harness, so what passes is what gets
uploaded. The simulated limits are the ones measured on the device rather than
the documented ones: a storage value is dropped above **1022** bytes (not
1024), the thirteenth entry is refused, both silently, and a KVS value may be
**253** characters exactly.

`CFG.test_mode` in the script does the same job on the device: it logs what it
would persist and writes nothing, so it cannot damage a real journal. With
`CFG.test_feed` set to a list of watt values, those are used in place of the
meter.

## Device facts this relies on

Measured on a Plug M Gen3, `S3PL-30110EU`, firmware 2.0.0.

| | |
|---|---|
| `Script.storage` | 12 entries, **1022** bytes per value, `setItem`/`getItem`/`removeItem` only — no `getKeys`, and no RPC access at all |
| KVS value | **253** characters exactly |
| script code | 20480 bytes, comments included |
| script memory | about 25 KB; a 932 byte page costs 5.5 KB parsed |
| `aenergy.total` | Wh with three decimals, so whole mWh — but it jumps, roughly 207 mWh at a time, minutes apart |
| `JSON.parse` on bad input | uncatchable, kills the script |

## Verified on the device

Running on `192.168.178.21` with a phone charging through it. Switching the
plug off and back on over RPC produced, on real hardware:

```
page   1785879118|423,413|50
```

The 423 second block at 413 mWh is 3.51 W, against the 3.5–3.6 W the plug was
reporting. The `50` after it is the fifty second null block while the plug was
off — a bare number, no second field. The page moved from slot `p0` to `p1` as
it was rewritten, the generation went 1 → 2, and `archive_end` landed exactly
on the next block's start, so the archive stayed gapless across the switch.
