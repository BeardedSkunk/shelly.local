# blu-osm

The script that publishes a Shelly BLU H&T to openSenseMap, as a template.

It is checked in with placeholders rather than with values:

    {{OSM_URL}}          the box's data endpoint
    {{OSM_TOKEN}}        that box's access token
    {{OSM_TEMPERATURE}}  the box's temperature sensor id
    {{OSM_HUMIDITY}}     its humidity sensor id

The app fills them in when it deploys, from the box the user picked out of
their own account. That is the whole reason for the template: a token belongs
to one box and to one person, and a copy of it checked in here would be a copy
of it in every build of the app and in the repository's history.

`app/src/main/assets/blu-osm.js` is a copy of this file and is what the app
actually uploads. Keep the two in step.

## Changing the sensor

The script never names a sensor. It looks for BTHome components carrying the
object ids it wants — battery, humidity, temperature — and takes the first of
each. Which physical BLU H&T sits behind them is the plug's business, not the
script's, so a sensor can be replaced without touching a line of code.

That holds across the two models in this household. The one with the display
(`SBHT-003C`) also broadcasts a light reading and two objects the firmware has
no name for; the one without (`SBHT-203C`) broadcasts something the object table
calls a distance in millimetres. None of it is looked at. The three that matter
are the same on both.

Two tools do the pairing, both over plain RPC because nothing here goes through
the Shelly app:

    node tools/discover.js <plug-ip> [--watch]   what the plug hears
    node tools/pair.js <plug-ip> --list
    node tools/pair.js <plug-ip> --addr <mac> [--name "..."]
    node tools/pair.js <plug-ip> --unpair <mac>

`discover.js` is only for finding out an address one does not know. It reports a
sensor while it is in pairing mode and not otherwise: the garden plug heard
nothing at all from a sensor lying a metre away and broadcasting every sixty
seconds, and paired with it a minute later without complaint. So four presses on
the sensor's button first, and `--watch` until it turns up.

Once the address is known, `discover.js` has nothing left to contribute.
`AddDevice` listens for an ordinary broadcast, no pairing mode involved.

The order matters, and it is the opposite of what one expects. `AddDevice` does
not answer straight away — it waits for a packet from the address it was given,
half a minute or so from a sensor in range and forever from one that is not
there. So the sensor goes to its place first and is paired afterwards, which is
also what makes the changeover free of any gap:

1. Learn the new sensor's address indoors, next to any plug: `discover.js`.
   Nothing is paired yet, and the station outside keeps sending.
2. Put the new sensor in its place, beside the old one, and pair it there as
   well: `pair.js --addr <new>`. Both are now on the plug and the script has
   not noticed a thing — it builds its map once, at start, and even a restart
   would find the older components first.
3. Leave them side by side until the new one has taken on the temperature
   outside, and watch what remains: `compare.js`. A sensor carried out of a warm
   room needs a good while, and until the difference stops moving it says
   nothing about the two devices.
4. From the desk: `pair.js --unpair <old>`, then restart the script so it maps
   itself onto the new components:

        curl -s "http://<plug-ip>/rpc/Script.Stop?id=<id>"
        curl -s "http://<plug-ip>/rpc/Script.Start?id=<id>"

   The new sensor has been delivering since step 2, so the first reading after
   the restart is a fresh one and openSenseMap sees no interruption at all.
5. Collect the old sensor whenever it suits. Unpaired, it is simply talking to
   nobody.

The five-minute archive survives all of this — `Script.storage` hangs on the
script id, not on the code or the sensor — except for whatever is still in RAM
when the script stops, at most half an hour. Those readings are already in
openSenseMap; the archive only exists for the times when they are not.

## Correcting a sensor

Two BLU H&T lying side by side do not agree. On 12.08.2026 two of them spent a
morning a hand's width apart in the garden and stayed several tenths of a
degree apart the whole time, and the gap moved with the weather — a warming
morning pulls them apart, a calm night brings them together. So the honest
number comes from the quiet hours, not from a single reading, and it wants a
third opinion: a device of a different make, in the same place, read at a
moment when nothing is changing fast.

Where the correction goes is decided by the hardware. The model with the
display carries `Temperatur-Offset` and `Feuchtigkeits-Offset` in the sensor
itself, reachable over Bluetooth from the Shelly app — that is the right place
for a sensor no script ever reads. The model without a display has no such
field, not even on firmware v1.2.12, so for anything feeding this script the
correction lives here instead.

The script keeps it out of the code. On its first start it creates two virtual
numbers, which appear among the plug's components in its web interface:

    Temperatur-Offset   K   default 0
    Feuchte-Offset      %   default 0

    curl -s "http://<plug-ip>/rpc/Number.Set?id=<id>&value=-0.8"

They are found by name, not by id, so a restart binds to the existing pair
rather than making a second one. A value takes effect at the next poll, within
a minute, without restarting anything.

The offset is applied in `update()`, at the single point where a reading enters
the script — so the KVS entry, the five-minute archive and openSenseMap all see
the same corrected number and cannot drift apart. What is already in the
archive stays as it was: those are finished records, not raw readings. A
correction therefore works from now on and not backwards, which also means the
series has a step in it on the day it is set.
