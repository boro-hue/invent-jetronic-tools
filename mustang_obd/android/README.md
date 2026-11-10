# Mustang OBD / W211 OBD — Android apps

The phone version of [`mustang_obd.py`](../README.md): a read-only, catch-all OBD-II logger
over a Bluetooth ELM327 adapter. Same logic, same CSV columns, same safety rules — ported to
Java and tested against the Python tool. One codebase, one app per car (they install side
by side):

| App | For | Download (Android 6.0+) |
|---|---|---|
| **Mustang OBD** | 2016 Ford Mustang 3.7 V6 — CAN, plus Ford transmission-temp/odometer candidates | [`dist/MustangOBD.apk`](dist/MustangOBD.apk) |
| **W211 OBD** | 2005 Mercedes-Benz E220 CDI (W211) — protocol auto-detected (K-line on pre-2006 cars), generic EOBD | [`dist/W211OBD.apk`](dist/W211OBD.apk) |

Both work on any OBD-II / EOBD car; the differences are the default protocol, the
car-specific extras, the app name/colour and the download folder. `dist/*.sha256` has the
checksums.

## Install

1. Copy the APK to the phone (or open this page on the phone and download it).
2. Tap it. Android asks to allow installs from that source (browser / file manager) — allow it.
3. Play Protect may say it doesn't recognise the app (it's not from the Play Store) —
   choose *Install anyway*. It has no internet features besides WiFi OBD adapters, and
   asks only for Bluetooth / Nearby devices (and Location on Android 11 and older, only to
   find BLE adapters) and notifications (for the "logging" notification).

## Hands-free (recommended for driving)

Tap **Hands-free drive** once — or tick **Start hands-free when the app opens**, then just
opening the app is enough. The app then, without any further taps:

1. waits for the ignition (open the app before or after starting the engine),
2. takes a **snapshot** (trouble codes, freeze frame, VIN …),
3. **logs the whole drive**, also with the screen off,
4. **ends by itself** once the car has been switched off for 2 minutes (a stall or a short
   stop with the engine running doesn't end it),
5. shows a **"drive saved"** notification — tap it and the share sheet opens with all the
   files (snapshot + log), ready to send to Claude or anywhere else.

**Stop** ends it early at any time. Never handle the phone while driving.

## Use

1. **Pair the adapter** in the phone's Bluetooth settings (usually named `OBDII`, `OBD2`,
   `V-LINK`…, PIN `1234` or `0000`). BLE-only adapters don't need pairing.
2. Plug it into the OBD port (under the dashboard, driver's side), ignition ON.
3. Open **Mustang OBD → Adapter...** and pick it (paired adapters that look like OBD are
   listed first; *Scan for a BLE adapter...* finds BLE ones; *Demo* simulates a car).
4. **Start log**. The first ~10 s discover what the car supports, then rows stream in; the
   screen shows the main gauges plus every decoded value. Logging continues with the screen
   off (notification with a *Stop* button). Press **Stop** when done.
5. **Snapshot** reads trouble codes (stored / pending / permanent), freeze frame,
   per-cylinder misfire counters (Mode 06), VIN / calibration IDs and every PID once.
6. **Share files** sends the last session's files (Drive, e-mail, …).

Files are saved to **Download/MustangOBD** or **Download/W211OBD** (Android 10+; on 6–9 in the app's own folder —
use *Share files*): `mustang_*.csv` (every supported PID decoded + raw hex),
`mustang_*.meta.json` (what the car supports, constants, VIN), `mustang_snapshot_*.json/.txt`.
The CSV opens in Excel / Google Sheets and has exactly the same columns as the laptop tool.

If something doesn't work, tick **Adapter trace** and repeat: the raw adapter conversation
is saved as `elm_trace_*.txt` next to the log — that's what's needed to diagnose a
misbehaving adapter.

Same caveats as the laptop tool: the transmission-temperature and odometer values come from
**unverified** Ford-specific identifiers (raw bytes are always logged beside them); a manual
car won't report transmission temperature.

### W211 E220 CDI notes

- The OBD socket is under the dashboard in the driver's footwell (left side), usually behind
  a small cover.
- Pre-2006 W211s answer generic OBD (EOBD) over **K-line** (ISO 14230-4 KWP2000 or ISO
  9141-2); the app finds the protocol by itself (first connect can take ~10 s). Your adapter
  must support K-line — most ELM327 Bluetooth clones do, but some tiny "CAN-only" ones don't.
  If the app says *"Adapter OK, but the car did not answer"* with the ignition on, that is
  the first suspect.
- K-line is slow: expect roughly 1–3 rows per second (each value is a separate request).
- What you get is the **generic diesel EOBD set** the engine computer offers: rpm, speed,
  load, coolant and intake temperature, airflow (MAF), boost (MAP), **common-rail pressure**,
  commanded EGR, distances/warm-ups since codes were cleared, trouble codes and freeze frame.
  Mercedes-specific values (injector corrections, rail/boost *targets*, glow plugs, gearbox)
  are only available through Mercedes diagnostic sessions, which this read-only app
  deliberately never opens.

Read-only: the app can only send OBD read services (01 02 03 06 07 09 0A 22) — it cannot
clear codes or change anything in the car. Set it up before driving.

## Build it yourself

No Android Studio needed:

```
sudo apt install openjdk-17-jdk-headless aapt dalvik-exchange zipalign apksigner
./build_apk.sh            # -> dist/MustangOBD.apk and dist/W211OBD.apk   (or: ./build_apk.sh w211)
```

`build_apk.sh` fetches the Android API 34 framework jar from Maven Central once (Robolectric's
`android-all`, checksum-verified), compiles with `javac --release 8` (no lambdas, so the
classic `dx` dexer works and the app runs on Android 6+), links with `aapt2`, aligns, and
signs with `signing/mustangobd-sideload.jks`. **That key is public on purpose** (password
`mustangobd`): any build from this repo can then update an installed copy in place. It
proves nothing about who built an APK — build it yourself if that matters to you.

## Tests

- `test/run_tests.sh` — the OBD core on a plain JVM, CAN and K-line: decoder parity with the Python tool
  (≈ 27 000 generated vectors), parsing, the read-only guard (including an audit of every
  line sent in full sessions), Bluetooth-drop reconnect, Stop, demo log + snapshot, the
  Python simulator over TCP in two adapter variants, the CSV header compared with the
  Python tool's, and optionally the third-party
  [ELM327-emulator](https://github.com/Ircama/ELM327-emulator) (`./run_tests.sh 35000`).
- `test/run_ui_tests.sh [SDK]` — Robolectric: the real `MainActivity` and `LoggerService`
  of **both apps** on the Android framework (JVM, Android 6/9/12/12L): launch, adapter dialog
  with paired devices, Demo log (CAN Mustang / K-line W211) with the foreground notification,
  Stop, saved files incl. MediaStore, Share, Snapshot, denied-permission path.

Not tested on a physical phone or a real adapter yet.
