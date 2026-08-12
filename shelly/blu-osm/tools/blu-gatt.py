"""Redet mit einem Shelly BLU H&T direkt, ohne die Shelly-App.

    python blu-gatt.py dump                      alles lesen
    python blu-gatt.py set temp_offset 0         eine Einstellung schreiben
    python blu-gatt.py bisect --plug 192.168.178.26   Helligkeit einkreisen

Voraussetzungen: bleak (pip install bleak), eine bestehende Bluetooth-Kopplung
zwischen diesem Rechner und dem Sensor, und ein Knopfdruck pro Verbindung.

Woher das Wissen stammt
-----------------------
Aus einem Bluetooth-Mitschnitt des Handys vom 12.08.2026: Entwickleroptionen,
HCI-Snoop-Protokoll, in der Shelly-App jede Einstellung einmal angefasst, dann
`adb bugreport` und die Sitzung entschluesselt. Das Ergebnis ist erfreulich
schlicht -- gewoehnliches GATT, keine Verschluesselung, keine Signatur, keine
Cloud. Ein Schreibvorgang ist ein ATT Write Command auf ein festes Merkmal,
Zahlen little-endian, Ja/Nein als einzelnes Byte.

Der Knopfdruck ist nicht die Erlaubnis, sondern die Voraussetzung: im
Normalbetrieb funkt der Sensor nicht verbindbar, das spart ihm die Batterie.
Erst der Druck oeffnet ein kurzes Fenster. Steht die Verbindung einmal, haelt
sie -- gemessen drei Minuten und 64 Lesevorgaenge am Stueck.

Was der Sensor nicht hergibt
----------------------------
Den gemessenen Helligkeitswert. Nach aussen kommt nur die Entscheidung, hell
oder dunkel. Zwei nur lesbare Merkmale sahen danach aus und waren es nicht: sie
standen ueber drei Minuten unbeweglich auf 299 und 297, auch unter einer
Taschenlampe, und sanken im Lauf einer Stunde um je eins, waehrend die Batterie
von 99 auf 98 Prozent ging. Das ist mit hoher Wahrscheinlichkeit die
Batteriespannung in Hundertstel Volt.

Deshalb bisect: die Helligkeit laesst sich nur einkreisen, indem man die
Schwelle verstellt und zusieht, auf welcher Seite der Sensor landet.
"""

import argparse
import asyncio
import json
import sys
import time
import urllib.request

try:
    from bleak import BleakClient, BleakScanner
except ImportError:
    sys.exit('bleak fehlt:  python -m pip install bleak')

ADDR = 'FC:4D:6A:38:E2:F2'

# Name -> (UUID, Breite in Bytes). Die Zuordnung stammt aus dem Mitschnitt:
# jede Einstellung einmal in der App geaendert, mit auffaelligen Zahlen, und
# im Protokoll nachgesehen, welches Merkmal sich bewegt hat.
FELDER = {
    'temp_offset':     ('0de178e5-a95d-4988-b042-7145d540a000', 2),  # Zehntelgrad
    'feuchte_offset':  ('0de178e5-a95d-4988-b042-7145d540a002', 2),  # ganze Prozent
    'schwelle_dunkel': ('c1a32099-32e8-42d8-99bb-b90ce4abe841', 2),
    'schwelle_hell':   ('c1a32099-32e8-42d8-99bb-b90ce4abe842', 2),
    'fahrenheit':      ('68348d04-f62c-435d-b075-cc54b9f049cc', 1),
    'uhrzeit':         ('d56a3410-115e-41d1-945b-3a7f189966a1', 4),
    'intervall':       ('08b83239-6f5e-4412-892d-81e59224716e', 2),
}

# Noch nicht zugeordnet: fuenf Ja/Nein-Schalter. Welcher davon Anzeige
# invertieren, Energiesparmodus, Uhr-Synchronisierung und Zigbee ist, klaert
# je ein Mitschnitt, in dem genau einer umgelegt wird.
UNBEKANNT = [
    '611723f5-53dd-4289-888a-7523db56bb59',
    '8645a7a9-6bb6-41fa-a120-4034629c2519',
    '317c7868-5889-4572-b6ef-2c436ee5a92a',
    'ca9d7a88-2ad3-4940-9b8b-75558d08a3b0',
    'a9e33a3f-0396-41e5-a7c4-30511ffba2ad',
]

