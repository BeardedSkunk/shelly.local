"""Redet mit einem Shelly BLU H&T direkt, ohne die Shelly-App.

    python blu-gatt.py fields          Feldnamen und Kurzformen zeigen
    python blu-gatt.py listen          nur zuhoeren, nichts anfassen
    python blu-gatt.py dump            alle Einstellungen lesen
    python blu-gatt.py get bright      eine Einstellung lesen
    python blu-gatt.py set toff 0      eine Einstellung schreiben
    python blu-gatt.py gatt            alle Merkmale auflisten
    python blu-gatt.py key             den 16-Byte-Schluessel lesen
    python blu-gatt.py pin 123456      PIN schicken, dann den Schluessel lesen
    python blu-gatt.py shell           einmal verbinden, dann viele Befehle
    python blu-gatt.py probe 40        eine Schwelle, viele Pakete
    python blu-gatt.py track           der Helligkeit folgen, auch bewegter
    python blu-gatt.py bisect          Helligkeit einkreisen (nur stillstehend)

Jeder dieser Befehle laeuft auch einzeln, ohne die shell -- die shell spart nur
die Wartezeit auf die Verbindung, wenn man mehrere hintereinander braucht.

Die Feldnamen folgen der Merkmalstabelle des Herstellers. Jedes hat eine kurze
Form, und zwar ein Wort und keine Anfangsbuchstaben: "dark" statt "dt", weil
man sich das eine nach einer Woche noch merkt und das andere nicht. Beide
Formen gelten ueberall. 'fields' zeigt die Tabelle und braucht dafuer weder
Bluetooth noch Sensor.

Warum es nie sofort geht: der Sensor funkt einmal pro Minute und schweigt
dazwischen. Eine Verbindung kann nur zustande kommen, waehrend er funkt -- das
ist BLE, kein Mangel des Werkzeugs. Ein Direktversuch ueber die Adresse wurde
am 12.08.2026 nach 133 Sekunden aufgegeben. Wer viel vorhat, nimmt 'shell' und
zahlt die Wartezeit ein einziges Mal. Kuerzer als eine Minute geht nicht: der
Takt liegt fest, und die einzigen Ereignisse, die ihn ueberholen, sind ein
Knopfdruck und eine Batterie unter 15 Prozent.

Voraussetzungen: bleak (pip install bleak), eine bestehende Bluetooth-Kopplung
zwischen diesem Rechner und dem Sensor, und ein Knopfdruck pro Verbindung.
'listen' braucht nichts davon -- Empfangen merkt die Gegenseite nicht.

Woher das Wissen stammt
-----------------------
Aus einem Bluetooth-Mitschnitt des Handys vom 12.08.2026: Entwickleroptionen,
HCI-Snoop-Protokoll, in der Shelly-App jede Einstellung einmal angefasst, dann
`adb bugreport` und die Sitzung entschluesselt. Das Ergebnis ist erfreulich
schlicht -- gewoehnliches GATT, keine Verschluesselung, keine Signatur, keine
Cloud. Ein Schreibvorgang ist ein ATT Write Command auf ein festes Merkmal,
Zahlen little-endian, Ja/Nein als einzelnes Byte.

Am 18.08.2026 kam die Merkmalstabelle des Herstellers dazu und bestaetigte
jede UUID:
<https://shelly-api-docs.shelly.cloud/docs-ble/Devices/BLU_ZB/ht_display/>

Vor der Kopplung ist der Knopfdruck die Voraussetzung: ein ungekoppelter BLU
funkt nicht verbindbar, das spart ihm die Batterie, und erst der Druck oeffnet
ein Fenster. Danach nicht mehr -- ein gekoppelter Sensor laesst sich auch im
Normalbetrieb ansprechen, ohne dass "set" im Display steht. Steht die
Verbindung einmal, haelt sie: drei Minuten und 64 Lesevorgaenge am Stueck.

Das Licht, und was daran offen ist
----------------------------------
Ueber GATT gibt der Sensor die Helligkeit nicht her. Zwei nur lesbare Merkmale
sahen danach aus und waren es nicht -- es sind die beiden Zellen in Hundertstel
Volt, was die Tabelle bestaetigt hat.

Im Funk steht sie sehr wohl, als Objekt 0x64, und 'listen' druckt sie. Womit
sie zusammenhaengt, ist aber noch nicht geklaert. Am 18.08.2026 meldete der
Sensor 0x64 = 126 und blieb dabei bei "dunkel", waehrend die Schwelle Schritt
fuer Schritt von 32767 auf 63 fiel. Erklaerbar ist das durch dreierlei, und
welches zutrifft, weiss niemand:

  * es ist dort tatsaechlich dunkler als 63 -- ab Werk gilt schon alles unter
    50 als dunkel, das ist Zimmerdaemmerung und kein finsterer Keller;
  * geschriebene Schwellen werden gespeichert, aber nicht sofort angewendet;
  * die Entscheidung wird selten neu gefaellt, nicht einmal je Paket.

Eine vierte Moeglichkeit ist ausgeschlossen, und zwar durch den ersten Schritt
selbst: bei Schwelle 32767 muss das Geraet "dunkel" entscheiden, wie hell es
auch sei, und es meldete Bit 0. Also ist 0 = dunkel und das Bit nicht
verdreht.

Zwei Werkzeuge fuer den Rest. 'probe' setzt eine Schwelle und sieht ihr mehrere
Pakete lang zu -- aendert sich das Urteil erst beim dritten, lag es an der Zeit
und bisect misst zu frueh. Und 'bisect' fuehrt Protokoll ueber Schwelle, 0x64
und Urteil und zeigt am Ende beide Spalten nebeneinander; wo es umschlaegt,
steht das Verhaeltnis der beiden Skalen. Ein Knopfdruck zu Beginn, danach
laeuft es allein; jeder Schritt kostet ein Funkintervall, weil Zuhoeren und
Zugreifen dasselbe Fenster benutzen.
"""

