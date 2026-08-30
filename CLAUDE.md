# shelly.local — Entwickler-Einstieg für Claude-Sessions

Local-only Android-App zur Steuerung von Shelly-Geräten (Gen1–Gen4), **plus** — und das ist der
eigentliche Schwerpunkt dieses Forks — zwei mJS-Geräteskripte samt Werkzeugkasten: ein
**Energie-Journal**, das auf dem Stecker selbst archiviert, und eine **BLU-H&T-Brücke** nach
openSenseMap. Nutzersicht: `README.md`. Vandas Architektur-Doku der App-Basis: `DEVELOPER.md`.

Diese Datei ist eine **Landkarte**: wo etwas steht, warum es so gebaut ist, und was einen sonst in
die Falle laufen lässt. **Gehen Datei und Code auseinander, gilt der Code.** Die beiden Skripte
haben ihre eigene, sehr gründliche Wahrheit: `shelly/power-journal/README.md` und
`shelly/blu-osm/README.md` — dort steht alles Gemessene (Speicher-Pyramide, RAM-Budgets,
JSON.parse-Falle, BLU-Protokoll). Diese Landkarte wiederholt das nicht.

## Herkunft und Namen — die Fallen zuerst

- **Fork von `vandah/pearlnode`** (Vanda Hendrychová, MIT, 14 Commits Basis). Ihr Copyright bleibt
  in der LICENSE, nichts geht upstream zurück; ihr Remote heißt `upstream` und dient nur dem
  Hereinholen. Der Fork ist ein echter GitHub-Fork — **öffentlich** unter
  `github.com/BeardedSkunk/shelly.local`.
- **Umbenennung komplett (30.08.2026):** Name, Repo, Ordner **und** Package heißen
  `shelly.local` — `applicationId = "shelly.local"`, Quellbaum `app/src/main/java/shelly/local/`,
  die Application-Klasse heißt `ShellyLocalApp`. Damit ist die neue App für Android eine
  **andere App** als das alte `com.pearlnode`: beide laufen nebeneinander, bis die Einstellungen
  von Hand übernommen sind, dann fliegt die alte runter. Es gibt keinen Datenimport — bewusst so
  entschieden (Neu-Einrichten war ausdrücklich in Ordnung).
- **Debug installiert sich NEBEN Release**: `applicationIdSuffix = ".debug"`, eigenes Icon,
  eigener Anzeigename. Absicht — ein Debug-Install darf die produktive App samt Geräteliste nicht
  verdrängen.
- `metadata/com.pearlnode.yml` ist **Vandas F-Droid-Datei** (ihr Name, ihr Repo) — Upstream-Erbe,
  nicht anfassen, auch nicht in Phase 2.
- `release.sh` signiert mit `$HOME/pearlnode.p12`, Key-Alias `pearlnode` — Pfad und Alias zeigen
  auf die existierende Signatur und **bleiben**, auch nach Phase 2 (sonst neue App-Signatur).