NUR_LESBAR = {
    'firmware':  '00002a26-0000-1000-8000-00805f9b34fb',
    'hersteller': '00002a29-0000-1000-8000-00805f9b34fb',
    'spannung_a': '8f8e2438-535d-478d-af0f-c3692c3c1bb1',
    'spannung_b': '8f8e2438-535d-478d-af0f-c3692c3c1bb2',
}


async def fassen(minuten=5.0):
    """Wartet auf das verbindbare Fenster und greift zu.

    Der Suchlauf laeuft durchgehend statt in Runden: zwischen zwei Laeufen ist
    der Adapter blind, und der Sensor funkt nur einmal pro Minute. Mit
    Start-Stop-Start wurden 24 Versuche gebraucht und ein Paket gehoert.
    """
    q = asyncio.Queue()

    def gesehen(dev, adv):
        if dev.address.upper() == ADDR:
            q.put_nowait((dev, adv.rssi))

    sc = BleakScanner(detection_callback=gesehen)
    await sc.start()
    print('   warte auf den Sensor -- jetzt den Knopf druecken', flush=True)
    ende = asyncio.get_event_loop().time() + minuten * 60
    try:
        while asyncio.get_event_loop().time() < ende:
            try:
                dev, rssi = await asyncio.wait_for(q.get(), timeout=5.0)
            except asyncio.TimeoutError:
                continue
            while not q.empty():
                q.get_nowait()
            try:
                c = BleakClient(dev, timeout=8.0)
                await c.connect()
                print('   verbunden, %d dBm' % rssi, flush=True)
                return c
            except Exception:
                pass
    finally:
        await sc.stop()
    return None


async def lesen(c, uuid, breite):
    raw = await c.read_gatt_char(uuid)
    return int.from_bytes(raw[:breite], 'little')


async def schreiben(c, uuid, breite, wert):
    await c.write_gatt_char(uuid, wert.to_bytes(breite, 'little'), response=False)


def plug_zustand(plug, geraet=200):
    """Was der Shelly gerade vom Sensor hoert: hell/dunkel und wie alt."""
    basis = 'http://%s/rpc/' % plug
    komp = json.load(urllib.request.urlopen(basis + 'Shelly.GetComponents?dynamic_only=true', timeout=10))
    hell = stufe = None
    for x in komp['components']:
        if x['key'].startswith('bthomesensor:'):
            if x['config']['obj_id'] == 30:
                hell = x['status'].get('value')
            if x['config']['obj_id'] == 100:
                stufe = x['status'].get('value')
    st = json.load(urllib.request.urlopen(basis + 'BTHomeDevice.GetStatus?id=%d' % geraet, timeout=10))
    return hell, stufe, st.get('packet_id'), st.get('last_updated_ts')


async def cmd_dump(args):
    c = await fassen()
    if not c:
        return print('keine Verbindung')
    try:
        for name, uuid in NUR_LESBAR.items():
            raw = await c.read_gatt_char(uuid)
            try:
                text = raw.decode()
                zeig = text if text.isprintable() else raw.hex()
            except Exception:
                zeig = '%s  = %d' % (raw.hex(), int.from_bytes(raw, 'little'))
            print('%-18s %s' % (name, zeig))
        for name, (uuid, breite) in FELDER.items():
            print('%-18s %d' % (name, await lesen(c, uuid, breite)))
        for i, uuid in enumerate(UNBEKANNT):
            print('unbekannt_%-8d %d   (%s)' % (i, await lesen(c, uuid, 1), uuid[:8]))
    finally:
        await c.disconnect()


async def cmd_set(args):
    if args.feld not in FELDER:
        return print('unbekanntes Feld. Bekannt:', ', '.join(FELDER))
    uuid, breite = FELDER[args.feld]
    c = await fassen()
    if not c:
        return print('keine Verbindung')
    try:
        vorher = await lesen(c, uuid, breite)
        await schreiben(c, uuid, breite, args.wert)
        await asyncio.sleep(0.4)
        nachher = await lesen(c, uuid, breite)
        print('%s: %d -> %d%s' % (args.feld, vorher, nachher,
                                  '' if nachher == args.wert else '   NICHT UEBERNOMMEN'))
    finally:
        await c.disconnect()


