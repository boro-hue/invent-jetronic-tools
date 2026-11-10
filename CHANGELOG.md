# Changelog

## 2026-11-10 — Hands-free mode (apps 1.2)

- **Hands-free drive** in both Android apps: one tap (or automatically when the app opens,
  if enabled) waits for the ignition, takes a snapshot, logs the drive and ends by itself
  once the car has been off for 2 minutes; a "drive saved" notification opens the share
  sheet with all the session's files.
- Same end-of-drive rule in the core and the Python tool (`--stop-when-off SEC`): the log
  ends when the car stays silent or the adapter does not come back within that time.
- Tests: Python 34, JVM ~47 400 checks, Robolectric 64 (8 tests × 4 Android versions × 2
  apps) incl. the full hands-free flow and auto-start.

## 2026-11-10 — W211 OBD app + K-line support

- `mustang_obd` (Python and Android) now speaks **K-line and J1850** besides CAN: ISO 9141-2
  and ISO 14230-4 KWP2000 replies (3-byte header + checksum, keyed by ECU address), VIN and
  other Mode 09 items reassembled from numbered 4-byte messages, DTCs 3 per message,
  protocol auto-detected (CAN-first default falls back to auto). CAN-only features
  (multi-PID requests, Mode 06 decoding, Mode 22) are skipped on K-line.
- New **W211 OBD** Android app for a 2005 Mercedes E220 CDI (W211): same codebase, built as a
  separate APK (`dist/W211OBD.apk`, own app ID/name/colour/Download folder, installs next to
  Mustang OBD). `dist/MustangOBD.apk` is version 1.1 (update over 1.0).
  Python: `profile_w211_e220cdi_2005.json`, `--demo w211`.
- Simulators (Python and Java) gained a W211 diesel on K-line (KWP2000 or ISO 9141-2).
  Tests: Python 32, JVM ~47 000 checks incl. K-line parsing, CSV-header parity with the
  Python tool on the W211, read-only audit of K-line sessions; Robolectric 48 (6 tests × 4
  Android versions × 2 apps).

## 2026-11-08 — `mustang_obd` Android app

- New **Android app** (`mustang_obd/android/`, APK in `dist/`, Android 6+): the OBD logger
  ported to Java — same discovery, tiers, multi-PID/count shortcuts, CSV columns (checked
  identical to the Python tool), snapshot (DTCs, freeze frame, Mode 06 misfire counters,
  IDs) and the same read-only whitelist. Classic Bluetooth (SPP), BLE, WiFi and a built-in
  Demo car; a foreground service keeps logging with the screen off; files go to
  Download/MustangOBD; Share button; optional adapter trace.
- Built without Android Studio (`build_apk.sh`: javac + dx + aapt2 + apksigner) and tested
  on the JVM (decoder parity with Python over ~27 000 vectors, both simulators, the
  third-party ELM327-emulator) and on Robolectric for Android 6/9/12/12L (launch, adapter
  dialog, demo log with notification, Stop, saved files incl. MediaStore, Share, snapshot,
  permission denial). Not yet run on a physical phone.
- Fix (Python tool): PID 0xA6 (odometer) decoded a value from a too-short 3-byte answer;
  it now requires the full 4 bytes, like every other decoder.

## 2026-11-08 — New, separate tool: `mustang_obd/` (OBD-II logger)

Not related to the Jetronic ECU: a read-only catch-all OBD-II logger for ELM327-type
adapters over classic Bluetooth (COM port or RFCOMM), BLE, WiFi or USB, written for a
2016 Ford Mustang 3.7 V6 and usable on any CAN OBD-II car. Logs every supported Mode 01
PID (decoded + raw) with tiered polling, multi-PID requests and auto-reconnect; a
snapshot mode dumps trouble codes, freeze frame, Mode 06 results (per-cylinder misfire
counts) and vehicle IDs; a Mode 22 scanner discovers enhanced Ford identifiers. Every
request passes a read-only whitelist (no code clearing, no writes). Ships with an
ELM327 + Mustang simulator (`--demo`) and 28 tests; not yet run on a real car.

