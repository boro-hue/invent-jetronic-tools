# invent-jetronic-tools

Unofficial, community-made logging and reverse-engineering tools for the **Invent Jetronic** aftermarket standalone ECU, as used on classic-car EFI conversions (old Mercedes, Audi, and similar).

> **Unofficial.** This project is not affiliated with, endorsed by, or supported by the ECU vendor. Everything here was reverse-engineered from serial traffic between the author's own ECU and the vendor's InventCommander app, purely for interoperability with the author's own hardware. This repository contains **no vendor software or firmware**; the sample map in `samples/` is the author's own tuned configuration, read out of the author's own ECU and shared for reference — obtain the vendor's software and original base calibrations from the vendor.

**About to update ECU firmware from 4.1.6 to 4.1.7?** Read [docs/MIGRATION_BUG_4.1.6_to_4.1.7.md](docs/MIGRATION_BUG_4.1.6_to_4.1.7.md) first (summary in the warning section below).

## What this is

- `jetronic_logger.py` — a standalone continuous logger that talks the ECU's serial protocol directly, over the USB/FTDI cable or the ECU's built-in Bluetooth. It writes plain CSV you can open in anything.
- `docs/` — the reverse-engineered protocol and channel map, a Bluetooth guide, a detailed write-up of a firmware-migration bug, flash-hygiene working habits, M103 conversion field notes, and the exact vehicle setup everything was verified on.
- `re-tools/` — the toolkit that was used to decode the protocol, so you can verify or extend the channel map for your own firmware and engine.
- `samples/` — the road-verified reference map for an M103 + 190 cc install, the same changes as an apply-it-yourself patch, and a complete example log from the documented car.

The vendor's Commander app remains the tool for viewing and changing settings and for flashing. These tools fill one gap: **reliable, gap-free logging while driving**, plus public documentation of how the ECU talks.

## Why

Commander's built-in CSV logging worked at a standstill on the author's car, but dropped out while driving: a 22-minute city drive produced only short stationary bursts separated by minutes-long gaps (all the actual driving fell in the gaps), and a later driving session lost about 93 % of its data the same way. You cannot diagnose transients, surge, or a stall from logs that vanish whenever the car moves.

This logger polls the ECU's live-telemetry frame itself at a steady rate and writes every row: gap-free through key-on, engine start, idle, throttle blips, and driving. Verified result: a 7-minute engine-running session at 20 Hz with 8,398 rows and zero gaps.

## Features

- **Gap-free 20 Hz CSV logging** over USB or Bluetooth. The ECU is polled (it never speaks unprompted); 20 Hz is comfortable — Commander itself polls at roughly 11 Hz.
- **25 named channels with verified scalings** — RPM, MAP, CLT, IAT, injection time, TPS, AFR and target AFR, fast/slow lambda correction, idle-valve position, battery voltage, raw sensor voltages, and more. Scalings were locked by time-aligned linear regression against Commander's own CSV, R² ≥ 0.99 per channel (see [docs/PROTOCOL.md](docs/PROTOCOL.md)).
- **All 50 raw data bytes** (`d00`–`d49`) in every row — channels that are not labelled yet are still captured, and labels can be added later from the very same log.
- **Reconnect-friendly** — Bluetooth connects with retries while the RFCOMM link comes up, and the logger is a single self-contained script that is easy to wrap in a retry loop for long drive logs.
- **Handshake / probe / capture modes** — connection smoke test, passive baud scan, and raw byte capture with timing, for protocol work on other firmwares.
- **Read-only by design** — the logger sends only the three documented poll commands (live telemetry, firmware version, ECU name). It never writes to the ECU.

## Quick start

You need: Python 3, the ECU's USB/FTDI cable (or a paired Bluetooth link), ignition ON, and **InventCommander closed** — the ECU accepts one connection at a time, and the logger will tell you if the port is busy.

```
py -m pip install pyserial
```

Smoke-test the link (reads firmware version, ECU name, and one live frame):

```
py jetronic_logger.py --handshake                 # USB (default COM3; override with --port COM5)
py jetronic_logger.py --bt --handshake            # Bluetooth
```

Expected output:

```
Handshake:
  firmware: 4.1.7
  ecu name: Jetronic_XXXX
  live poll: OK
```

Log:

```
py jetronic_logger.py --log --hz 20 --seconds 600          # 10-minute USB log
py jetronic_logger.py --bt --log --hz 20 --seconds 600     # same, wireless — ideal while driving
```

CSV files land in `logs/jlog_*.csv` next to the script: elapsed time, the 25 named channels, then all 50 raw data bytes.

Other modes: `--list-bt` (show paired Bluetooth serial ports), `--probe` (passive baud scan), `--capture` (raw bytes with inter-byte timing, a protocol-RE aid). Defaults: `--port COM3`, `--baud 19200`, `--hz 20`, `--seconds 120`.

Developed and tested on Windows (`py` launcher). USB logging should work anywhere pyserial does — use `python3 jetronic_logger.py --port /dev/ttyUSB0 ...` — but `--bt` auto-detection is Windows-only.

## Bluetooth

The ECU's Bluetooth module is a Bluetooth Classic HC-06 (Serial Port Profile, not BLE). Once paired — device name **"Jetronic"**, PIN **0000** — Windows exposes it as an ordinary COM port and the logger talks the exact same framed protocol as over USB. Laptop on the passenger seat, no cable to the ECU.

