# Samples

Real files from the project that produced this repo, shared as **reference material**.

## `M103_190cc_fw417_reference_patch.md`

The **complete, exact set of changes** behind the road-verified map for a **Mercedes M103 3.0 (6-cyl, NA, catalyst)** conversion (fw 4.1.7_update1, 190 cc injectors, EZL retained — full setup in [`../docs/VEHICLE_SETUP.md`](../docs/VEHICLE_SETUP.md)).

It is published as a *patch you apply to your own ECU read-out* rather than a full `.imf`, so the repo contains no vendor calibration data. Covers: the injector-flow VE compensation (×150/190), the idle lambda-target flatten (12 cells), the 4.1.6→4.1.7 migration parameter repair, and the idle-target tweak — each with exact cell indexes and values, plus the warnings that go with them.

### ⚠ DO NOT APPLY BLINDLY

- The VE change assumes **190 cc injectors with the flow field left at the preset's 150** — on a car whose flow is already configured correctly it makes the mixture **~21 % lean**, which can damage an engine under load.
- Sensor calibrations, idle-valve settings and input wiring are install-specific.
- If you flash any map: write → read back → byte-compare → verify stationary with a log before driving. See [`../docs/FLASH_HYGIENE.md`](../docs/FLASH_HYGIENE.md). Modifying ECU data can stall or damage an engine — use at your own risk.

## `example_log_start_idle_blips_drive.csv`

A complete, unedited ~8-minute session (9,490 rows) recorded by [`jetronic_logger.py`](../jetronic_logger.py) at ~20 Hz on the map above. Sampling is continuous while the engine runs; the only dropouts are a few seconds around cranking and engine-off, where garbled frames are counted and skipped, not written:

- t ≈ 0–45 s: key-on, engine off (note the atmospheric MAP ≈ 100 kPa baseline)
- t ≈ 45–50 s: **cranking and warm start** (the garbled-frame dropouts during the voltage sag are here — normal)
- t ≈ 50–186 s: warm neutral idle (~780–820 rpm)
- t ≈ 186–210 s: several throttle blips to ~66 %
- t ≈ 210–275 s: idle **in Drive** on the brake (~675–690 rpm)
- t ≈ 275–320 s: back to neutral, then engine switched off
- t ≈ 320–475 s: ignition on, engine off — a clean sensor-baseline tail (RPM decodes as a small constant here: the period bytes read 0xFFFF when there is no crank signal)

Columns: wall-clock `t_s`, 25 decoded channels (see [`../docs/PROTOCOL.md`](../docs/PROTOCOL.md) for scalings), then all 50 raw frame bytes `d00`–`d49` so future channel discoveries can be applied retroactively to old logs.

Useful things visible in this log: closed-loop lambda bang-bang at idle, the periodic correction→adaptation banking resets (~every 20–30 s — normal), MAP-rate transient fuel firing on blips (`d28`), N→D engagement dip, and the IAT-scheduled idle-valve base duty.
