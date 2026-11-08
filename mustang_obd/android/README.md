# Mustang OBD — Android app

The phone version of [`mustang_obd.py`](../README.md): a read-only, catch-all OBD-II logger
for a 2016 Ford Mustang 3.7 V6 (works on any CAN OBD-II car) over a Bluetooth ELM327 adapter.
Same logic, same CSV columns, same safety rules — ported to Java and tested against the
Python tool.

**Download:** [`dist/MustangOBD-1.0.apk`](dist/MustangOBD-1.0.apk) (Android 6.0 or newer;
`dist/*.sha256` has the checksum).

## Install

1. Copy the APK to the phone (or open this page on the phone and download it).
2. Tap it. Android asks to allow installs from that source (browser / file manager) — allow it.
3. Play Protect may say it doesn't recognise the app (it's not from the Play Store) —
   choose *Install anyway*. It has no internet features besides WiFi OBD adapters, and
   asks only for Bluetooth / Nearby devices (and Location on Android 11 and older, only to
   find BLE adapters) and notifications (for the "logging" notification).

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

Files are saved to **Download/MustangOBD** (Android 10+; on 6–9 in the app's own folder —
use *Share files*): `mustang_*.csv` (every supported PID decoded + raw hex),
`mustang_*.meta.json` (what the car supports, constants, VIN), `mustang_snapshot_*.json/.txt`.
The CSV opens in Excel / Google Sheets and has exactly the same columns as the laptop tool.

If something doesn't work, tick **Adapter trace** and repeat: the raw adapter conversation
is saved as `elm_trace_*.txt` next to the log — that's what's needed to diagnose a
misbehaving adapter.

Same caveats as the laptop tool: the transmission-temperature and odometer values come from
**unverified** Ford-specific identifiers (raw bytes are always logged beside them); a manual
car won't report transmission temperature.

Read-only: the app can only send OBD read services (01 02 03 06 07 09 0A 22) — it cannot
clear codes or change anything in the car. Set it up before driving.

## Build it yourself

No Android Studio needed:

```
sudo apt install openjdk-17-jdk-headless aapt dalvik-exchange zipalign apksigner
./build_apk.sh            # -> dist/MustangOBD-<version>.apk
```

`build_apk.sh` fetches the Android API 34 framework jar from Maven Central once (Robolectric's
`android-all`, checksum-verified), compiles with `javac --release 8` (no lambdas, so the
classic `dx` dexer works and the app runs on Android 6+), links with `aapt2`, aligns, and
signs with `signing/mustangobd-sideload.jks`. **That key is public on purpose** (password
`mustangobd`): any build from this repo can then update an installed copy in place. It
proves nothing about who built an APK — build it yourself if that matters to you.

## Tests

- `test/run_tests.sh` — the OBD core on a plain JVM: decoder parity with the Python tool
  (≈ 27 000 generated vectors), parsing, the read-only guard (including an audit of every
  line sent in full sessions), Bluetooth-drop reconnect, Stop, demo log + snapshot, the
  Python simulator over TCP in two adapter variants, the CSV header compared with the
  Python tool's, and optionally the third-party
  [ELM327-emulator](https://github.com/Ircama/ELM327-emulator) (`./run_tests.sh 35000`).
- `test/run_ui_tests.sh [SDK]` — Robolectric: the real `MainActivity` and `LoggerService`
  on the Android framework (JVM): launch, adapter dialog with paired devices, Demo log with
  the foreground notification, Stop, saved files, Share, Snapshot, denied-permission path.

Not tested on a physical phone or a real adapter yet.