Pairing steps, picking the right COM port, and the gotchas (one connection at a time, slight extra latency): **[docs/BLUETOOTH.md](docs/BLUETOOTH.md)**.

## Firmware 4.1.6 → 4.1.7 update warning

> ⚠ **The 4.1.6 → 4.1.7 firmware updater migrated the ECU's Param2 settings block positionally into the new 41-byte layout (previously 43 bytes), leaving old-meaning values sitting in re-purposed slots — on the author's ECU, index 30 came out 0 where stock 4.1.7 calibrations use 50, and indexes 34/37 were clamped from 140/180 down to 30.** On an otherwise byte-identical map this caused hyperactive transient fuel, repeated lambda-correction wipes, a growing idle surge, and eventually a genuine engine stall while driving.
>
> Full write-up — how it was found, how to check your own ECU, and the fix: **[docs/MIGRATION_BUG_4.1.6_to_4.1.7.md](docs/MIGRATION_BUG_4.1.6_to_4.1.7.md)**

If you have already updated: read the ECU back with Commander and compare the migrated parameters against a fresh stock 4.1.7 calibration for your engine **before** driving. The write-up is meant as a reproducible bug report — the goal is to help get the migration fixed, and to keep other cars off the road shoulder in the meantime.

## Repo map

| Path | What it is |
|---|---|
| `jetronic_logger.py` | The logger. One file, pyserial is the only dependency. |
| `docs/PROTOCOL.md` | The reverse-engineered serial protocol: link settings (19200 8N1, RTS/DTR line state), frame format, poll commands, and the verified channel map with byte offsets and scalings. |
| `docs/BLUETOOTH.md` | Pairing and wireless-logging guide. |
| `docs/MIGRATION_BUG_4.1.6_to_4.1.7.md` | The firmware-migration bug: discovery, root cause, how to detect it on your ECU, and the fix. |
| `docs/FLASH_HYGIENE.md` | Safe map/firmware working habits: numbered revisions, write → read-back → byte-compare, one change per flash, never trusting a settings migration. |
| `docs/M103_CONVERSION_NOTES.md` | Field notes from the M103 KE-Jetronic → EFI conversion: the injector-mismatch trap (with the 2026-08-06 `Param1[7]` decode correction), the idle target-table ledge, reading closed-loop correction as a config-error detector, idle-valve checklist. |
| `docs/VEHICLE_SETUP.md` | The exact car and configuration all of this was developed and verified on. |
| `samples/` | Road-verified M103 + 190 cc reference map + the same changes as a patch (**do not flash/apply blindly**) and a complete ~8-min example log. |

| `re-tools/` | The reverse-engineering toolkit used to build the channel map — USB sniffing procedure (`SNIFFING.md`, `capture_combo.ps1`), pcap/frame decoding (`decode_pcap.py`, `analyze_frames.py`, `split_frames.py`), and regression tools that map raw bytes to Commander channels (`align_decode.py`, `validate_decode.py`, `decode_live.py`). Use these to verify or extend the map for other firmwares and engines. |
| `drafts/` | Reusable write-ups: the vendor bug report template and a forum announcement post. |

## Compatibility (the honest version)

Everything here was validated on exactly one setup:

- ECU firmware **4.1.7_update1**, InventCommander **4.1.7_update1**
- A Mercedes **M103 3.0 inline-6** conversion (W124/W126) where the ECU handles fuel injection and the idle valve only — ignition stays on the stock EZL distributor module
- Channel scalings locked against Commander's own CSV on that car (R² ≥ 0.99 per named channel)

What that means for you:

- **Firmware 4.1.6:** the protocol framing and poll commands are expected to be identical, but this is **unverified** — the captures were made against 4.1.7. Run `--handshake` first; if you get firmware, name, and a live poll, you are in business. Either way, please open an issue and report your firmware version and result.
- **Other engines / configurations:** the raw bytes `d00`–`d49` are always recorded faithfully. Treat the named-channel scalings as unverified on your setup until you have cross-checked a few values against Commander's live view — or re-derive the map for your firmware with `re-tools/`.
- Channels that never changed during the mapping sessions (accel-enrichment values, status/error flags, dwell, debug words) are not named yet; they are present in the raw bytes, and pull requests that lock more of them are welcome.

## Disclaimer

- **Use at your own risk.** The logger itself is read-only, but you will be using it around a running engine and, guided by the docs, possibly changing ECU settings or firmware with the vendor's own software. Modifying ECU data can make an engine run dangerously lean, stall, or be damaged. Verify every change stationary — at idle and with throttle blips, logger running — before you drive, and keep first test drives short and cautious.
- Unofficial and unaffiliated: all protocol information was derived by observing traffic from the author's own hardware, for interoperability. Nothing here speaks for the vendor.
- **No vendor binaries:** this repository contains no vendor firmware or software, and none will be accepted in issues or pull requests — please obtain those from the vendor and do not share them. Tuned maps read out of **your own** ECU are welcome as reference contributions (like the one in `samples/`), with personal identifiers removed.

## License

[MIT](LICENSE). Findings, corrections, and logs from other setups are very welcome — open an issue.