import argparse
import asyncio
import sys
import time

try:
    from bleak import BleakClient, BleakScanner
except ImportError:
    sys.exit('bleak fehlt:  python -m pip install bleak')

ADDR = 'FC:4D:6A:38:E2:F2'

# Name -> (UUID, Breite in Bytes). Die Zuordnung stammt aus dem Mitschnitt:
# jede Einstellung einmal in der App geaendert, mit auffaelligen Zahlen, und
# im Protokoll nachgesehen, welches Merkmal sich bewegt hat.
FIELDS = {
    # name: (uuid, bytes, signed, note). The names follow the manufacturer's
    # own characteristic table; the short forms are for typing at the shell.
    'temp_offset':      ('0de178e5-a95d-4988-b042-7145d540a000', 2, True,
                         '0.1 C steps, default 0'),
    'humidity_offset':  ('0de178e5-a95d-4988-b042-7145d540a002', 2, True,
                         '1 % steps, default 0'),
    'dark_threshold':   ('c1a32099-32e8-42d8-99bb-b90ce4abe841', 2, False,
                         'default 50, roughly lux'),
    'bright_threshold': ('c1a32099-32e8-42d8-99bb-b90ce4abe842', 2, False,
                         'default 500, roughly lux'),
    'temp_unit':        ('8645a7a9-6bb6-41fa-a120-4034629c2519', 1, False,
                         '0 Celsius, 1 Fahrenheit -- and the date flips too'),
    'invert_display':   ('611723f5-53dd-4289-888a-7523db56bb59', 1, False,
                         '0 black on white, 1 inverted'),
    'clock_12h':        ('a9e33a3f-0396-41e5-a7c4-30511ffba2ad', 1, False,
                         '0 is 24 hours. Shows only after leaving set mode'),
    'zigbee':           ('68348d04-f62c-435d-b075-cc54b9f049cc', 1, False,
                         'the globe on the display is its indicator'),
    'time_sync':        ('317c7868-5889-4572-b6ef-2c436ee5a92a', 1, False,
                         'default 1. Invisible on the device'),
    'power_saver':      ('ca9d7a88-2ad3-4940-9b8b-75558d08a3b0', 1, False,
                         'default 0. Invisible on the device'),
    # Minutes added to the clock, and it is the time zone, not a send
    # interval: 65535 reads as -1 and takes a minute off.
    'utc_offset':       ('08b83239-6f5e-4412-892d-81e59224716e', 2, True,
                         'time zone in minutes'),
    # Seconds since 1970, four bytes little-endian. Milliseconds it cannot be:
    # four bytes hold 4,294,967,295 and milliseconds since 1970 are around
    # 1,786,000,000,000 today. Writing shows nothing, because a wrong time is
    # still a valid one -- press three times to see the clock at all.
    'unix_time':        ('d56a3410-115e-41d1-945b-3a7f189966a1', 4, False,
                         'UTC seconds'),
}

# A short form per field. Words, not initials -- "dark" and "toff" can be
# recalled a week later, "dt" and "to" cannot, and a short form nobody
# remembers saves nothing.
SHORT = {
    'toff':   'temp_offset',
    'hoff':   'humidity_offset',
    'dark':   'dark_threshold',
    'bright': 'bright_threshold',
    'unit':   'temp_unit',
    'invert': 'invert_display',
    'clock':  'clock_12h',
    'sync':   'time_sync',
    'power':  'power_saver',
    'tz':     'utc_offset',
    'time':   'unix_time',
}

FELDER = dict([(n, (u, b)) for n, (u, b, _, _) in FIELDS.items()])
VORZEICHEN = set([n for n, (_, _, vz, _) in FIELDS.items() if vz])
ERLAUBT = sorted(FIELDS) + sorted(SHORT)


def feld(name):
    """Loest eine Kurzform auf den vollen Feldnamen auf."""
    return SHORT.get(name, name)


def feld_liste(einzug='  '):
    kurz = dict([(v, k) for k, v in SHORT.items()])
    zeilen = ['%s%-8s %-17s %s' % (einzug, 'short', 'name', 'what it is')]
    for name, (uuid, breite, vz, note) in FIELDS.items():
        zeilen.append('%s%-8s %-17s %s'
                      % (einzug, kurz.get(name, ''), name, note))
    return '\n'.join(zeilen)