## Bauen, Testen, Ausliefern

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio1/jbr" ./gradlew :app:testDebugUnitTest
JAVA_HOME="/c/Program Files/Android/Android Studio1/jbr" ./gradlew :app:assembleDebug
node shelly/power-journal/test/acceptance.js         # 137 Checks gegen das echte Skript
node shelly/blu-osm/test/quarters.js
```

- `gradle.properties` pinnt hier **kein** JDK — nur `JAVA_HOME` übersteuern, kein `-D` nötig.
- Toolchain: AGP 9.0.1 + Gradle 9.1.0 mit **eingebautem Kotlin** (kein
  `org.jetbrains.kotlin.android`-Plugin — wie shellyPower, anders als HomeShare/videoapp), dazu
  Compose-Plugin 2.2.10, kotlinx-serialization, KSP für Room.
  `android.disallowKotlinSourceSets=false` ist der dokumentierte KSP-Workaround, stehen lassen.
- **Skript-Tests immer ZWEIMAL fahren**: einmal normal, einmal `PJ_STRIPPED=1` bzw.
  `BLU_STRIPPED=1` — der Minifier benennt auch Funktionslokale um, der zweite Lauf testet, was
  wirklich aufs Gerät geht (globales Memory `shelly-skript-fallen`).
- 14 JVM-Testdateien (`app/src/test/`) für alles, „was entscheidet, was eine Zahl bedeutet":
  Journal-Dekodierung, Seiten-Slices, Preisrechnung, gefühlte Temperatur, Balkenbereiche.
- `app/src/main/assets/power-journal.min.js` wird von `node shelly/power-journal/tools/asset.js`
  **erzeugt und eingecheckt** (Gradle hat kein Node); ein Test vergleicht Asset gegen Quelle, ein
  vergessenes Regenerieren fällt also durch statt auszuliefern. `assets/blu-osm.js` ist eine
  Kopie von `shelly/blu-osm/blu-osm.js` — von Hand im Gleichlauf halten, die App merkt eine alte
  Fassung inzwischen selbst (Commit `8369610`).
- Release: `./release.sh` (Versionsabfrage, Build, zipalign-erhaltendes Signieren — Vandas
  Reproducible-Build-Kette, v1-Signing bewusst aus). Ausgabe `shelly.local-v<version>.apk`.

## Die drei Schichten

**1. Vandas Basis** — Gerätesteuerung: Discovery (mDNS/IP-Scan/BLE), Gen1-REST vs. Gen2-RPC
hinter einer gemeinsamen Schnittstelle, DeviceType-Katalog (60+ Modelle) mit Capabilities,
verschlüsselte Zugangsdaten (Keystore), Schedules, Alarm-Sync (Handy-Wecker → Geräte-Schedules),
Firmware-Updates, Glance-Widgets, 9 Sprachen. **Architektur komplett in `DEVELOPER.md`** —
zuerst dort nachsehen, die Datei stimmt für diese Schicht.

**2. Deine App-Schicht** (Aug 2026, ~156 Commits) — was `DEVELOPER.md` NICHT kennt:

| Baustein | Dateien | Kern |
|---|---|---|
| KVS-Karte | `ui/screens/KvsSection.kt`, `KvsFormatting.kt` | KVS des Geräts auf dem Steuer-Screen; packt JSON-in-String aus (ältere Schreiber legten Objekte als Text ab); Zeitstempel folgen dem **System**-Datumsformat samt totem `date_format`-Setting (globales Memory `android-datumsformat-trick`) |
| Energie-Seite | `PowerScreen.kt`, `PowerChart.kt`, `PowerViewModel`, `PowerJournalRepository`, `PowerSyncWorker`, `PowerBlockDao` | Tipp auf die Watt eines messenden Gen2 öffnet Aufzeichnung/Historie/Kosten. Die App **installiert das Journal-Skript selbst** und spiegelt alle vier Auflösungs-Stufen stündlich im Hintergrund (unmetered) in Room — **lokal wird nie gelöscht**: Viertelstunden überleben hier, lange nachdem der Stecker sie nur noch als Tag hat. Lesen legt die Stufen **feinste zuerst** übereinander |
| Strompreis | `SettingsScreen.kt`, `AppSettings.kt` | Tarif in der Einheit, in der man ihn abliest; Einspeisung wird getrennt bepreist |
| Sensor-Seite | `SensorScreen.kt`, `SensorViewModel`, `SensorRepository`, `SensorSyncWorker`, `SensorBlockDao`, `FeltTemperature.kt`, `TemperatureColors.kt` | „Sensoren ohne eigene Adresse": BLU H&T, gehört über den Stecker. Historie aus **openSenseMap** plus dem 5-Minuten-Archiv des blu-osm-Skripts; gefühlte Temperatur, Farbskalen für Temperatur und Feuchte, gleiche Chart-Mechanik wie die Energie-Seite (Balken ziehen zum Ablesen) |
| BLU-Verwaltung | `BluScreen.kt`, `BluViewModel`, `BluFormatting.kt` | ein BLU-Sensor, aktuell gehalten „by asking the Shelly it is heard through" — alles über den Stecker, kein eigenes GATT in der App |
| Charts | `PowerChart.kt` | Faltung auf einem Arbeitsthread (war quadratisch auf dem Zeichenthread); Balkenbeschriftung entscheidet die Breite |
| Poll-Disziplin | `ui/viewmodels/LivePoll.kt` | kein Polling für Geräte, die niemand ansieht — und ein Weg zurück |
| Zeit | überall | **jede Zeit wird von der Uhr gelesen, die sie gestempelt hat** — Handy-Zeit vor Gerätezeit (`1799af0`, `42f45a8`) |

Room ist inzwischen **Version 6** (PowerBlock- und SensorBlock-Tabellen auf Vandas Basis).

**3. Die Geräteskripte** (`shelly/`) — hier steckt die meiste Messarbeit:

- `power-journal/` — Blöcke etwa konstanter Leistung, Auflösungs-Pyramide in `Script.storage`
  (12×1022 B), „Dachboden" als nie laufendes Zweitskript (13,7 Jahre Tageswerte), gapless by
  construction, Copy-on-Write, signierte Energie (`net = aenergy − 2·ret_aenergy`), Lesen über
  `GET /script/<id>/journal` **nach Zeit, nie nach Slot**. Alles Warum steht im README dort.
- `blu-osm/` — BLU H&T → openSenseMap, **als Template**: `{{OSM_TOKEN}}` & Co. füllt die App
  beim Deploy aus dem, was schon auf dem Stecker steht — **damit nie ein Token ins Repo kann**.
  Sensor-Tausch ohne Lücke, virtuelle `Number`-Komponenten für Offsets (Korrektur ohne Code),
  Nachtrag nach Ausfällen. `tools/blu-gatt.py` spricht den Sensor direkt (GATT, aus einem
  HCI-Mitschnitt reverse-engineert, später von der Herstellertabelle bestätigt).
- Beide Skripte tragen **drei Versionszähler** (api/code/VERSION — globales Memory
  `shelly-skripte-drei-versionszaehler`): `code` bei jedem Ausrollen hochzählen, `VERSION`
  bewegen wirft das Archiv weg.
- Upload immer über `tools/upload.js` (minifiziert, liest zurück, vergleicht) — nie Quelltext
  roh hochladen, 20480-Byte-Grenze zählt Kommentare mit.

## Geräte und Einsatz (Stand 30.08.2026, am Gerät geprüft)

| Wo | Was |
|---|---|
| Plug **.23** (Balkonkraftwerk) | `power-journal` läuft dort — der Stecker gehört dem Journal allein |
| Plug **.24** (Pumpe) | `blu-osm` + gekoppelte BLU H&T; `power-journal` dort seit 28.08. **stillgelegt** — zwei Skripte teilen sich ~25 KB RAM und sterben aneinander (Kapitel „Two scripts do not fit on one plug" im blu-osm-README) |
| **Pixel 8 Pro** | Release **und** Debug installiert (Alltag + Test nebeneinander) |
| **F101** | nur Debug |
| **Armor 8** | nichts |
| BLU H&T | zwei Modelle: `SBHT-003C` (Display — die Uhr ist die Kopplungsanzeige) und `SBHT-203C` |

**Querverbindung wetterschau:** blu-osm füttert openSenseMap, und die Wetter-App liest genau
diese Box — ihr zweiter Rückfallweg ist das `quarters`-Archiv dieses Skripts (globales Memory
`wetterschau-zwei-rueckfallwege`). Änderungen am Skript-Endpunkt betreffen also **zwei** Apps.

## Konventionen

- **Code-Kommentare Englisch** (Upstream-Stil, auch in deiner Schicht so gehalten). UI-Strings
  über `values-*` in 9 Sprachen — neue Strings mindestens en+de nachziehen.
- Commit-Messages: anfangs Englisch im Upstream-Ton, seit der Sensor-Arbeit Deutsch mit
  erzählendem Titel („Die Ausnahme war das Einzige, was nicht im Protokoll stand"). Beides ist
  Bestand; aktuell wird Deutsch geschrieben. Committen und pushen laufend, `main` direkt.
- `versionCode`/`versionName` (aktuell 7 / 1.6) bei ausgelieferten App-Ständen hochzählen;
  die Skripte zählen ihren eigenen `code`-Zähler.
- Keine neuen Laufzeit-Abhängigkeiten ohne Anlass — die Liste in `app/build.gradle.kts` ist
  bewusst kurz und direkt (kein Version-Catalog).

## Fallstricke der App-Schicht

(Die Skript-Fallstricke — JSON.parse tödlich, `str.at()`, Minifier, 1022-Byte-Slots, RAM-Peaks —
stehen in den Skript-READMEs und den globalen Shelly-Memories.)

- **KVS-Werte können JSON-im-String sein** (ältere Schreiber): `KvsFormatting` und
  `ShellyClient.getKvs`-Pendants lesen beides. Beim Schreiben Objekte **als Objekt** übergeben.
- **`aenergy.total` springt gequantelt** (~206–209 mWh je nach Stecker, kalibriert pro Gerät) —
  `apower` unter ~1,5 W ist Blindbereich. Wer an Schwellen dreht: erst die Messkapitel lesen.
- Der Journal-`api`-Zähler ist **absichtlich nicht** die Archiv-Version — ein Upgrade darf einem
  Leser nicht erzählen, seine gespeicherten Blöcke bedeuteten jetzt etwas anderes.
- Die **Lichtschwellen-Frage ist offen**: 0x64 ist eine von drei Stufen, nicht die Helligkeit;
  ob geschriebene Schwellen sofort gelten oder träge, ist unentschieden (`probe`/`bisect` im
  blu-osm-README). Nichts darauf bauen.
- Beim Anfassen der Discovery dran denken: ein Stecker **ohne** gekoppelten BLU hat seinen
  BLE-Empfänger aus — `BTHome.StartDeviceDiscovery` zuerst, sonst wartet `AddDevice` ewig.

## Aktueller Stand (30.08.2026)

`main`, v1.6 / versionCode 7, alles gepusht. Historie am 30.08. auf die BeardedSkunk-Identität
umgeschrieben (Vandas 14 Commits unverändert), am selben Tag Umbenennung Phase 1.

Zuletzt gebaut: die RAM-Härtung beider Skripte nach dem Skript-Tod vom 28.08. (Lesepfade
gedeckelt, `reply_chars`-Budget, Eigenschafts-Wrapper), davor die Sensor-Strecke komplett
(blu-osm, Nachtrag, Offsets, Sensor-Screen), davor das Energie-Journal samt Pyramide, Dachboden
und Preisrechnung, davor KVS-Karte und Update-Fixes auf Vandas Basis.

**Offen:** die alten `com.pearlnode`-Apps auf Pixel (Release + Debug) und F101 (Debug) bleiben
installiert, bis die neue App dort eingerichtet ist — dann deinstallieren; das neue Release muss
Sascha selbst signieren (`release.sh`, Keystore-Passwort) · Lichtschwellen-Verhalten des BLU
klären · die vier Tage Solar-Sync-Ausfall von Ende August sind nachgetragen, die Ursachenkette
steht in `c7e43e8`/`1fe4a8a` — bei erneutem Stillstand zuerst dort nachlesen.
