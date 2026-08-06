# CLAUDE.md — AI assistant context for invent-jetronic-tools

This file gives an AI assistant (Claude Code reads it automatically; paste it into any
other AI) the full working context of this project so it can help safely and correctly.
Everything here was learned on a real car — a Mercedes M103 3.0 inline-6 (W124/W126,
automatic) converted from KE-Jetronic to an **Invent Jetronic standalone ECU**, firmware
**4.1.7_update1**, controlling **fuel injection + idle valve only** (ignition stays on
the stock EZL distributor module, narrowband lambda, batch injection, 4 bar
non-vacuum-referenced fuel pressure).

## Hard safety rules for AI assistants

1. **Never write to the ECU and never build tooling that writes to the ECU while the
   vehicle can move.** All tools in this repo are read-only by design. Flashing is done
   by the human, stationary, in InventCommander.
2. **Every flash must be verified**: build file → record md5 → File→Open in Commander →
   spot-check values on screen → write → **Read All** → byte-compare the read-back
   against the intended file. Commander's editor state (not the file on disk) is what
   gets written, and Commander can silently overwrite files on disk with its editor
   contents — both failure modes have destroyed work repeatedly.
3. **One change per flash** wherever possible; otherwise changes must act in different
   phases (e.g. cranking vs running) so results stay attributable.
4. **Never advise pulling HT (spark) leads** on an EZL car for cylinder-balance tests —
   open secondary can kill the EZL and dump raw fuel into the catalyst. Injector
   electrical connectors only.
5. Every flash **wipes the ECU's lambda adaptation**. The first minutes after any flash
   run worse than the map deserves; judge nothing before ~5 minutes of running.
   Adaptation also carries several percent of the real tune — a map that was "validated"
   over weeks includes its adaptation layer, and flashing those same table bytes raw can
   run lean/rough until adaptation relearns.

## .imf file format (Commander map/settings files)

- Plain INI text, **CRLF line endings**, ASCII. Sections `[Common]`, `[Tables]`,
  `[Parameters]`.
- **Table lines** (`InjVe3d=`, `InjCltCorr=`, …): comma-separated **plain hex bytes**
  `0xNN`, including values ≥ 0x80 (e.g. `0x87`).