# Was der Knopf am Sensor selbst tut. Am Geraet durchprobiert und spaeter in
# der Herstellerdoku Zeile fuer Zeile wiedergefunden -- beides stimmt ueberein:
#
#   1 mal   set-Modus
#   2 mal   Datum statt Uhrzeit
#   3 mal   Celsius / Fahrenheit
#   4 mal   invertieren
#   5 mal   zwoelf / vierundzwanzig Stunden
#
# und im set-Modus, also nach dem ersten Druck:
#
#   4 mal   Bluetooth-Kopplung
#   5 mal   Zigbee-Anmeldung
#
# Deshalb war "6 bis 9 mal kein Unterschied" richtig beobachtet: mehr gibt es
# nicht. Waehrend die Kopplung laeuft, blinkt die Leuchte eine Minute lang
# einmal alle zwei Sekunden -- das ist die Anzeige, die wir am Display vermisst
# haben.

# Die Sicherheits-Eingabe. Nur beschreibbar, und als einzige Stelle im ganzen
# Geraet big-endian: die 123456 aus der App standen im Mitschnitt als 0001e240.
PIN_UUID = '0ffb7104-860c-49ae-8989-1f946d5f6c03'

# Sechzehn Bytes, beim Auslesen alle null. Sehr wahrscheinlich der
# BTHome-Verschluesselungsschluessel -- der Stecker meldet fuer diesen Sensor
# key:false, was dazu passt. Ob er sich nach einer PIN anders liest, ist genau
# die Frage, die 'pin' beantwortet.
SCHLUESSEL_UUID = 'eb0fb41b-af4b-4724-a6f9-974f55aba81a'

# Werksreset: eine 1 hierhin und das Geraet ist leer. Absichtlich nur als
# Konstante und ohne Befehl -- es gibt keinen Grund, so etwas bequem zu machen.
WERKSRESET_UUID = 'b0a7e40f-2b87-49db-801c-eb3686a24bdb'

NUR_LESBAR = {
    'firmware':  '00002a26-0000-1000-8000-00805f9b34fb',
    'hersteller': '00002a29-0000-1000-8000-00805f9b34fb',
    'spannung_a': '8f8e2438-535d-478d-af0f-c3692c3c1bb1',
    'spannung_b': '8f8e2438-535d-478d-af0f-c3692c3c1bb2',
}


async def brauchbar(c):
    """Steht die Verbindung wirklich, oder nur der Anschein davon?

    Ein connect() kann durchkommen, bevor die Merkmalstabelle geholt ist. Der
    Client sieht verbunden aus, der erste Zugriff faellt mit
    BleakCharacteristicNotFoundError um, und das sieht aus wie ein
    abgebrochener Funkkontakt, obwohl nur zu frueh gefragt wurde. Am
    18.08.2026 hat das einen Bisect-Schritt gekostet.

    Also nach jedem Verbindungsaufbau einmal nachsehen, ob ein Merkmal da ist,
    von dem wir wissen, dass es da sein muss.
    """
    try:
        uuid = FELDER['dark_threshold'][0]
        if c.services.get_characteristic(uuid) is not None:
            return True
    except Exception:
        pass
    try:
        await c.disconnect()
    except Exception:
        pass
    return False


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
            return c if await brauchbar(c) else None
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
        return c if await brauchbar(c) else None
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
        if await brauchbar(c):
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
                if await brauchbar(c):
                    return c
            except Exception:
                pass
    finally:
        await sc.stop()
    return None


# Welche Felder mit Vorzeichen gelesen werden, steht oben in FIELDS. Die
# Offsets duerfen negativ sein -- ein Sensor, der zu warm liest, braucht genau
# das -- und die Zeitzone westlich von Greenwich ebenso.


async def lesen(c, uuid, breite, mit_vorzeichen=False):
    raw = await c.read_gatt_char(uuid)
    return int.from_bytes(raw[:breite], 'little', signed=mit_vorzeichen)


async def schreiben(c, uuid, breite, wert):
    roh = wert.to_bytes(breite, 'little', signed=wert < 0)
    await c.write_gatt_char(uuid, roh, response=False)


BTHOME_UUID = '0000fcd2-0000-1000-8000-00805f9b34fb'

# Wie breit ein BTHome-Objekt ist, soweit dieser Sensor sie sendet. Gebraucht
# wird nur, ueber die unbekannten hinwegzukommen, um an 0x1e zu gelangen.
#   0x00 Paketzaehler   0x01 Batterie %   0x15 Batterie schwach
#   0x1e hell/dunkel    0x2e Feuchte %    0x45 Temperatur 0.1 C
#   0x64 Helligkeit     0x40 nur beim Modell ohne Display
#
# 0x15 fehlte hier und das war gefaehrlich: die Objekte kommen aufsteigend
# sortiert, ein unbekanntes bricht das Zerlegen ab, und 0x15 stuende vor
# 0x1e. Solange die Batterie voll ist, sendet er es nicht und es fiel nicht
# auf -- unter 15 Prozent waere hell/dunkel schlagartig verschwunden.
BREITEN = {0x00: 1, 0x01: 1, 0x15: 1, 0x1e: 1, 0x2e: 1, 0x40: 2, 0x45: 2,
           0x64: 1}


def paket_text(paket):
    """Ein Funkpaket in eine Zeile."""
    teile = []
    if 0x64 in paket:
        teile.append('Helligkeit %3d' % paket[0x64])
    if 0x1e in paket:
        teile.append('%s' % ('HELL  ' if paket[0x1e] else 'dunkel'))
    if 0x45 in paket:
        roh = paket[0x45]
        if roh >= 0x8000:
            roh -= 0x10000
        teile.append('%5.1f C' % (roh / 10.0))
    if 0x2e in paket:
        teile.append('%2d %%' % paket[0x2e])
    if 0x01 in paket:
        teile.append('Batterie %d %%' % paket[0x01])
    if paket.get(0x15):
        teile.append('BATTERIE SCHWACH')
    if 0x00 in paket:
        teile.append('#%d' % paket[0x00])
    return '   '.join(teile)


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


