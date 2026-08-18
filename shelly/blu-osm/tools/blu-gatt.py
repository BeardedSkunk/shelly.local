"""Redet mit einem Shelly BLU H&T direkt, ohne die Shelly-App.

    python blu-gatt.py dump                      alles lesen
    python blu-gatt.py get zigbee                eine Einstellung lesen
    python blu-gatt.py set temp_offset 0         eine Einstellung schreiben
    python blu-gatt.py pin 123456                PIN schicken, Schluessel lesen
    python blu-gatt.py shell                     einmal verbinden, dann viele Befehle
    python blu-gatt.py bisect                    Helligkeit einkreisen
                                                 (ein Knopfdruck am Anfang)

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
Schwelle verstellt und zusieht, auf welcher Seite der Sensor landet. Ein
Knopfdruck zu Beginn, danach laeuft es allein -- jeder Schritt hoert ein
Funkpaket ab und greift im selben Fenster fuer den naechsten Schreibvorgang zu,
sodass ein Schritt ein Funkintervall kostet und nicht zwei.
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
    # Am Geraet durchprobiert und beobachtet, nicht hergeleitet. Die frueheren
    # Namen stammten aus einem Bluetooth-Mitschnitt und waren teils vertauscht:
    # was nach Fahrenheit aussah, schaltet einen Globus, und die beiden
    # Display-Schalter standen ueber Kreuz.
    'temp_offset':     ('0de178e5-a95d-4988-b042-7145d540a000', 2),  # Zehntelgrad, mit Vorzeichen
    'feuchte_offset':  ('0de178e5-a95d-4988-b042-7145d540a002', 2),  # ganze Prozent, mit Vorzeichen
    'schwelle_dunkel': ('c1a32099-32e8-42d8-99bb-b90ce4abe841', 2),
    'schwelle_hell':   ('c1a32099-32e8-42d8-99bb-b90ce4abe842', 2),
    # 0 Celsius, 1 Fahrenheit. In Fahrenheit dreht sich auch das Datum: erst
    # der Monat, dann der Tag.
    'fahrenheit':      ('8645a7a9-6bb6-41fa-a120-4034629c2519', 1),
    # 0 schwarz auf weiss, 1 invertiert.
    'invertieren':     ('611723f5-53dd-4289-888a-7523db56bb59', 1),
    # 0 vierundzwanzig Stunden, 1 zwoelf. Der einzige Wert, der sich auf dem
    # Display erst zeigt, wenn der Sensor den set-Bildschirm verlaesst -- alle
    # anderen schlagen sofort durch.
    'uhr12h':          ('a9e33a3f-0396-41e5-a7c4-30511ffba2ad', 1),
    # Schaltet ein Globus-Symbol ein und aus. Wofuer es steht, ist offen.
    'globus':          ('68348d04-f62c-435d-b075-cc54b9f049cc', 1),
    # Nicht die Sendefrequenz, sondern ein Zeitversatz: die Zahl wird in
    # Minuten auf die Uhrzeit aufgeschlagen, 65535 zieht eine Minute ab. In
    # manchen Betriebsarten ist die Uhrzeit leer und der BLU zaehlt hiermit --
    # wie sich die Uhrzeit loeschen laesst, damit das nutzbar wird, ist noch
    # offen.
    'zeitversatz':     ('08b83239-6f5e-4412-892d-81e59224716e', 2),
    # Unixzeit in Sekunden, vier Bytes little-endian. Millisekunden koennen es
    # nicht sein: vier Bytes fassen 4.294.967.295, und Millisekunden seit 1970
    # sind heute rund 1.786.000.000.000. Gelesen wurden b1847c6a, also
    # 1786545329 -- auf die Sekunde der Zeitpunkt des Lesens.
    #
    # Sichtbar aendert sich beim Schreiben nichts, weil eine falsche Zeit auch
    # eine gueltige ist: der Sensor uebernimmt sie und zeigt sie nur, wenn man
    # auf die Uhranzeige umschaltet -- drei Mal druecken.
    'epochSec':        ('d56a3410-115e-41d1-945b-3a7f189966a1', 4),
    # Zwei Schalter ohne sichtbare Wirkung. Uebrig sind Energiesparmodus,
    # Uhr-Synchronisierung, Zigbee und Sicherheit -- alles Dinge, die sich auf
    # einem Display auch nicht zeigen wuerden.
    'schalter_a':      ('317c7868-5889-4572-b6ef-2c436ee5a92a', 1),
    'schalter_b':      ('ca9d7a88-2ad3-4940-9b8b-75558d08a3b0', 1),
}

# Was der Knopf am Sensor selbst tut, unabhaengig von alldem:
#
#   2 mal   Celsius / Fahrenheit
#   3 mal   Datum statt Uhrzeit
#   4 mal   invertieren
#   5 mal   zwoelf / vierundzwanzig Stunden
#   6 bis 9 kein sichtbarer Unterschied

# Die Sicherheits-Eingabe. Nur beschreibbar, und als einzige Stelle im ganzen
# Geraet big-endian: die 123456 aus der App standen im Mitschnitt als 0001e240.
PIN_UUID = '0ffb7104-860c-49ae-8989-1f946d5f6c03'

# Sechzehn Bytes, beim Auslesen alle null. Sehr wahrscheinlich der
# BTHome-Verschluesselungsschluessel -- der Stecker meldet fuer diesen Sensor
# key:false, was dazu passt. Ob er sich nach einer PIN anders liest, ist genau
# die Frage, die 'pin' beantwortet.
SCHLUESSEL_UUID = 'eb0fb41b-af4b-4724-a6f9-974f55aba81a'

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


async def zeige_schluessel(c, wann):
    roh = await c.read_gatt_char(SCHLUESSEL_UUID)
    leer = all(b == 0 for b in roh)
    print('  Schluessel %s: %s%s' % (wann, roh.hex(), '   (alles null)' if leer else ''))
    return roh


async def cmd_pin(args):
    """Schickt die PIN und sieht nach, ob der Schluessel danach etwas hergibt.

    Die PIN geht als vier Bytes big-endian hinaus -- so stand sie im Mitschnitt,
    und es ist die einzige Stelle im Geraet, die nicht little-endian ist. Was
    sie freischaltet, weiss niemand; deshalb wird vorher und nachher gelesen und
    der Unterschied gezeigt, statt etwas zu behaupten.
    """
    c = await fassen(weg=args.weg)
    if not c:
        return print('keine Verbindung')
    try:
        vorher = await zeige_schluessel(c, 'vorher ')
        await c.write_gatt_char(PIN_UUID, args.pin.to_bytes(4, 'big'), response=False)
        print('  PIN geschickt: %d als %s (big-endian)'
              % (args.pin, args.pin.to_bytes(4, 'big').hex()))
        await asyncio.sleep(0.6)
        nachher = await zeige_schluessel(c, 'nachher')
        if vorher == nachher:
            print('  unveraendert -- die PIN aendert am Schluessel nichts,')
            print('  jedenfalls nichts, was sich lesen laesst.')
        else:
            print('  VERAENDERT.')
        # Und ob sonst irgendwo etwas aufgegangen ist.
        print('\n  was sonst zu lesen ist:')
        for name, (uuid, breite) in FELDER.items():
            try:
                print('    %-16s %d' % (name, await lesen(c, uuid, breite, name in VORZEICHEN)))
            except Exception as e:
                print('    %-16s <%s>' % (name, type(e).__name__))
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
    print('verbunden. Befehle: get <feld> | set <feld> <wert> | dump |'
          ' pin <zahl> | schluessel | ende')
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
                if befehl == 'pin' and len(teile) == 2:
                    await zeige_schluessel(c, 'vorher ')
                    wert = int(teile[1], 0)
                    await c.write_gatt_char(PIN_UUID, wert.to_bytes(4, 'big'), response=False)
                    print('  PIN geschickt: %d' % wert)
                    await asyncio.sleep(0.6)
                    await zeige_schluessel(c, 'nachher')
                    continue
                if befehl == 'schluessel':
                    await zeige_schluessel(c, 'jetzt  ')
                    continue
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


async def messen_und_fassen(sekunden=120):
    """Hoert ein Funkpaket ab und greift im selben Atemzug zu.

    Beides zusammen, weil beides dasselbe Fenster braucht: die Entscheidung
    hell/dunkel steht nur im Rundfunk und nirgends in GATT, und verbinden laesst
    sich der Sensor nur, waehrend er funkt. Getrennt gemacht kostet jeder
    Messschritt zwei Funkintervalle statt einem -- bei einem Sensor, der einmal
    pro Minute etwas sagt, ist das der Unterschied zwischen einer halben und
    einer ganzen Stunde.

    Zurueck kommt (Paket, Verbindung). Das Paket ist das erste nach dem Anfang
    des Zuhoerens und damit die Antwort auf die zuletzt gesetzte Schwelle. Die
    Verbindung kann fehlen, wenn das Fenster schon wieder zu war -- dann steht
    die Antwort trotzdem fest und nur der naechste Schreibzugriff muss warten.
    """
    q = asyncio.Queue()

    def gesehen(dev, adv):
        if dev.address.upper() != ADDR:
            return
        daten = adv.service_data.get(BTHOME_UUID)
        if daten:
            q.put_nowait((bthome_lesen(daten), dev))

    erstes = None
    sc = BleakScanner(detection_callback=gesehen)
    await sc.start()
    ende = asyncio.get_event_loop().time() + sekunden
    try:
        while asyncio.get_event_loop().time() < ende:
            try:
                paket, dev = await asyncio.wait_for(q.get(), timeout=5.0)
            except asyncio.TimeoutError:
                continue
            if erstes is None:
                erstes = paket
            try:
                c = BleakClient(dev, timeout=8.0)
                await c.connect()
                return erstes, c
            except Exception:
                continue  # Fenster verpasst, das naechste Paket kommt bestimmt
    finally:
        await sc.stop()
    return erstes, None


async def schwellen_setzen(c, wert):
    """Setzt beide Schwellen und liest nach, ob sie wirklich stehen.

    Nachlesen ist nicht Zierde: ein stillschweigend verworfener Schreibvorgang
    wuerde die naechste Messung zur Antwort auf die vorige machen, und die
    Einkreisung liefe in die falsche Richtung, ohne dass es auffiele.
    """
    dunkel_uuid, breite = FELDER['schwelle_dunkel']
    hell_uuid, _ = FELDER['schwelle_hell']
    await schreiben(c, dunkel_uuid, breite, wert)
    await schreiben(c, hell_uuid, breite, wert)
    await asyncio.sleep(0.4)
    return (await lesen(c, dunkel_uuid, breite),
            await lesen(c, hell_uuid, breite))


async def cmd_bisect(args):
    """Kreist die Helligkeit ein -- ein Knopfdruck am Anfang, dann von allein.

    Der Sensor gibt seinen Messwert nicht her, nur sein Urteil. Also wird die
    Schwelle verstellt und zugesehen, auf welche Seite er faellt: liegt sie
    unter der Helligkeit, meldet er hell, darueber dunkel. Jeder Schritt
    halbiert die Klammer.

    Beide Schwellen wandern gemeinsam auf denselben Wert, und das mit Absicht.
    Der Sensor hat eine fuer den Weg nach dunkel und eine fuer den Weg nach
    hell, und dazwischen bleibt er aus Traegheit stehen, wo er war -- eine
    Hysterese, die jede Messung wertlos machen wuerde. Gleiche Schwellen nehmen
    sie weg: dann ist jeder Schritt ein einzelner Vergleich, unabhaengig davon,
    wie er vorher stand.

    Am Ende stehen die alten Schwellen wieder da, ausser man sagt --behalten.
    Zwei gleiche Schwellen sind kein Zustand, in dem man einen Sensor laesst.
    """
    lo, hi = args.von, args.bis

    print('Einkreisen zwischen %d und %d, hoechstens %d Schritte.'
          % (lo, hi, args.schritte))
    print('Jetzt ein Mal den Knopf am Sensor druecken. Danach laeuft alles von')
    print('allein -- weitere Knopfdruecke braucht das Skript nicht.')
    print('Und nebenher kein zweites BLE-Programm auf denselben Sensor.\n',
          flush=True)

    c = await fassen(minuten=args.warten, weg=args.weg)
    if not c:
        return print('keine Verbindung. Knopf gedrueckt? Laeuft sonst noch'
                     ' etwas ueber Bluetooth gegen diesen Sensor?')

    dunkel_uuid, breite = FELDER['schwelle_dunkel']
    hell_uuid, _ = FELDER['schwelle_hell']
    original = (await lesen(c, dunkel_uuid, breite),
                await lesen(c, hell_uuid, breite))
    print('verbunden. Schwellen vorher: dunkel %d, hell %d\n' % original,
          flush=True)

    schritt = 0
    versuche = 0
    try:
        while schritt < args.schritte and hi - lo > args.genau:
            schritt += 1
            versuche += 1
            if versuche > args.schritte * 3:
                print('zu viele Fehlversuche -- abgebrochen')
                break
            pruef = (lo + hi) // 2
            print('Schritt %d/%d -- pruefe %d   (Klammer %d .. %d)'
                  % (schritt, args.schritte, pruef, lo, hi), flush=True)

            if c is None:
                print('   warte auf ein Fenster zum Schreiben ...', flush=True)
                c = await fassen(minuten=args.warten, weg=args.weg)
                if c is None:
                    print('   keine Verbindung mehr -- abgebrochen')
                    break
            try:
                zurueck = await schwellen_setzen(c, pruef)
            except Exception as e:
                print('   Verbindung weg (%s) -- neuer Anlauf\n'
                      % type(e).__name__, flush=True)
                c = None
                schritt -= 1
                continue
            if zurueck != (pruef, pruef):
                print('   Schwellen nicht uebernommen (dunkel %d, hell %d)'
                      ' -- neuer Anlauf\n' % zurueck, flush=True)
                schritt -= 1
                continue

            await c.disconnect()
            c = None
            await asyncio.sleep(1.0)  # der Funk kommt erst nach dem Trennen

            print('   gesetzt. warte auf sein naechstes Funkpaket ...',
                  flush=True)
            paket, c = await messen_und_fassen(args.geduld)
            if paket is None or 0x1e not in paket:
                print('   kein verwertbares Paket -- Schritt wiederholt\n',
                      flush=True)
                schritt -= 1
                continue

            hell = bool(paket[0x1e])
            print('   Sensor meldet: %s   (%.1f C, %d %%, Batterie %d %%)'
                  % ('HELL' if hell else 'dunkel',
                     paket.get(0x45, 0) / 10.0, paket.get(0x2e, 0),
                     paket.get(0x01, 0)), flush=True)

            # Hell heisst: die Helligkeit liegt ueber dem Pruefwert. Dann ist
            # der Pruefwert die neue Untergrenze, nicht die neue Obergrenze.
            if hell:
                lo = pruef
            else:
                hi = pruef
            print('   Klammer jetzt %d .. %d\n' % (lo, hi), flush=True)

        print('Ergebnis: die Helligkeit liegt zwischen %d und %d.' % (lo, hi))
        if lo == args.von:
            print('   Sie kann auch darunter liegen -- nach unten wurde die')
            print('   Klammer nie verlassen. Mit --von tiefer ansetzen.')
        if hi == args.bis:
            print('   Sie kann auch darueber liegen -- nach oben wurde die')
            print('   Klammer nie verlassen. Mit --bis hoeher ansetzen.')
    finally:
        if args.behalten:
            print('\nDie Schwellen bleiben stehen, wo die Suche sie gelassen'
                  ' hat.')
        else:
            print('\nSetze die urspruenglichen Schwellen zurueck'
                  ' (dunkel %d, hell %d).' % original, flush=True)
            if c is None:
                _, c = await messen_und_fassen(args.geduld)
            if c is None:
                c = await fassen(minuten=args.warten, weg=args.weg)
            if c is None:
                print('   KEINE VERBINDUNG -- die Schwellen stehen noch auf')
                print('   dem letzten Pruefwert. Bitte nachholen:')
                print('     python blu-gatt.py set schwelle_dunkel %d'
                      % original[0])
                print('     python blu-gatt.py set schwelle_hell %d'
                      % original[1])
            else:
                await schreiben(c, dunkel_uuid, breite, original[0])
                await schreiben(c, hell_uuid, breite, original[1])
                await asyncio.sleep(0.4)
                nach = (await lesen(c, dunkel_uuid, breite),
                        await lesen(c, hell_uuid, breite))
                print('   jetzt: dunkel %d, hell %d%s'
                      % (nach[0], nach[1],
                         '' if nach == original else '   NICHT UEBERNOMMEN'))
        if c is not None:
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

    pn = mit_weg(sub.add_parser('pin', help='PIN schicken und den Schluessel lesen'))
    pn.add_argument('pin', type=int)

    b = mit_weg(sub.add_parser('bisect', help='die Helligkeit einkreisen'))
    b.add_argument('--von', type=int, default=0)
    b.add_argument('--bis', type=int, default=65535,
                   help='zwei Bytes fasst das Feld, mehr geht nicht')
    b.add_argument('--schritte', type=int, default=16,
                   help='16 halbieren 65535 bis auf eins herunter')
    b.add_argument('--genau', type=int, default=1,
                   help='Schluss, sobald die Klammer so eng ist')
    b.add_argument('--geduld', type=int, default=120,
                   help='Sekunden, die auf ein Funkpaket gewartet wird')
    b.add_argument('--warten', type=float, default=5.0,
                   help='Minuten, die auf die erste Verbindung gewartet wird')
    b.add_argument('--behalten', action='store_true',
                   help='die gefundenen Schwellen stehen lassen statt die'
                        ' urspruenglichen zurueckzuschreiben')

    args = p.parse_args()
    asyncio.run({'dump': cmd_dump, 'get': cmd_get, 'set': cmd_set, 'pin': cmd_pin,
                 'shell': cmd_shell, 'bisect': cmd_bisect}[args.cmd](args))


if __name__ == '__main__':
    main()
