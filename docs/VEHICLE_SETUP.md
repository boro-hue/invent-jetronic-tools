# The vehicle behind this repo

Everything in this repository — the logger, the protocol notes, the migration-bug write-up, the reference patch and the example log — was developed and verified on one real car. Details here so you can judge how much of it transfers to yours.

## Vehicle

| Item | Value |
|---|---|
| Car | Mercedes-Benz W124/W126-era sedan, **automatic transmission** (torque converter — relevant for the idle-in-Drive findings) |
| Engine | **M103 3.0L** naturally-aspirated inline-6, 2,962 cc (88.5 mm bore × 80.25 mm stroke) |
| Variant | Catalyst version: 9.2:1 compression, ~177 hp stock @ 5700 rpm, 255–260 N·m @ 4400 rpm |
| Valvetrain | SOHC 12v, hydraulic lifters; firing order 1-5-3-6-2-4; redline ~6200 rpm |
| Original fuel system | Bosch **KE-Jetronic / CIS-E** (mechanical-electronic CIS, ~5.3–5.8 bar system pressure) — fully removed |
| Exhaust | Catalytic converter fitted (one reason chronic rich running had to be fixed quickly) |

## Conversion

| Item | Value |
|---|---|
| ECU | **Invent Jetronic** standalone, firmware **4.1.7_update1** (started on 4.1.6 — see the [migration-bug write-up](MIGRATION_BUG_4.1.6_to_4.1.7.md)) |
| ECU controls | **Fuel injection + idle control valve only** |
| Ignition | **Stock Mercedes EZL module retained.** The ECU feeds it an emulated air-flow-pot load signal (`SensorAfsEmulator` / the `MafEmu` channel). The ignition tables inside the ECU are inert on this install and were never touched. |
| Load sensing | MAP speed-density (no MAF) |
| Injection mode | Batch, **no cam sync** (`PhaseLockFlag=0`) |
| Injectors | Bosch **190 cc/min** (3-bar rating) port injectors, six |
| Fuel pressure | **4 bar**, regulator **not** vacuum-referenced |
| Lambda | **Narrowband-behaving** oxygen sensor input (AFR channel snaps 14.0 / 14.7 / 15.4, classic bang-bang); closed loop + adaptation enabled. Full-load mixture is unverifiable with it — see the [conversion notes](M103_CONVERSION_NOTES.md) |
| Idle valve | 2-wire PWM valve on the ECU's idle output, ~98 Hz; base duty is IAT-scheduled by the firmware |
| Throttle | TPS calibrated 0–100 %; the separate idle/full-load switch inputs are not correctly wired on this install (both read active — a known open item, documented so you can compare) |
| Connection | ECU's FTDI FT232R USB-serial (the Commander cable) and the on-board HC-06 Bluetooth module |

## State of tune (full map in `samples/M103_190cc_fw417_reference_map.imf`; exact changes in `samples/M103_190cc_fw417_reference_patch.md`)

Stock M103 base calibration plus, in order of application:

1. **Global VE ×(150/190)** — compensates the preset's 150 cc/min injector-flow assumption vs the real 190 cc injectors (~27–30 % rich as delivered).
2. **Idle lambda-target flatten** — 600–700 rpm columns, 20–70 kPa rows: 13.0 → 14.7 AFR (removes an idle limit cycle).
3. **Post-migration parameter repair** — Param2 tail restored to stock 4.1.7 values, head defaults harmonized (the migration bug).
4. Warm idle target 800 rpm.

Verified behavior on this state: stable warm idle (~780–820 rpm neutral, σ 21–34; ~675–690 rpm in Drive), closed loop active at idle and cruise with single-digit corrections, adaptation banking normally, no stall tendency across multiple logged sessions. Known remaining quirks: weak idle-valve closed-loop gains (idle drifts with intake-air temperature), accel enrichment not yet configured (mild tip-in lean), lean-of-stoich idle dwell bias.

## What transfers to your car, and what doesn't

- **Transfers:** the logger and protocol (any Invent Jetronic ECU on these firmware generations), the migration-bug check and fix method, the flash-hygiene workflow, the diagnostic patterns (using closed-loop correction to detect config errors).
- **Transfers with care:** the M103 conversion notes (same engine family, similar installs), the reference patch (apply-to-your-own-readout only — **never flash it blindly**, see `samples/README.md`).
- **Install-specific:** sensor calibrations, idle-valve duty values, switch-input wiring, ignition arrangement.

---

*Anything you replicate from this page — map or parameter changes especially — can stall or damage an engine. Verify with the car stationary before driving; use at your own risk.*