BEKANNT = dict([(u.lower(), n) for n, (u, _) in FELDER.items()]
               + [(u.lower(), n) for n, u in NUR_LESBAR.items()]
               + [(PIN_UUID, 'PIN'), (SCHLUESSEL_UUID, 'Schluessel')])


async def merkmale_zeigen(c, lesen_auch=True):
    """Zaehlt auf, was das Geraet ueberhaupt anbietet.

    Alles, was wir bisher kennen, stammt aus dem Handy-Mitschnitt und damit aus
    dem, was die Shelly-App angefasst hat. Was sie nicht anfasst, steht dort
    nicht -- und genau da koennte liegen, was wir suchen: ein Merkmal, das die
    Hell-Dunkel-Entscheidung direkt hergibt, oder eins mit notify, das sie von
    sich aus meldet. Waere eines davon da, braeuchte bisect das Trennen und
    Abhorchen ueberhaupt nicht mehr.
    """
    gefunden = []
    for dienst in c.services:
        print('\nDienst %s' % dienst.uuid)
        for m in dienst.characteristics:
            eig = ','.join(m.properties)
            name = BEKANNT.get(m.uuid.lower(), '')
            zeile = '  %s  [%s]  %s' % (m.uuid, eig, name)
            wert = ''
            if lesen_auch and 'read' in m.properties:
                try:
                    roh = await c.read_gatt_char(m)
                    wert = '%s' % roh.hex()
                    if 1 <= len(roh) <= 4:
                        wert += '  = %d' % int.from_bytes(roh, 'little')
                    else:
                        try:
                            t = roh.decode()
                            if t.isprintable():
                                wert += '  = %r' % t
                        except Exception:
                            pass
                except Exception as e:
                    wert = '<%s>' % type(e).__name__
            print('%-72s %s' % (zeile, wert))
            if 'notify' in m.properties or 'indicate' in m.properties:
                gefunden.append(m.uuid)
    if gefunden:
        print('\nMit notify/indicate: %s' % ', '.join(gefunden))
        print('Das ist die interessante Spur -- so ein Merkmal koennte die')
        print('Helligkeit melden, ohne dass die Verbindung getrennt wird.')
    else:
        print('\nKein einziges Merkmal mit notify oder indicate.')
        print('Der Sensor sagt von sich aus nichts, solange man an ihm haengt.')
    return gefunden


async def cmd_horchen(args):
    """Hoert nur zu. Kein Verbindungsaufbau, kein Schreiben, kein Knopfdruck.

    Der Sensor funkt alle sechzig Sekunden von sich aus, und in diesem Paket
    steht laut Herstellertabelle nicht nur seine Entscheidung hell/dunkel,
    sondern unter 0x64 auch die gemessene Helligkeit. Wenn das stimmt, ist das
    Einkreisen ueber die Schwellen ueberfluessig -- man liest den Wert einfach
    ab. Ein Empfaenger stoert den Sensor nicht; er weiss nicht einmal, dass
    jemand zuhoert.

    Ungewoehnliche Objekte werden roh gezeigt statt verschwiegen: das Zerlegen
    bricht bei einer unbekannten Kennung ab, und dann fehlt alles dahinter,
    ohne dass es auffiele.
    """
    print('hoere zu, %d Sekunden. Er meldet sich etwa einmal pro Minute.'
          % args.dauer)
    print('(Nur Empfang -- der Sensor merkt davon nichts.)\n', flush=True)

    gesehen_zahl = [0]

    def gesehen(dev, adv):
        if dev.address.upper() != ADDR:
            return
        daten = adv.service_data.get(BTHOME_UUID)
        if not daten:
            return
        gesehen_zahl[0] += 1
        paket = bthome_lesen(daten)
        rest = ''
        verbraucht = 1
        for kennung in paket:
            verbraucht += 1 + BREITEN.get(kennung, 0)
        if verbraucht < len(daten):
            rest = '   Rest %s' % daten[verbraucht:].hex()
        print('%s   %s%s' % (paket_text(paket), '', rest), flush=True)
        if args.roh:
            print('      roh %s' % daten.hex(), flush=True)

    sc = BleakScanner(detection_callback=gesehen)
    await sc.start()
    try:
        await asyncio.sleep(args.dauer)
    finally:
        await sc.stop()
    if not gesehen_zahl[0]:
        print('nichts gehoert. Ist er in Reichweite, und laeuft nebenher ein'
              ' anderes BLE-Programm?')
    else:
        print('\n%d Pakete.' % gesehen_zahl[0])


