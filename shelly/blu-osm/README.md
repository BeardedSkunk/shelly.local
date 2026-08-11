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

Two tools do the pairing, both over plain RPC because nothing here goes through
the Shelly app:

    node tools/discover.js <plug-ip> [--watch]   what the plug hears
    node tools/pair.js <plug-ip> --list
    node tools/pair.js <plug-ip> --addr <mac> [--name "..."]
    node tools/pair.js <plug-ip> --unpair <mac>

`discover.js` reports only devices that are *not* already paired, which is
exactly the new one. It needs the sensor to broadcast during the scan — a press
on its button is enough — and `--watch` keeps scanning until it turns up.

The order matters, and it is the opposite of what one expects. A plug refuses an
address it has never received: the call breaks off and nothing is created. So
the sensor goes to its place first and is paired afterwards, which is also what
makes the changeover free of any gap:

1. Learn the new sensor's address indoors, next to any plug: `discover.js`.
   Nothing is paired yet, and the station outside keeps sending.
2. Put the new sensor in its place, beside the old one. Still nothing has
   changed as far as the plug is concerned — it builds its map once, at start.
3. From the desk: `pair.js --addr <new>`, then `pair.js --unpair <old>`, then
   restart the script so it maps itself onto the new components:

        curl -s "http://<plug-ip>/rpc/Script.Stop?id=<id>"
        curl -s "http://<plug-ip>/rpc/Script.Start?id=<id>"

   The new sensor has been delivering since step 2, so the first reading after
   the restart is a fresh one and openSenseMap sees no interruption at all.
4. Collect the old sensor whenever it suits. Unpaired, it is simply talking to
   nobody.

The five-minute archive survives all of this — `Script.storage` hangs on the
script id, not on the code or the sensor — except for whatever is still in RAM
when the script stops, at most half an hour. Those readings are already in
openSenseMap; the archive only exists for the times when they are not.
