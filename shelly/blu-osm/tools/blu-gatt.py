"""Redet mit einem Shelly BLU H&T direkt, ohne die Shelly-App.

    python blu-gatt.py dump                      alles lesen
    python blu-gatt.py get zigbee                eine Einstellung lesen
    python blu-gatt.py set temp_offset 0         eine Einstellung schreiben
    python blu-gatt.py shell                     einmal verbinden, dann viele Befehle
    python blu-gatt.py bisect                    Helligkeit einkreisen

Warum es nie sofort geht: der Sensor funkt einmal pro Minute und schweigt
dazwischen. Eine Verbindung kann nur zustande kommen, waehrend er funkt -- das
ist BLE, kein Mangel des Werkzeugs. Ein Direktversuch ueber die Adresse wurde
am 12.08.2026 nach 133 Sekunden aufgegeben. Wer viel vorhat, nimmt 'shell' und
zahlt die Wartezeit ein einziges Mal; wer es dauerhaft schneller will,
verkuerzt das Sendeintervall im Sensor.

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

Vor der Kopplung ist der Knopfdruck die Voraussetzung: ein ungekoppelter BLU
funkt nicht verbindbar, das spart ihm die Batterie, und erst der Druck oeffnet
ein Fenster. Danach nicht mehr -- ein gekoppelter Sensor laesst sich auch im
Normalbetrieb ansprechen, ohne dass "set" im Display steht. Steht die
Verbindung einmal, haelt sie: drei Minuten und 64 Lesevorgaenge am Stueck.

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
import sys

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
    'uhrzeit':         ('d56a3410-115e-41d1-945b-3a7f189966a1', 4),
    'intervall':       ('08b83239-6f5e-4412-892d-81e59224716e', 2),
    # ACHTUNG: die drei folgenden Namen sind aus dem Mitschnitt hergeleitet und
    # nicht am Geraet geprueft. Am 12.08.2026 hat sich gezeigt, dass 'fahrenheit'
    # in Wahrheit ein Globus-Symbol auf dem Display schaltet -- die uebrigen
    # Zuordnungen hingen an derselben Kette und sind damit ebenso fraglich. Sie
    # stehen hier, bis jemand sie am Geraet durchprobiert und richtigstellt.
    'fahrenheit':      ('68348d04-f62c-435d-b075-cc54b9f049cc', 1),
    'invertieren':     ('8645a7a9-6bb6-41fa-a120-4034629c2519', 1),
    'zigbee':          ('611723f5-53dd-4289-888a-7523db56bb59', 1),
    'schalter_a':      ('317c7868-5889-4572-b6ef-2c436ee5a92a', 1),
    'schalter_b':      ('ca9d7a88-2ad3-4940-9b8b-75558d08a3b0', 1),
    'schalter_c':      ('a9e33a3f-0396-41e5-a7c4-30511ffba2ad', 1),
}

# Warum die Schalter keine Namen tragen
# -------------------------------------
# Sie hatten welche, aus dem Mitschnitt abgeleitet: drei Schreibvorgaenge auf 1
# und zwei auf 0 in einem Durchgang, in dem drei Einstellungen eingeschaltet
# und zwei ausgeschaltet wurden. Das teilt die fuenf sauber in zwei Gruppen,
# und ein Anker aus dem ersten Durchgang schien den Rest zu bestimmen.
#
# Am Geraet nachgesehen war der Anker falsch: das Merkmal, das ich fuer
# Fahrenheit hielt, schaltet ein Globus-Symbol. Damit fiel die ganze Kette, denn
# jede weitere Zuordnung hing daran. Eine Herleitung, deren erster Schritt nicht
# geprueft ist, ist keine.
#
# Zuordnen laesst sich das in Sekunden, jetzt wo sie schreibbar sind: einen
# umlegen, aufs Display und in die App schauen, den Namen eintragen. Was dabei
# herauskommt, ist beobachtet und nicht hergeleitet.

NUR_LESBAR = {
    'firmware':  '00002a26-0000-1000-8000-00805f9b34fb',
    'hersteller': '00002a29-0000-1000-8000-00805f9b34fb',
    'spannung_a': '8f8e2438-535d-478d-af0f-c3692c3c1bb1',
    'spannung_b': '8f8e2438-535d-478d-af0f-c3692c3c1bb2',
}


async def fassen(minuten=5.0, weg=1):
    """Verbindet sich, auf einem von mehreren Wegen.

    weg 1  lauschen und im selben Moment zugreifen. Zuverlaessig, dauert bis
           zu einer Minute -- so lange schweigt der Sensor zwischen zwei
           Funkpaketen, und ohne Funkpaket nimmt niemand eine Verbindung an.
    weg 2  geradeheraus ueber die Adresse. Windows soll selbst warten. Am
           12.08.2026 gemessen: nach 133 Sekunden aufgegeben, also nutzlos --
           bleibt drin, weil es auf anderer Hardware anders sein kann.
    weg 3  beides zugleich: lauschen, und parallel geradeheraus versuchen.
           Was zuerst durchkommt, gewinnt.

    Schneller als das Funkintervall geht keiner davon. Wer es wirklich schnell
    braucht, verkuerzt das Intervall im Sensor selbst -- siehe 'intervall' --
    oder haelt die Verbindung offen, siehe 'shell'.
    """
    if weg == 2:
        try:
            c = BleakClient(ADDR, timeout=minuten * 60)
            await c.connect()
            return c
        except Exception:
            return None
    if weg == 3:
        direkt = asyncio.create_task(_direkt(minuten))
        lauschen = asyncio.create_task(_lauschen(minuten))
        fertig, offen = await asyncio.wait({direkt, lauschen},
                                           return_when=asyncio.FIRST_COMPLETED)
        for t in offen:
            t.cancel()
        for t in fertig:
            if t.result() is not None:
                return t.result()
        return None
    return await _lauschen(minuten)


async def _direkt(minuten):
    try:
        c = BleakClient(ADDR, timeout=minuten * 60)
        await c.connect()
        return c
    except Exception:
        return None


async def _lauschen(minuten=5.0):
    """Verbindet sich mit dem Sensor.

    Zuerst geradeheraus ueber die Adresse: ein gekoppelter Sensor laesst sich so
    ansprechen, ohne auf ein Funkpaket zu warten und ohne dass jemand einen
    Knopf druecken muss. Das ist der Normalfall und dauert Sekunden.

    Klappt das nicht, wird gewartet, bis er sich von selbst meldet, und in
    derselben Sekunde zugegriffen. Der Suchlauf laeuft dabei durchgehend statt
    in Runden: zwischen zwei Laeufen ist der Adapter blind, und der Sensor funkt
    nur einmal pro Minute. Mit Start-Stop-Start wurden 24 Versuche gebraucht und
    ein einziges Paket gehoert.
    """
    try:
        c = BleakClient(ADDR, timeout=12.0)
        await c.connect()
        return c
    except Exception:
        pass

    q = asyncio.Queue()

    def gesehen(dev, adv):
        if dev.address.upper() == ADDR:
            q.put_nowait((dev, adv.rssi))

    sc = BleakScanner(detection_callback=gesehen)
    await sc.start()
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
                return c
            except Exception:
                pass
    finally:
        await sc.stop()
    return None


# Die Offsets duerfen negativ sein -- ein Sensor, der zu warm liest, braucht
# genau das. Auf dem Draht sind es zwei Bytes im Zweierkomplement, und weil ein
# Offset nie in die Naehe von 32767 Zehntelgrad kommt, ist die Grenze zwischen
# "grosse Zahl" und "negative Zahl" hier ungefaehrlich zu ziehen.
VORZEICHEN = {'temp_offset', 'feuchte_offset'}


async def lesen(c, uuid, breite, mit_vorzeichen=False):
    raw = await c.read_gatt_char(uuid)
    return int.from_bytes(raw[:breite], 'little', signed=mit_vorzeichen)


async def schreiben(c, uuid, breite, wert):
    roh = wert.to_bytes(breite, 'little', signed=wert < 0)
    await c.write_gatt_char(uuid, roh, response=False)


BTHOME_UUID = '0000fcd2-0000-1000-8000-00805f9b34fb'

# Wie breit ein BTHome-Objekt ist, soweit dieser Sensor sie sendet. Gebraucht
# wird nur, ueber die unbekannten hinwegzukommen, um an 0x1e zu gelangen.
BREITEN = {0x00: 1, 0x01: 1, 0x1e: 1, 0x2e: 1, 0x40: 2, 0x45: 2, 0x64: 1}


def bthome_lesen(daten):
    """Zerlegt die Dienstdaten und gibt zurueck, was drinsteht.

    Aufbau: ein Kopfbyte, dann Objekte aus Kennung und Wert. Was uns
    interessiert, ist 0x1e -- hell oder dunkel. Der gemessene Helligkeitswert
    steht nirgends, weder hier noch ueber GATT; der Sensor gibt nur seine
    Entscheidung her.
    """
    aus = {}
    i = 1  # Kopfbyte ueberspringen
    while i < len(daten):
        kennung = daten[i]
        breite = BREITEN.get(kennung)
        if breite is None:
            break  # unbekanntes Objekt: ab hier ist die Laenge nicht mehr bekannt
        aus[kennung] = int.from_bytes(daten[i + 1:i + 1 + breite], 'little')
        i += 1 + breite
    return aus


async def rundfunk_abwarten(sekunden=90):
    """Wartet auf das naechste Funkpaket und liest hell/dunkel daraus.

    Der Sensor funkt nicht, solange eine Verbindung steht -- also erst trennen,
    dann zuhoeren. Das geht vom selben Rechner aus; ein Shelly als Zuhoerer ist
    dafuer nicht noetig, auch wenn er es koennte.
    """
    ergebnis = asyncio.Queue()

    def gesehen(dev, adv):
        if dev.address.upper() != ADDR:
            return
        daten = adv.service_data.get(BTHOME_UUID)
        if daten:
            ergebnis.put_nowait(bthome_lesen(daten))

    sc = BleakScanner(detection_callback=gesehen)
    await sc.start()
    try:
        return await asyncio.wait_for(ergebnis.get(), timeout=sekunden)
    except asyncio.TimeoutError:
        return None
    finally:
        await sc.stop()


async def cmd_dump(args):
    c = await fassen(weg=getattr(args, 'weg', 1))
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
            print('%-18s %d' % (name, await lesen(c, uuid, breite, name in VORZEICHEN)))
    finally:
        await c.disconnect()


async def cmd_get(args):
    uuid, breite = FELDER[args.feld]
    c = await fassen(weg=getattr(args, 'weg', 1))
    if not c:
        return print('keine Verbindung')
    try:
        print('%s = %d' % (args.feld, await lesen(c, uuid, breite, args.feld in VORZEICHEN)))
    finally:
        await c.disconnect()


async def cmd_set(args):
    if args.feld not in FELDER:
        return print('unbekanntes Feld. Bekannt:', ', '.join(FELDER))
    uuid, breite = FELDER[args.feld]
    c = await fassen(weg=getattr(args, 'weg', 1))
    if not c:
        return print('keine Verbindung')
    try:
        vz = args.feld in VORZEICHEN
        vorher = await lesen(c, uuid, breite, vz)
        await schreiben(c, uuid, breite, args.wert)
        await asyncio.sleep(0.4)
        nachher = await lesen(c, uuid, breite, vz)
        print('%s: %d -> %d%s' % (args.feld, vorher, nachher,
                                  '' if nachher == args.wert else '   NICHT UEBERNOMMEN'))
    finally:
        await c.disconnect()


async def cmd_shell(args):
    """Verbindet einmal und nimmt danach beliebig viele Befehle entgegen.

    Das Warten kostet einmal bis zu einer Minute, danach nichts mehr: die
    Verbindung bleibt offen und jedes get und set geht in Millisekunden. Fuer
    ein Durchprobieren aller Schalter ist das der einzige ertraegliche Weg --
    sonst zahlt man die Wartezeit ein Dutzend Mal.

    Gemessen: eine Verbindung hielt drei Minuten und 64 Zugriffe am Stueck.
    Bricht sie ab, sagt es das und beendet sich, statt still nichts mehr zu tun.
    """
    print('verbinde einmal, danach geht alles sofort ...', flush=True)
    c = await fassen(weg=args.weg)
    if not c:
        return print('keine Verbindung')
    print('verbunden. Befehle: "get <feld>", "set <feld> <wert>", "dump", "ende"')
    print('Felder:', ', '.join(sorted(FELDER)), flush=True)
    try:
        while True:
            try:
                zeile = input('blu> ').strip()
            except (EOFError, KeyboardInterrupt):
                break
            if not zeile:
                continue
            teile = zeile.split()
            befehl = teile[0].lower()
            try:
                if befehl in ('ende', 'quit', 'exit'):
                    break
                if befehl == 'dump':
                    for name, (uuid, breite) in FELDER.items():
                        print('  %-18s %d' % (name, await lesen(c, uuid, breite,
                                                                name in VORZEICHEN)))
                elif befehl == 'get' and len(teile) == 2 and teile[1] in FELDER:
                    uuid, breite = FELDER[teile[1]]
                    print('  %s = %d' % (teile[1],
                                         await lesen(c, uuid, breite, teile[1] in VORZEICHEN)))
                elif befehl == 'set' and len(teile) == 3 and teile[1] in FELDER:
                    uuid, breite = FELDER[teile[1]]
                    vz = teile[1] in VORZEICHEN
                    wert = int(teile[2], 0)
                    vorher = await lesen(c, uuid, breite, vz)
                    await schreiben(c, uuid, breite, wert)
                    await asyncio.sleep(0.3)
                    nachher = await lesen(c, uuid, breite, vz)
                    print('  %s: %d -> %d%s' % (teile[1], vorher, nachher,
                                                '' if nachher == wert else '   NICHT UEBERNOMMEN'))
                else:
                    print('  ?  get <feld> | set <feld> <wert> | dump | ende')
            except Exception as e:
                print('  Verbindung weg (%s) -- neu starten' % type(e).__name__)
                break
    finally:
        await c.disconnect()
        print('getrennt')


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
        c = await fassen(weg=getattr(args, 'weg', 1))
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
        # Entscheidung -- ueber GATT ist sie nirgends zu lesen.
        print('   warte auf sein naechstes Funkpaket ...', flush=True)
        paket = await rundfunk_abwarten(args.geduld)
        if paket is None or 0x1e not in paket:
            print('   kein verwertbares Paket -- Schritt uebersprungen\n', flush=True)
            continue
        hell = bool(paket[0x1e])
        print('   Sensor meldet: %s   (%.1f C, %d %%)'
              % ('HELL' if hell else 'dunkel',
                 paket.get(0x45, 0) / 10.0, paket.get(0x2e, 0)), flush=True)

        if hell:
            hi = pruef      # Helligkeit liegt ueber dem Pruefwert
        else:
            lo = pruef
        print('   Klammer jetzt %d .. %d\n' % (lo, hi), flush=True)

    print('Ergebnis: die Helligkeit liegt zwischen %d und %d.' % (lo, hi))
    if original and args.zuruecksetzen:
        print('\nSetze die urspruenglichen Schwellen zurueck.')
        c = await fassen(weg=getattr(args, 'weg', 1))
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

    def mit_weg(p):
        p.add_argument('--weg', type=int, default=1, choices=(1, 2, 3),
                       help='1 lauschen, 2 geradeheraus, 3 beides zugleich')
        return p

    mit_weg(sub.add_parser('dump', help='alle Einstellungen lesen'))

    g = mit_weg(sub.add_parser('get', help='eine Einstellung lesen'))
    g.add_argument('feld', choices=sorted(FELDER))

    s = mit_weg(sub.add_parser('set', help='eine Einstellung schreiben'))
    s.add_argument('feld', choices=sorted(FELDER))
    s.add_argument('wert', type=int)

    mit_weg(sub.add_parser('shell', help='einmal verbinden, dann viele Befehle'))

    b = sub.add_parser('bisect', help='die Helligkeit einkreisen')
    b.add_argument('--von', type=int, default=0)
    b.add_argument('--bis', type=int, default=1000)
    b.add_argument('--schritte', type=int, default=5)
    b.add_argument('--geduld', type=int, default=90,
                   help='Sekunden, die auf ein Funkpaket gewartet wird')
    b.add_argument('--zuruecksetzen', action='store_true',
                   help='am Ende die urspruenglichen Schwellen wiederherstellen')

    args = p.parse_args()
    asyncio.run({'dump': cmd_dump, 'get': cmd_get, 'set': cmd_set,
                 'shell': cmd_shell, 'bisect': cmd_bisect}[args.cmd](args))


if __name__ == '__main__':
    main()