async def cmd_probe(args):
    """Setzt beide Schwellen auf einen Wert und sieht mehrere Pakete lang zu.

    bisect fragt nach jedem Schritt genau ein Paket ab und geht weiter. Das
    setzt voraus, dass der Sensor seine Entscheidung sofort neu faellt --
    was niemand geprueft hat. Faellt er sie nur alle paar Minuten neu, oder
    erst nach irgendeinem inneren Anlass, dann antwortet das eine Paket auf
    eine Frage von vorhin, und die ganze Einkreisung misst nichts.

    Also eine Schwelle, viele Pakete. Aendert sich das Urteil beim dritten
    oder fuenften, wissen wir, dass es an der Zeit lag und nicht am Wert.
    Aendert es sich nie, obwohl die Schwelle weit unter der gemeldeten
    Helligkeit liegt, dann gehorcht das Bit diesen Schwellen nicht.
    """
    dunkel_uuid, breite = FELDER['dark_threshold']
    hell_uuid, _ = FELDER['bright_threshold']

    print('Setze beide Schwellen auf %d und hoere dann %d Pakete lang zu.'
          % (args.wert, args.pakete))
    print('Ein Mal den Knopf am Sensor druecken.\n', flush=True)

    c = await fassen(minuten=args.warten, weg=args.weg)
    if not c:
        return print('keine Verbindung.')
    original = (await lesen(c, dunkel_uuid, breite),
                await lesen(c, hell_uuid, breite))
    print('vorher: dunkel %d, hell %d' % original, flush=True)
    zurueck = await schwellen_setzen(c, args.wert)
    print('jetzt:  dunkel %d, hell %d%s'
          % (zurueck[0], zurueck[1],
             '' if zurueck == (args.wert, args.wert)
             else '   NICHT UEBERNOMMEN'), flush=True)
    await c.disconnect()
    await asyncio.sleep(1.0)

    print('\nhoere zu. Jetzt ist der Moment fuer die Taschenlampe.\n',
          flush=True)
    urteile = set()
    gesehen = [0]
    angefangen = time.monotonic()

    def sehen(dev, adv):
        if dev.address.upper() != ADDR:
            return
        daten = adv.service_data.get(BTHOME_UUID)
        if not daten:
            return
        paket = bthome_lesen(daten)
        gesehen[0] += 1
        if 0x1e in paket:
            urteile.add(paket[0x1e])
        print('  %5.0f s   %s' % (time.monotonic() - angefangen,
                                  paket_text(paket)), flush=True)

    sc = BleakScanner(detection_callback=sehen)
    await sc.start()
    try:
        ende = time.monotonic() + args.pakete * 70
        while gesehen[0] < args.pakete and time.monotonic() < ende:
            await asyncio.sleep(1.0)
    finally:
        await sc.stop()

    print('')
    if not gesehen[0]:
        print('nichts gehoert.')
    elif len(urteile) > 1:
        print('Das Urteil hat sich waehrend der Beobachtung geaendert. Es'
              ' haengt also an')
        print('der Zeit oder am Licht, nicht nur am geschriebenen Wert -- und'
              ' bisect,')
        print('das nach jedem Schritt ein einziges Paket abfragt, misst zu'
              ' frueh.')
    else:
        print('%d Pakete, ein einziges Urteil (%s) bei Schwelle %d.'
              % (gesehen[0], 'hell' if urteile.pop() else 'dunkel',
                 args.wert))
        print('Keine Traegheit zu sehen -- das eine Paket, das bisect'
              ' abfragt, genuegt.')

    if args.behalten:
        print('\nDie Schwellen bleiben auf %d.' % args.wert)
        return
    print('\nSetze zurueck auf dunkel %d, hell %d.' % original, flush=True)
    _, c = await messen_und_fassen(args.geduld)
    if c is None:
        c = await fassen(minuten=args.warten, weg=args.weg)
    if c is None:
        print('   keine Verbindung. Bitte nachholen:')
        print('     python blu-gatt.py set bright %d' % original[1])
        print('     python blu-gatt.py set dark %d' % original[0])
        return
    try:
        await schreiben(c, hell_uuid, breite, original[1])
        await schreiben(c, dunkel_uuid, breite, original[0])
        await asyncio.sleep(0.4)
        print('   jetzt: dunkel %d, hell %d'
              % (await lesen(c, dunkel_uuid, breite),
                 await lesen(c, hell_uuid, breite)))
    finally:
        await c.disconnect()


async def cmd_fields(args):
    """Zeigt die Feldnamen. Braucht kein Bluetooth und keinen Sensor."""
    print(feld_liste())
    print('\nBeide Formen gelten ueberall:  get bright   get bright_threshold')


async def cmd_key(args):
    """Liest den 16-Byte-Schluessel. In der shell heisst das auch 'key'."""
    c = await fassen(weg=getattr(args, 'weg', 1))
    if not c:
        return print('keine Verbindung')
    try:
        await zeige_schluessel(c, 'jetzt  ')
    finally:
        await c.disconnect()


async def cmd_gatt(args):
    c = await fassen(weg=getattr(args, 'weg', 1))
    if not c:
        return print('keine Verbindung')
    try:
        await merkmale_zeigen(c)
    finally:
        await c.disconnect()


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
    args.feld = feld(args.feld)
    uuid, breite = FELDER[args.feld]
    c = await fassen(weg=getattr(args, 'weg', 1))
    if not c:
        return print('keine Verbindung')
    try:
        print('%s = %d' % (args.feld, await lesen(c, uuid, breite, args.feld in VORZEICHEN)))
    finally:
        await c.disconnect()


