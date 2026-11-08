# mustang_obd — catch-all OBD-II logger (2016 Mustang 3.7 V6)

A single-script data logger that reads **everything the car will tell a generic
OBD-II adapter** over Bluetooth (or USB / WiFi) and writes it to CSV + JSON. It was
written for a 2016 Ford Mustang 3.7 V6, but the core works on any CAN-bus OBD-II car
(all US cars 2008+, most European cars 2004+). The Mustang-specific part is only the
optional enhanced-data profile.

**Read-only by design.** The only diagnostic services it can send are 01 (live data),
02 (freeze frame), 03/07/0A (stored/pending/permanent trouble codes), 06 (monitor test
results), 09 (vehicle info) and 22 (ReadDataByIdentifier). It cannot clear codes,
write, run routines or change diagnostic sessions — anything else is refused in code
before a byte is sent (`guard_obd` / `guard_at`, covered by tests).

**On a phone?** The same tool as an Android app (Android 6+), with a foreground service so
logging survives the screen turning off: see [android/README.md](android/README.md) — APK in
[`android/dist/`](android/dist/).

## What it captures

| | How often | Where |
|---|---|---|
| Every **live PID** the PCM supports (RPM, speed, load, coolant, IAT, MAF, MAP, timing, fuel trims, O2/wideband sensors, catalyst temps, throttle/pedal, fuel level, baro, evap, module voltage, run time, …) — decoded **and** raw hex | fast PIDs every row, the rest round-robin | `logs/mustang_*.csv` |
| PIDs only another module (e.g. the TCM) reports | round-robin | same CSV, columns prefixed `7E9_` |
| Enhanced Ford data from the profile (transmission fluid temp, odometer — **unverified candidates**, see below) | round-robin | same CSV |
| Adapter-measured battery voltage | round-robin | `AdapterVolts_V` |
| Supported-PID map, PID lengths, constant values (OBD standard, fuel type, O2 layout…), VIN | once | `logs/mustang_*.meta.json` |
| Trouble codes (stored / pending / permanent), freeze frame, VIN / calibration IDs / CVN / ECU names, **Mode 06 results incl. per-cylinder misfire counts**, every live PID once | `--snapshot` | `logs/mustang_snapshot_*.json` + readable `.txt` |
| Every Mode 22 identifier an ECU answers | `--scan-dids` | `logs/mustang_didscan_*.csv` |

Rows are written as fast as the adapter allows. Fast-tier PIDs are bundled up to six
per request (if the PCM accepts it), and the adapter's "number of responses" shortcut
is used when it handles it correctly — both are tested at start-up and fall back
automatically. As a rough estimate, expect **2–5 rows/s with a cheap Bluetooth ELM327 clone** and
more with an OBDLink/STN-based adapter. Slow-tier values carry forward between updates.

## What you need

- A laptop with Python 3.8+ (`py` on Windows).
- `py -m pip install pyserial` (and `py -m pip install bleak` only for BLE adapters).
- An ELM327-compatible OBD adapter. Classic-Bluetooth adapters show up as a COM port.
  BLE-only adapters (Vgate iCar Pro BLE, Veepeak BLE+, many "iOS compatible" ones) have
  no COM port — use `--ble`. WiFi adapters: `--tcp 192.168.0.10:35000`.
- The car's OBD port is under the dashboard on the driver's side.

## Quick start

Try it with no car first — the built-in simulator behaves like an adapter on a
Mustang (synthetic values, fake VIN):

```
py mustang_obd.py --demo --log --seconds 20
py mustang_obd.py --demo --snapshot
```

**1. Pair the adapter** (classic Bluetooth): Windows Settings → Bluetooth & devices →
Add device → Bluetooth → pick the adapter (often "OBDII", "OBD2", "V-LINK"); PIN is
usually `1234` or `0000`. Windows then creates "Standard Serial over Bluetooth link
(COMx)" ports. `py mustang_obd.py --list` shows them.

**2. Plug in, ignition ON** (engine running is best), close any other OBD app
(Torque, FORScan, Car Scanner…) — an adapter accepts one connection at a time.

```
py mustang_obd.py --bt --handshake        # auto-finds the Bluetooth COM port
py mustang_obd.py --port COM7 --handshake # or name it explicitly
py mustang_obd.py --ble --handshake       # BLE adapter (scans by name)
```

Expected:

```
Handshake:
  link:      serial COM7
  adapter:   ELM327 v1.5
  battery:   14.3 V  (engine running)
  protocol:  ISO 15765-4 (CAN 11/500)
  ECU 7E8:   44 live PIDs supported
  VIN:       1FA6P8AM...
```

**3. Snapshot** (codes, freeze frame, misfire counters, IDs, every PID once):

```
py mustang_obd.py --bt --snapshot
```

**4. Log a drive** (Ctrl+C to stop; the CSV is flushed every row, so a crash or a
dead battery loses nothing):

```
py mustang_obd.py --bt --log
py mustang_obd.py --bt --log --seconds 1800       # 30 minutes
py mustang_obd.py --bt --log --pids 0C,0D,05,0B   # only these PIDs - much faster rows
```