async def cmd_bisect(args):
    """Kreist die Helligkeit ein, indem beide Schwellen gemeinsam wandern.

    Der Sensor kennt zwei Schwellen, eine fuer den Weg nach dunkel und eine
    fuer den Weg nach hell. Dazwischen bleibt er stehen, wo er ist -- eine
    Hysterese, die eine Messung wertlos macht: er wuerde aus Traegheit auf
    dunkel stehenbleiben und wir hielten das fuer ein Ergebnis. Beide Schwellen
    auf denselben Wert zu setzen nimmt sie weg. Dann ist jeder Schritt ein
    einzelner Vergleich: ueber dem Wert hell, darunter dunkel, unabhaengig
    davon, wie er vorher stand.
    """
    dunkel_uuid, breite = FELDER['schwelle_dunkel']
    hell_uuid, _ = FELDER['schwelle_hell']

    lo, hi = args.von, args.bis
    print('Einkreisen zwischen %d und %d, %d Schritte.' % (lo, hi, args.schritte))
    print('Nach jedem Schritt: Knopf druecken, wenn das Skript darum bittet.\n')

    original = None
    for schritt in range(1, args.schritte + 1):
        pruef = (lo + hi) // 2
        print('Schritt %d/%d -- pruefe %d   (Klammer %d .. %d)'
              % (schritt, args.schritte, pruef, lo, hi), flush=True)
        c = await fassen()
        if not c:
            print('   keine Verbindung, abgebrochen')
            break
        try:
            if original is None:
                original = (await lesen(c, dunkel_uuid, breite),
                            await lesen(c, hell_uuid, breite))
                print('   urspruengliche Schwellen: dunkel %d, hell %d' % original, flush=True)
            await schreiben(c, dunkel_uuid, breite, pruef)
            await schreiben(c, hell_uuid, breite, pruef)
            await asyncio.sleep(0.4)
            print('   gesetzt: beide Schwellen auf %d' % pruef, flush=True)
        finally:
            await c.disconnect()

        # Erst getrennt funkt er wieder, und nur sein Funk verraet die
        # Entscheidung. Auf ein frisches Paket warten, nicht auf das alte.
        _, _, _, vorher_ts = plug_zustand(args.plug)
        print('   warte auf ein frisches Funkpaket ...', flush=True)
        hell = None
        for _ in range(args.geduld):
            time.sleep(5)
            hell, stufe, paket, ts = plug_zustand(args.plug)
            if ts != vorher_ts:
                print('   Sensor meldet: %s' % ('HELL' if hell else 'dunkel'), flush=True)
                break
        else:
            print('   kein frisches Paket -- Schritt uebersprungen', flush=True)
            continue

        if hell:
            hi = pruef      # Helligkeit liegt ueber dem Pruefwert
        else:
            lo = pruef
        print('   Klammer jetzt %d .. %d\n' % (lo, hi), flush=True)

    print('Ergebnis: die Helligkeit liegt zwischen %d und %d.' % (lo, hi))
    if original and args.zuruecksetzen:
        print('\nSetze die urspruenglichen Schwellen zurueck.')
        c = await fassen()
        if c:
            try:
                await schreiben(c, dunkel_uuid, breite, original[0])
                await schreiben(c, hell_uuid, breite, original[1])
                print('   dunkel %d, hell %d wiederhergestellt' % original)
            finally:
                await c.disconnect()


def main():
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest='cmd', required=True)

    sub.add_parser('dump', help='alle Einstellungen lesen')

    s = sub.add_parser('set', help='eine Einstellung schreiben')
    s.add_argument('feld', choices=sorted(FELDER))
    s.add_argument('wert', type=int)

    b = sub.add_parser('bisect', help='die Helligkeit einkreisen')
    b.add_argument('--plug', default='192.168.178.26',
                   help='Shelly, der den Sensor hoert')
    b.add_argument('--von', type=int, default=0)
    b.add_argument('--bis', type=int, default=1000)
    b.add_argument('--schritte', type=int, default=5)
    b.add_argument('--geduld', type=int, default=24,
                   help='wie viele Fuenf-Sekunden-Runden auf ein Funkpaket gewartet wird')
    b.add_argument('--zuruecksetzen', action='store_true',
                   help='am Ende die urspruenglichen Schwellen wiederherstellen')

    args = p.parse_args()
    asyncio.run({'dump': cmd_dump, 'set': cmd_set, 'bisect': cmd_bisect}[args.cmd](args))


if __name__ == '__main__':
    main()