async def cmd_set(args):
    args.feld = feld(args.feld)
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
    print('verbunden.\n')
    print('commands')
    print('  get <field>            read one setting')
    print('  set <field> <value>    write one setting')
    print('  dump                   read them all')
    print('  gatt                   list every characteristic')
    print('  key                    read the 16-byte key')
    print('  pin <number>           send a PIN, then read the key')
    print('  fields                 this table again')
    print('  quit                   disconnect and leave')
    print('')
    print(feld_liste(), flush=True)
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
                if befehl in ('quit', 'exit'):
                    break
                if befehl == 'fields':
                    print(feld_liste())
                    continue
                if befehl == 'pin' and len(teile) == 2:
                    await zeige_schluessel(c, 'vorher ')
                    wert = int(teile[1], 0)
                    await c.write_gatt_char(PIN_UUID, wert.to_bytes(4, 'big'), response=False)
                    print('  PIN geschickt: %d' % wert)
                    await asyncio.sleep(0.6)
                    await zeige_schluessel(c, 'nachher')
                    continue
                if befehl == 'gatt':
                    await merkmale_zeigen(c)
                    continue
                if befehl == 'key':
                    await zeige_schluessel(c, 'jetzt  ')
                    continue
                if befehl == 'dump':
                    for name, (uuid, breite) in FELDER.items():
                        print('  %-18s %d' % (name, await lesen(c, uuid, breite,
                                                                name in VORZEICHEN)))
                if len(teile) >= 2:
                    teile[1] = feld(teile[1])
                if befehl == 'get' and len(teile) == 2 and teile[1] in FELDER:
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
                    print('  ?  get | set | dump | gatt | key | pin | fields'
                          ' | quit')
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
            paket = bthome_lesen(daten)
            paket['roh'] = daten.hex()
            paket['rssi'] = adv.rssi
            q.put_nowait((paket, dev))

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
                if await brauchbar(c):
                    return erstes, c
            except Exception:
                pass  # Fenster verpasst, das naechste Paket kommt bestimmt
    finally:
        await sc.stop()
    return erstes, None


async def schwellen_setzen(c, wert):
    """Setzt beide Schwellen und liest nach, ob sie wirklich stehen.

    Nachlesen ist nicht Zierde: ein stillschweigend verworfener Schreibvorgang
    wuerde die naechste Messung zur Antwort auf die vorige machen, und die
    Einkreisung liefe in die falsche Richtung, ohne dass es auffiele.

    Die Reihenfolge ist nicht beliebig. Ab Werk steht dunkel unter hell (50 und
    500), und zwischen den beiden Schreibvorgaengen steht die Ordnung kurz auf
    dem Kopf, wenn man in die falsche Richtung anfaengt: nach oben zuerst die
    dunkle Schwelle zu schreiben hiesse dunkel > hell fuer einen Wimpernschlag.
    Ob die Firmware das hinnimmt, weiss niemand -- also erst die, die den Weg
    frei macht. Nach unten ist das die dunkle, nach oben die helle.
    """
    dunkel_uuid, breite = FELDER['dark_threshold']
    hell_uuid, _ = FELDER['bright_threshold']
    if await lesen(c, dunkel_uuid, breite) > wert:
        reihe = [(dunkel_uuid, wert), (hell_uuid, wert)]
    else:
        reihe = [(hell_uuid, wert), (dunkel_uuid, wert)]
    for uuid, w in reihe:
        await schreiben(c, uuid, breite, w)
    await asyncio.sleep(0.4)
    return (await lesen(c, dunkel_uuid, breite),
            await lesen(c, hell_uuid, breite))