- **Parameter lines** (`Param1raw=`, `Param2raw=`): values ≥ 0x80 are written
  **sign-extended** as `0xFFFFFFNN`. Commander normalises on write — a plain `0x80`
  param token becomes `0xFFFFFF80` in the ECU read-back (value-identical, but it changes
  the file's md5; normalise before comparing).
- No trailing commas. Edit files line-wise in binary mode preserving CRLF; verify size
  and CRLF count after every programmatic edit.

## Verified decode anchors (firmware 4.1.7)

`Param1raw` (58 bytes) — indices verified against Commander UI on the reference car:

| idx | UI field | notes |
|---|---|---|
| 1 | Idle → Adjustment range | × 0.3929 % |
| 3 | (TPS-related) | |
| **7** | **Injection → Engine start → "Cold enrichment"** | **CRANKING-ONLY enrichment (stock M103 = 150). Historically misdecoded as "injector flow cc/min" — it is NOT. Running fuel follows the VE table alone; the ECU has no injector-flow field.** |
| 9 | Engine start → After-start add @0 °C | |
| 15/16/17 | Idle → PID kp/ki/kd | |
| 18 / 20 | Idle → Minimal position @0 °C / @60 °C | counts; % = ×0.38813 + 0.30 |
| 21 | Idle → Pickup rpm | single byte (max 255!) |
| 23 | Engine start → "Idle position" | ALSO the valve's park position at low runlevels |
| 24 | Engine start → "Idle pos cold add" | |
| 28 | Idle → Pickup % | × 0.3929 |
| 29 | Sensors → TPS 100 % calibration | ≈ 206.7 counts/V |
| 45 | Idle → IAT compensation | |

`Param2raw` (41 bytes in 4.1.7): 28–33 = Transients Cold block, 34–39 = Hot block
(35 = Hot Enrichment Level), 40 = Deadband. **4.1.6 had 43 bytes — see the migration
bug below.**

Key tables: `InjVe3d` (256 bytes = 16 MAP rows 20→170 kPa step 10 × 16 rpm columns from
`GridRpm16` × 50), value × 0.5 = VE %. `InjCltCorr` (16 cells, axis −30…120 °C step 10)
= warm-up fuel %. `InjLambdaMult3d` = lambda/AFR target map. `IdleRpm` (16 cells, same
temp axis) = idle target /10.

## Firmware behaviour discovered on the road (not in any manual)

- **Idle valve running position is anchored at ≈ Minimal position + ~43 counts
  (+ IAT compensation).** The PID contributes only ±1–2 counts per event regardless of
  gains — the idle loop slews ~1 count/sample (firmware limit). To move idle, move
  Minimal position; PID retuning does almost nothing.
- Valve authority ceiling = Minimal@60 + Adjustment range + IAT comp, and the IAT term
  moves with heat soak. Heat-soaked intake air also costs idle torque: same map, hot bay
  → visibly lower idle on *more* valve.
- **Run-level demotion trapdoor:** below ≈ 430 rpm the ECU demotes (RL3→RL1) and slams
  the idle valve to the park position (= Param1[23]), removing air exactly when the
  engine is trying to recover. Dips that stay above ~470 rpm recover; below ~430 the
  ECU finishes the stall. "Pickup" anti-stall is a single byte (max 255 rpm) and
  therefore sits below the trapdoor — it can never fire first.
- **Lambda correction speed is config-dependent:** Main tab "Fuel correction on idle"
  vs "on load" (reference car: 1 vs 10 = idle corrects 10× slower). Consequence: short
  idle logs understate richness, and driving logs mask idle-region errors. A narrowband
  pegged ≥ ~0.85 V with near-zero switching = saturated rich, no usable signal, corr 0.
- Engine-start valve position must exceed the running idle requirement or the engine
  only starts with throttle (reference car: start 113 counts vs running need 120–137 —
  latent for weeks).
- Telemetry: `FuelConsumed` uses an unknown internal flow constant (likely the built-in
  injector table entry, not any user field) — calibrate litres against a pump fill
  before trusting it.

## Firmware 4.1.6 → 4.1.7 migration

The updater migrated Param2 positionally (43 → 41 bytes) leaving old values in
re-purposed slots (see `docs/MIGRATION_BUG_4.1.6_to_4.1.7.md`). On the reference car the
conversion was effectively brute-forced back to health slot-by-slot, and subtle
after-effects still surfaced for weeks. **Recommend to every user: do not migrate a
4.1.6 map. Start from the stock 4.1.7 preset for the engine, re-enter known-good
settings on top, re-tune fuel via closed-loop correction.**

## Tools in this repo

- `jetronic_logger.py` — 20 Hz CSV logger (USB COM3 19200 8N1, RTS high/DTR low, or
  Bluetooth SPP). Read-only: three poll commands (live 0x01 frame, version, name).
  25 verified channels + all 50 raw bytes per row.
- `jetronic_live.py` — live tuning copilot. Streams while driving, attributes
  `LambdaCorrFast` to VE cells behind gates (warm ≥75 °C, closed-loop target ≥14.0 AFR,
  no fuel-cut, 0.6 s steady window, cell-proximity), then builds a **damped** .imf
  proposal (default: half the measured correction, ±8 % clamp, ≥200 samples/cell, 3 %
  dead-band) from the newest Commander `history/*_read_all.imf`. Exports a JSON+MD
  session report every build (per-cell stats, rejection funnel, events, health flags) —
  the report is the hand-off artifact for AI analysis (`--ai on-fail|always`, default
  backend `claude -p`, or any `--ai-cmd`). AI output is advisory only.
- `re-tools/` — the USB-sniffing / regression toolkit used to derive the channel map.

## How tuning actually converges here (for an AI advising a user)

The lambda sensor → ECU fast-correction is the only ground truth. A cell's mean
correction is the map's measured error at that cell. Convergence loop: drive (≥15 min,
warm) → apply ~half the measured per-cell error → flash once, stationary, verified →
adaptation relearns → repeat. 2–3 iterations converge. Things that corrupt the
measurement: transients (gate them out), misfire (inflates spread — check per-cell std),
saturated sensor (no signal), open-loop enrichment regions, cold engine, the first
minutes after a flash. If closed-loop correction sits near zero and the sensor switches
around 0.45 V at ~0.3–0.6 Hz, the fuel map is correct — stop tuning fuel.