## 2026-08-06 — Session-report exports + AI backup, `CLAUDE.md`, migration recommendation

- `jetronic_live.py` now **exports a session report on every build**
  (`exports/jlive_report_*.json` + `.md`): per-cell stats with spread, the sample
  rejection funnel, warning events, health flags, and the proposal. The report is a
  self-contained hand-off artifact for deeper (human or AI) analysis when the internal
  math is not enough.
- New **AI backup option** (advisory-only, never applied automatically): `--ai on-fail`
  consults an AI when health flags fire (low acceptance, scattered samples, high-spread
  cells, neighbour sign conflicts), `--ai always` after every build. Default backend is
  the Claude Code CLI (`claude -p`); any model via `--ai-cmd` (report on stdin). Advice
  is saved to `exports/ai_advice_*.md`.
- New **`CLAUDE.md`**: full project context for AI assistants — verified decode anchors,
  `.imf` format rules, discovered firmware behaviours (idle anchor, run-level demotion
  trapdoor, adaptation resets, correction-speed asymmetry), and hard safety rules.
- README: the 4.1.6 → 4.1.7 section now carries an explicit recommendation — **do not
  migrate a 4.1.6 map; start from the stock 4.1.7 preset** and re-enter settings. The
  documented car's migration was effectively brute-forced back to health and still
  produced weeks of subtle after-effects.
- New **worked example** in `samples/`: a real 25-minute drive log
  (`example_drive_25min_rb15.csv`), the session report exported from it, and
  `LIVE_TUNING_EXAMPLE.md` — the full walkthrough including the blind cross-check
  against a hand-built revision and the health flags catching a real hardware misfire
  from correction spread alone.

## 2026-08-06 — New tool: `jetronic_live.py` (live tuning copilot)

Streams telemetry while driving, attributes lambda fast-correction to VE cells in real
time (steadiness / warm / closed-loop / cell-proximity gates), and builds a damped,
check-summed `.imf` proposal on demand or automatically at engine-off. Build-only by
design — it never writes to the ECU; flashing stays a stopped-car operation in
InventCommander, verified via Read All + md5. Auto-reconnects across USB dropouts and
writes its raw CSV line-buffered so a crash cannot lose a log. Validated by replaying a
25-minute drive log: proposals matched a hand-derived revision cell-for-cell in direction.
See the "Live tuning copilot" section in the README.

## 2026-08-06 — Correction: `Param1[7]` is cranking "Cold enrichment", NOT injector flow

Earlier revisions of these docs decoded `.imf` `Param1raw` byte 7 as "injector flow in
cc/min" (150 in the M103 base cal) and recommended setting it to the real injector size as
the preferred rich-running fix. **Flash testing on the documented car proved that decode
wrong:**

- **`Param1[7]` is the "Cold enrichment" field** on Commander's *Injection → Engine start*
  tab — a **cranking-only** enrichment (stock M103 value 150). The UI field tracked the
  byte exactly across two flashes (150 → 190).
- It does **not** scale running fuel: the running mixture was unchanged by `Param1[7]`,
  while VE-table changes moved fueling 1:1. **Running fuel follows the VE table alone —
  the ECU has no injector-flow field**, so the global VE rescale is the only injector-size
  correction.
- Two more anchors from the same tab were verified: **`Param1[23]` = "Idle position"**
  (the engine-start idle-valve preset, also the valve's park position at low runlevels)
  and **`Param1[24]` = "Idle pos cold add"**.

Corrected in `docs/M103_CONVERSION_NOTES.md` (trap section rewritten, FuelConsumed
inference retracted, verified `Param1raw` anchor table added), `docs/VEHICLE_SETUP.md`,
`samples/M103_190cc_fw417_reference_patch.md`, `samples/README.md`, `README.md`, and
`drafts/FORUM_POST.md`.