async def cmd_track(args):
    """Folgt der Helligkeit, statt sie einzukreisen.

    Einkreisen halbiert eine Klammer und kann darum nur einmal falsch liegen:
    was einmal ausgeschlossen ist, bleibt ausgeschlossen. Das ist richtig,
    solange das Gesuchte stillhaelt -- und falsch bei Sonnenaufgang. Am
    19.08.2026 lief eine Suche in eine Klammer von 5 bis 6, waehrend es
    draussen hell wurde; jeder Schritt antwortete auf eine andere Helligkeit
    als der davor, und das Ergebnis galt fuer keinen der beiden Zeitpunkte.

    Also nicht halbieren, sondern nachfuehren. Die Schwelle wird gesetzt, das
    Urteil abgewartet und die Schwelle um einen Schritt in die Richtung
    verschoben, aus der das Urteil kam: hell heisst zu niedrig, dunkel heisst
    zu hoch. Sie pendelt dann um den wahren Wert und wandert mit ihm.
    Nichts wird ausgeschlossen, und darum kann nichts falsch ausgeschlossen
    werden.

    Die Schrittweite verdoppelt sich, solange es in dieselbe Richtung geht,
    und faellt auf eins zurueck, sobald das Urteil umschlaegt. So ist der
    Anfang schnell, auch wenn der Startwert weit daneben liegt, und danach
    steht die Schwelle so fein wie moeglich.

    Was dabei entsteht, ist die Zeitreihe, die der Sensor selbst nicht
    hergibt: die Helligkeit in den Einheiten seiner eigenen Schwellen, Minute
    fuer Minute.
    """
    dunkel_uuid, breite = FELDER['dark_threshold']
    hell_uuid, _ = FELDER['bright_threshold']

    print('Folge der Helligkeit, %d Runden, etwa eine Minute je Runde.'
          % args.runden)
    print('Jetzt ein Mal den Knopf am Sensor druecken. Danach laeuft es'
          ' allein.\n', flush=True)

    c = await fassen(minuten=args.warten, weg=args.weg)
    if not c:
        return print('keine Verbindung.')
    original = (await lesen(c, dunkel_uuid, breite),
                await lesen(c, hell_uuid, breite))
    print('Schwellen vorher: dunkel %d, hell %d' % original, flush=True)

    wert = original[0] if args.start is None else args.start
    wert = max(args.min, min(args.max, wert))
    weite = 1
    zuletzt = None
    print('Start bei %d.\n' % wert)
    print('  Zeit    Schwelle  0x64  Urteil    naechste')
    angefangen = time.monotonic()
    runde = 0
    try:
        while runde < args.runden:
            runde += 1
            if c is None:
                c = await fassen(minuten=args.warten, weg=args.weg)
                if c is None:
                    print('  keine Verbindung mehr -- Schluss')
                    break
            try:
                zurueck = await schwellen_setzen(c, wert)
            except Exception:
                c = None
                runde -= 1
                continue
            if zurueck != (wert, wert):
                print('  Schwellen nicht uebernommen (%d/%d)' % zurueck,
                      flush=True)
                c = None
                runde -= 1
                continue
            await c.disconnect()
            c = None
            await asyncio.sleep(2.5)

            paket, c = await messen_und_fassen(args.geduld)
            if paket is None or 0x1e not in paket:
                runde -= 1
                continue
            hell = bool(paket[0x1e])

            # Umschlag heisst: wir sind darueber gelaufen. Dann wieder fein.
            if zuletzt is not None and hell != zuletzt:
                weite = 1
            elif zuletzt is not None:
                weite = min(weite * 2, args.weite)
            zuletzt = hell

            naechste = wert + weite if hell else wert - weite
            naechste = max(args.min, min(args.max, naechste))
            print('  %5.0f s  %8d  %4s  %-8s  %d'
                  % (time.monotonic() - angefangen, wert,
                     paket.get(0x64, '-'), 'hell' if hell else 'dunkel',
                     naechste), flush=True)
            wert = naechste

        print('\nZuletzt stand die Schwelle auf %d. Solange sie um einen'
              ' Wert pendelt,' % wert)
        print('ist das die Helligkeit in Schwellen-Einheiten; wandert sie'
              ' stetig, wandert')
        print('das Licht.')
    finally:
        if args.behalten:
            print('\nDie Schwellen bleiben auf %d.' % wert)
        else:
            print('\nSetze zurueck auf dunkel %d, hell %d.' % original,
                  flush=True)
            if c is None:
                _, c = await messen_und_fassen(args.geduld)
            if c is None:
                c = await fassen(minuten=args.warten, weg=args.weg)
            if c is None:
                print('   keine Verbindung. Bitte nachholen:')
                print('     python blu-gatt.py set bright %d' % original[1])
                print('     python blu-gatt.py set dark %d' % original[0])
            else:
                await schreiben(c, hell_uuid, breite, original[1])
                await schreiben(c, dunkel_uuid, breite, original[0])
                await asyncio.sleep(0.4)
                print('   jetzt: dunkel %d, hell %d'
                      % (await lesen(c, dunkel_uuid, breite),
                         await lesen(c, hell_uuid, breite)))
        if c is not None:
            await c.disconnect()


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

    dunkel_uuid, breite = FELDER['dark_threshold']
    hell_uuid, _ = FELDER['bright_threshold']
    original = (await lesen(c, dunkel_uuid, breite),
                await lesen(c, hell_uuid, breite))
    print('verbunden. Schwellen vorher: dunkel %d, hell %d\n' % original,
          flush=True)

    schritt = 0
    versuche = 0
    protokoll = []   # (Schwelle, Helligkeit aus 0x64, Entscheidung)
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
            # Der Funk kommt erst nach dem Trennen, und das Trennen ist nicht
            # fertig, wenn disconnect() zurueckkommt: am 18.08.2026 blieb ein
            # Schritt volle 120 Sekunden ohne ein einziges Paket, weil zu
            # frueh zugehoert wurde.
            await asyncio.sleep(2.5)

            print('   gesetzt (dunkel %d, hell %d). warte auf sein naechstes'
                  ' Funkpaket ...' % zurueck, flush=True)
            angefangen = time.monotonic()
            paket, c = await messen_und_fassen(args.geduld)
            gedauert = time.monotonic() - angefangen
            if paket is None or 0x1e not in paket:
                print('   kein verwertbares Paket nach %.0f s -- Schritt'
                      ' wiederholt\n' % gedauert, flush=True)
                schritt -= 1
                continue

            hell = bool(paket[0x1e])
            print('   %s' % paket_text(paket), flush=True)
            if args.debug:
                print('      [debug] nach %.0f s, %d dBm, Verbindung %s'
                      % (gedauert, paket.get('rssi', 0),
                         'gehalten' if c else 'verloren'))
                print('      [debug] roh %s' % paket.get('roh', ''))
                print('      [debug] Schwelle %d, 0x64 %s, 0x1e %d'
                      % (pruef, paket.get(0x64, '-'), paket[0x1e]), flush=True)
            protokoll.append((pruef, paket.get(0x64), hell))

            # Hell heisst: die Helligkeit liegt ueber dem Pruefwert. Dann ist
            # der Pruefwert die neue Untergrenze, nicht die neue Obergrenze.
            if hell:
                lo = pruef
            else:
                hi = pruef
            print('   Klammer jetzt %d .. %d\n' % (lo, hi), flush=True)

        if protokoll:
            print('\nWas gemessen wurde:\n')
            print('   Schwelle   0x64   Urteil')
            for schwelle, stufe, war_hell in protokoll:
                print('   %8d   %4s   %s'
                      % (schwelle, '-' if stufe is None else stufe,
                         'hell' if war_hell else 'dunkel'))
            stufen = [x for _, x, _ in protokoll if x is not None]
            if stufen and len(set(w for _, _, w in protokoll)) == 1:
                print('\n   Das Urteil hat sich kein einziges Mal geaendert,'
                      ' obwohl die Schwelle')
                print('   von %d bis %d gewandert ist. Dann misst 0x64 nicht'
                      ' in derselben' % (protokoll[0][0], protokoll[-1][0]))
                print('   Einheit wie die Schwellen -- 0x64 lag bei %d bis %d.'
                      % (min(stufen), max(stufen)))
            elif stufen:
                print('\n   0x64 lag waehrenddessen bei %d bis %d, der'
                      ' Umschlagpunkt in Schwellen-'
                      % (min(stufen), max(stufen)))
                print('   Einheiten zwischen %d und %d. Beides nebeneinander'
                      ' ist das Verhaeltnis' % (lo, hi))
                print('   der zwei Skalen.')
                if max(stufen) - min(stufen) > 1:
                    print('\n   ABER: 0x64 hat sich waehrend der Suche um %d'
                          ' bewegt. Eine Halbierung' % (max(stufen)
                                                        - min(stufen)))
                    print('   setzt voraus, dass das Gesuchte stillhaelt. Tut'
                          ' es das nicht -- Abend,')
                    print('   Wolke, jemand macht Licht an --, dann antwortet'
                          ' jeder Schritt auf eine')
                    print('   andere Helligkeit und die Klammer bedeutet'
                          ' nichts. Im Dunkeln messen,')
                    print('   oder bei kuenstlichem Licht, das sich nicht'
                          ' bewegt.')

        print('\nErgebnis: die Helligkeit liegt zwischen %d und %d.' % (lo, hi))
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
                print('     python blu-gatt.py set bright %d' % original[1])
                print('     python blu-gatt.py set dark %d' % original[0])
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
    mit_weg(sub.add_parser('gatt', help='alle Merkmale des Geraets auflisten'))
    mit_weg(sub.add_parser('key', help='den 16-Byte-Schluessel lesen'))
    sub.add_parser('fields', help='die Feldnamen und ihre Kurzformen zeigen')

    pr = mit_weg(sub.add_parser('probe',
                                help='eine Schwelle setzen und mehrere'
                                     ' Pakete lang zusehen'))
    pr.add_argument('wert', type=int)
    pr.add_argument('--pakete', type=int, default=5)
    pr.add_argument('--geduld', type=int, default=120)
    pr.add_argument('--warten', type=float, default=5.0)
    pr.add_argument('--behalten', action='store_true')

    h = sub.add_parser('listen',
                       help='nur zuhoeren, ohne den Sensor anzufassen')
    h.add_argument('--dauer', type=int, default=180,
                   help='Sekunden')
    h.add_argument('--roh', action='store_true',
                   help='die Dienstdaten zusaetzlich als Hex')

    g = mit_weg(sub.add_parser('get', help='eine Einstellung lesen'))
    g.add_argument('feld', choices=ERLAUBT, metavar='field')

    st = mit_weg(sub.add_parser('set', help='eine Einstellung schreiben'))
    st.add_argument('feld', choices=ERLAUBT, metavar='field')
    st.add_argument('wert', type=int)

    mit_weg(sub.add_parser('shell',
                           help='einmal verbinden, dann viele Befehle'))

    pn = mit_weg(sub.add_parser('pin',
                                help='PIN schicken und den Schluessel lesen'))
    pn.add_argument('pin', type=int)

    tr = mit_weg(sub.add_parser('track',
                                help='der Helligkeit folgen, auch wenn sie'
                                     ' sich bewegt'))
    tr.add_argument('--start', type=int, default=None,
                    help='Startschwelle, sonst die vorhandene')
    tr.add_argument('--runden', type=int, default=60)
    tr.add_argument('--weite', type=int, default=64,
                    help='groesster Schritt, mit dem nachgefuehrt wird')
    tr.add_argument('--min', type=int, default=0)
    tr.add_argument('--max', type=int, default=65535)
    tr.add_argument('--geduld', type=int, default=120)
    tr.add_argument('--warten', type=float, default=5.0)
    tr.add_argument('--behalten', action='store_true')

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
    b.add_argument('--debug', action='store_true',
                   help='jeden Schreibvorgang, jedes Paket und jede Sekunde'
                        ' mitschreiben')

    args = p.parse_args()
    befehle = {'dump': cmd_dump, 'gatt': cmd_gatt, 'key': cmd_key,
               'fields': cmd_fields, 'probe': cmd_probe, 'get': cmd_get,
               'listen': cmd_horchen, 'set': cmd_set, 'pin': cmd_pin,
               'shell': cmd_shell, 'track': cmd_track,
               'bisect': cmd_bisect}
    asyncio.run(befehle[args.cmd](args))


if __name__ == '__main__':
    main()