If the Bluetooth link drops it reconnects by itself and keeps appending to the same
file; if the car stops answering (ignition off) it waits and resumes.

> Set it up before you drive and leave the laptop alone, or have a passenger run it.

## Enhanced Ford data (Mode 22) — read this before trusting it

Standard OBD-II is only the legislated subset. Ford's PCM has many more values
(transmission temperature, gear, knock, VCT angles…) behind manufacturer-specific
Mode 22 identifiers ("DIDs"). They are **not standardised and not published**, so:

- `profile_mustang_2016_37.json` ships with just two **community-cited candidates**:
  `1E1C` transmission fluid temperature (6R80 automatic; `S16(A,B)/16` °C) and `DD01`
  odometer. Neither is verified on this car. Sanity checks: fully warm 6R80 fluid is
  about 80–85 °C per Ford's workshop manual; compare the odometer with the cluster.
  Raw bytes are always logged next to the decoded value, so a wrong formula can be
  fixed later from the same log.
- An identifier the PCM rejects is skipped automatically at start-up (a manual-gearbox
  car simply won't answer `1E1C`).
- **Discover more:** `py mustang_obd.py --bt --scan-dids quick` sweeps the ranges where
  Ford PCM identifiers usually sit (a few minutes; `full` = all 65 536, about an hour;
  or give ranges like `1E00-1EFF,DD00-DDFF`; `--ecu 7E1` etc. for other modules). Do it
  parked with the engine idling. Every answering identifier lands in a CSV.
- **Log all of them raw:** `--log --extra-dids logs/mustang_didscan_7E0_....csv`.
  Then find what each one means by correlating with known channels (rev the engine,
  drive warm-up, shift gears), give it a name and an `expr` in the profile.

`expr` is a tiny arithmetic language: `A`…`Z` are the data bytes, plus
`S8() U16() S16() U24() U32()`, `+ - * / // % & | ^ << >>`. Nothing else is accepted.

## Options

| Option | |
|---|---|
| `--list` / `--handshake` / `--snapshot` / `--log` / `--scan-dids R` | mode |
| `--bt`, `--port COMx`, `--ble [ADDR]`, `--rfcomm MAC`, `--tcp HOST:PORT`, `--demo` | connection (default `--bt`) |
| `--protocol 6` | ELM protocol; 6 = CAN 11-bit 500k (Mustang), `0` = auto |
| `--seconds N`, `--hz N` | stop after N s; cap the row rate |
| `--pids 0C,0D,...` | log only these Mode 01 PIDs |
| `--slow-per-cycle N` | slow-tier requests per row (default 1) |
| `--profile FILE` / `none` | enhanced-data profile |
| `--extra-dids SCAN.csv` | also log every DID a scan found (raw) |
| `--no-multi`, `--no-count` | disable multi-PID requests / response-count shortcut (compatibility) |
| `--trace` | write raw adapter traffic to `logs/elm_trace_*.txt` — attach it when reporting a problem |

## Troubleshooting

- **"Adapter OK, but the car did not answer"** — ignition not ON, or the adapter is not
  fully seated. A 2016 Mustang is CAN 11-bit 500k; `--protocol 0` lets the adapter search.
- **"Could not talk to the adapter"** — another app is connected, wrong COM port
  (Windows creates two per paired device; `--bt` tries the outgoing one first), or the
  adapter is BLE-only (use `--ble`). If you also have other Bluetooth serial devices
  paired, pass `--port` explicitly so `--bt` does not probe them.
- **"answers without CAN headers"** — a very poor clone that ignores `ATH1`; it cannot
  tell ECUs apart. Use a better adapter.
- **Slow rows** — try `--pids` for the channels you care about, or a better adapter.
  `--trace` shows the round-trip time of every request.
- Cheap adapters draw current when parked — unplug when you leave the car for days.

## Tests

```
py -m unittest -v test_mustang_obd
```

28 tests against the built-in simulator (`elm_sim.py`): the read-only guard (including
"a full snapshot + log + scan session sends nothing outside the whitelist"), ISO-TP
multi-frame reassembly with interleaved ECUs and lost frames, PID/DTC/Mode 06 decoding,
adapter variants (response-count semantics, chunked output, spaces on, header-less
clones), Bluetooth drop + reconnect, ignition off mid-log, DID scan → `--extra-dids`.

Also checked during development (not part of the suite): every mode against the
independent [ELM327-emulator](https://github.com/Ircama/ELM327-emulator) over TCP and
over a serial port; the BLE transport against a mock `bleak` with two GATT layouts; and
all PID decoders against python-OBD (24 300 random-input comparisons; the only
difference is PID 0x32, where python-OBD sign-extends each byte separately and this
tool reads a 16-bit signed value — the one that matches the PID's documented
−8192…8191.75 Pa range), DTC strings (all 65 535) and Mode 06 unit scalings.

**Not yet verified on a real car or a real adapter.** The first real run should be
`--handshake`, then `--snapshot`, then a short `--log --trace`.
