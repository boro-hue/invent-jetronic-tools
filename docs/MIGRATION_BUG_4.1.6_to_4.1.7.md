# Firmware migration bug: 4.1.6 → 4.1.7_update1 corrupts transient-fuel parameters (Param2 tail)

> **Unofficial community documentation.** This repository is not affiliated with, endorsed by,
> or supported by the vendor of the Invent Jetronic ECU. Everything here was reverse-engineered
> by the repo owner for interoperability with his own hardware. The vendor has been informed
> (see `drafts/VENDOR_BUG_REPORT.md`). The goal of this page is to help other owners recognize
> and repair the problem — not to criticize the product, which otherwise works well.

> **Safety warning.** Modifying ECU calibration data can stall the engine or damage it.
> An engine that dies while driving takes power steering and brake assist with it.
> Verify every change with the car stationary, in a safe place, before driving.
> Everything below is provided as-is, **use at your own risk**.

---

## TL;DR

If you updated your Invent Jetronic ECU firmware from **4.1.6** to **4.1.7_update1** while it
carried a customized (or even just previously-saved) map, the updater migrates the `Param2`
settings block **positionally**, byte by byte. The block shrank from **43 bytes to 41 bytes**
between versions and its layout changed, so the last 13 bytes (**idx28–40**) of your old
settings land in slots that now mean something else. On the documented unit:

- **idx30 became 0 where the stock 4.1.7 value is 50** — this slot is a transient-fuel
  threshold, so 0 means the transient logic fires on *any* MAP ripple;
- **idx34 and idx37** carried old-layout values **140 and 180**, which exceed the new slots'
  valid ranges and were **clamped to 30**.

The result: hyperactive MAP-rate transient fuel, the fast lambda correction repeatedly wiped
to zero, a lean-biased jumpy idle, a slightly rich drive mixture, a growing rpm oscillation
at drive idle — and, after ~20 minutes of driving, **one real engine stall at a stop in Drive**.

**Re-flashing your map through the 4.1.7 app does NOT fix it** — the app's converter produces
exactly the same bytes.

**The fix:** build a repaired map = your own ECU readout with Param2 bytes idx28–40 replaced
by the stock-4.1.7 values for your engine, flash it, read back and byte-compare before
starting the engine. Verified fix details below. After the repair the documented engine logged
its steadiest idle ever and the stall behavior did not return.

Documented on one ECU (M103 3.0 inline-6, Mercedes W124/W126 platform, ECU doing fuel +
idle valve only). The corrupted tail block is a near-universal vendor transient block —
identical in 8 of 10 calibrations shipped with the 4.1.7 app — so other engines migrated
from 4.1.6 very likely inherit the same corruption pattern.

---

## Who is affected

You are potentially affected if **all** of these are true:

1. Your ECU ran firmware **4.1.6** (the bug is documented for the 4.1.6 → 4.1.7_update1 step).
2. You updated to firmware **4.1.7_update1**.
3. The ECU carried your own map at update time (any map that was ever saved/flashed —
   even "stock plus one tweak" counts; the tables migrate fine, the *parameter block* does not).

You are **not** affected if you flashed a fresh stock 4.1.7 calibration from the app's
`calibrations/` folder after the update — those files contain correct 4.1.7 parameters.

## Symptoms you might notice

- Idle became **bumpy / jumpy** right after the firmware update, on a map that idled fine before.
- Mixture oscillation: rpm wanders, lambda correction visibly saw-tooths in the live view.
- A slow, **growing rpm oscillation while stopped in Drive** (automatic transmission),
  each dip deeper than the last. On the documented car the troughs walked down
  645 → 555 rpm at roughly 1.46 rpm/s — and on another day this pattern ended in a stall.
- Slightly **rich in drive** (~5 % on the documented car) while the idle sits lean.
- Feels like "the idle regulation got worse" although you changed nothing in the map.

None of these prove the bug on their own — see *How to check* below for hard tests.

## Background: what Param2 is

Invent `.imf` map files are plain-text INI. Besides the fuel/lambda tables they contain two
opaque byte blocks, `Param1raw` and `Param2raw` — the installation settings you normally edit
through the app UI (sensor scaling, idle control, enrichments, transient fuel...).
In firmware 4.1.6 `Param2` was **43 bytes**; in 4.1.7 it is **41 bytes** with a changed layout.
Throughout this page **idx N means the 0-based position in the `Param2raw` byte list.**

## Root cause

The firmware updater migrates `Param2` **positionally**: old byte 28 goes to new slot 28,
old byte 29 to new slot 29, and so on — no per-parameter (semantic) translation. Because the
layout changed, everything from **idx28 to idx40** (13 bytes, the "tail") ends up in
re-purposed slots. Two distinct corruption mechanisms were observed:

| Bytes | What happens | Effect |
|---|---|---|
| **idx30** | old-layout value **0** lands in a slot whose stock 4.1.7 value is **50** (a transient-fuel threshold) | zero threshold → MAP-rate transient logic triggers on ~2 kPa noise, continuously |
| **idx34, idx37** | old-layout values **140 / 180** exceed the new slots' allowed ranges → **clamped to 30** | wrong transient-fuel parameters |
| **idx9, idx15** (head) | not corrupted, but these keep old *defaults* (**100 / 0**) where the 4.1.7 factory defaults changed to **80 / 90** — idx15 is another "0 where the new stock value is 90" case, same pattern as idx30 | stale defaults; harmonizing them was verified harmless (see r4 below) |

Cross-check that this is parameter corruption and not a table problem: on the same
byte-identical map, base fuel delivery at matched cells was within **±1 %** before/after the
update — the tables migrated fine.

### Why re-flashing through the 4.1.7 app does not help

The obvious "fix" — load your old map in the 4.1.7 InventCommander and flash it again — was
tested: the app's converter performs the **same positional migration with the same clamps**,
and write == readback was proven byte-exact. You get the identical corrupted tail back.
The map-update utility shipped in the `calibrations/` folder only rewrites metadata
(version string), not parameter semantics. There is currently no tool in the package that
translates old-layout parameters to the new layout.

## Measured effects (same engine, same map, before vs after the firmware update)

From logged sessions at ~20 Hz (channels: `LambdaCorrFast`, `AccelTotalCorr`, `Map`, `Rpm`):

- **Transient correction hyperactive:** `AccelTotalCorr` nonzero in **84 %** of drive samples
  (range −22…+61, standing +8 % even at steady state) vs **~29 %** on 4.1.6.
  In a later stationary baseline log, |AccelTotalCorr| > 5 in **52 %** of samples.
- **Fast lambda correction wiped:** `LambdaCorrFast` snapped to 0 on ~2 kPa MAP transients
  about **30 times per log vs 1** on 4.1.6. In the pre-fix baseline it was effectively
  **pinned at 0 for 4.4 minutes straight** (24 wipes) — closed-loop trim could never accumulate.
- **Mixture:** ~**5 % rich in drive**; neutral idle lean-biased (lean dwell 76 % of samples).
- **Stability:** growing drive-idle oscillation (troughs 645 → 555 rpm, deepening ~1.46 rpm/s);
  marginal warm start (sag to 234 rpm, caught with pedal).
- **One documented real stall:** after ~20 min of driving, a large oscillation at a stop in
  Drive took rpm below 500 and the engine died. First and only stall on this installation.

## How to check if you are affected

### A. Compare your Param2 tail against stock (definitive)

1. In InventCommander 4.1.7, **read your ECU** and save the readout `.imf`.
2. Open the readout and the **stock 4.1.7 calibration for your engine** (from the
   `calibrations/` folder of your own app installation — stock files are the vendor's,
   they are not redistributed here) in a text editor.
3. Compare `Param2raw` bytes **idx28–40** (0-based).
   - **idx30 = 0 is the smoking gun** — in the 10 calibrations shipped with the 4.1.7 app,
     idx30 is never 0 (stock M103 value: 50).
   - The whole tail block idx28–40 is identical in 8 of those 10 cals, so a tail that
     doesn't match your engine's stock cal is highly suspicious.

### B. Log warm idle for 2 minutes (behavioral)

Use InventCommander's CSV logging, or this repo's `jetronic_logger.py`
(`py jetronic_logger.py --log --hz 20` over USB, or `--bt` after pairing — close Commander
first, they share the COM port). Engine fully warm, neutral, no touching the throttle:

- **Affected:** `LambdaCorrFast` repeatedly snaps to 0 within seconds of building anything,
  correlated with tiny (~2 kPa) MAP wiggles; `AccelTotalCorr` is busy (|value| > 5 in tens of
  percent of samples) although you never touch the pedal.
- **Healthy:** `LambdaCorrFast` builds a small value and holds it for tens of seconds;
  `AccelTotalCorr` ≈ 0 at steady idle. (Periodic small resets every ~20–30 s are normal —
  see *What is NOT this bug* below.)

### C. Seat of the pants (weakest, but free)

Idle got jumpy right after the firmware update; rpm oscillation grows while waiting at a
light in Drive; hesitation/mixture hunting you did not have on 4.1.6. Any of these after a
4.1.6 → 4.1.7 update justifies doing check A.

## The fix, step by step

> **Do all of this stationary. Do not drive until verification passes.** If in doubt at any
> step, stop and ask (open an issue). Never edit ignition-related tables.

1. **Read your ECU** with the 4.1.7 app and save the readout `.imf`. Keep an untouched copy —
   every change below happens on a **new numbered copy**, never on your only original.
2. **Record your old transient settings (optional but recommended).** If you ever customized
   transient/accel settings through the 4.1.6 UI, they lived in the old tail and **cannot be
   translated automatically** (on the documented unit exactly 3 bytes were user-set in the old
   layout: old idx28/29/40 = 30/44/50 vs old stock 20/30/60 — discarded by the repair, values
   noted for later re-application through the UI). Write yours down from the old app's UI
   screens if you still can, so you can re-enter them in the 4.1.7 UI afterwards.
3. **Build the repaired map:** in a copy of your readout, replace `Param2raw` bytes
   **idx28–40** with the corresponding 13 bytes from the **stock 4.1.7 calibration for your
   engine**. Change nothing else.
4. **Optional head harmonization:** if (and only if) your readout still has the never-touched
   old defaults **idx9 = 100** and **idx15 = 0**, set them to the new stock values
   **idx9 = 80, idx15 = 90**. On the documented unit this was verified harmless (it did not
   change behavior measurably) — it removes stale defaults, it is *not* the cure for the
   periodic small correction resets (those are normal, see below). If your values there are
   something else, you customized them — leave them alone.
5. **Flash** the repaired map with the 4.1.7 app.
6. **Read back and byte-compare against what you sent — before starting the engine.**
   Compare *parsed values*, not raw text: the app may normalize number formatting on write
   (observed once — value-exact, different token format).
7. **Verification log (engine on, car stationary):** fully warm engine, log ≥ 5 minutes at
   ~20 Hz: ~2 min neutral idle, several throttle blips, ~1 min at a stop **in Drive with your
   foot firmly on the brake**, then back to neutral. Pass criteria:

   | Check | Pass looks like (documented unit) |
   |---|---|
   | `LambdaCorrFast` survives throttle blips | not wiped on any blip (6/6 and 7/8 blips survived post-fix; pre-fix, wiped constantly) |
   | `AccelTotalCorr` at steady idle | ≈ 0; \|value\| > 5 in ≤ ~10 % of samples (was 52 % pre-fix) |
   | Drive-idle oscillation | rpm envelope does **not** grow while stopped in Drive (post-fix: 675–688 rpm, σ 24–30, no deepening; pre-fix: troughs walking 645 → 555) |
   | `LambdaCorrFast` accumulates | builds (e.g. to +14 %) and holds for tens of seconds (pre-fix: pinned at 0) |
   | Neutral idle | steady (post-fix: 780–820 rpm, σ 21–34 — the steadiest this engine ever logged; best 4.1.6 session was σ 25.7) |

8. Only after all criteria pass: a **cautious short test drive, ideally with the logger
   running**, watching drive-idle behavior at stops.

## What is normal and NOT this bug

**Periodic small `LambdaCorrFast` resets ≈ every 20–30 s are by design** (adaptation
banking), not corruption. The correction folds into the long-term adaptation table and
restarts from 0. You can watch the table accumulate per-cell values (small numbers like
−2, +1, +7, −13) under **Lambda control → Adaptation → View** in the app.
How to tell it apart from the bug:

| | Adaptation banking (normal) | Migration bug |
|---|---|---|
| Trigger | time-periodic (~20–30 s) | MAP/throttle transients, even ~2 kPa |
| Size when reset | small values (a few %) | any value, constantly — correction never accumulates |
| Blip correlation | independent of blips | wiped by blips and small MAP ripples |
| Adaptation table | visibly accumulating | trim can't build in the first place |

On the fixed unit, 11–12 such banking resets per ~4–5 min session remained — with the
adaptation table demonstrably accumulating. That is healthy behavior.

## Evidence appendix

### Timeline (2026, one ECU, M103 3.0, ECU = fuel + idle valve only, ignition on stock EZL)

| When | Event |
|---|---|
| Jul 20 | Firmware updated 4.1.6 → 4.1.7_update1. ECU carried a lightly-customized stock M103 map, verified healthy on 4.1.6 (idle limit cycle fixed, neutral σ 25.5, corrections settling 0…+7 %). |
| Jul 20, ~19:00 | First drive log on 4.1.7: `AccelTotalCorr` nonzero 84 % of drive samples (vs ~29 %), `LambdaCorrFast` wiped ~30×/log (vs 1), neutral lean dwell 76 %, ~5 % rich drive, growing drive surge. Base fuel at matched cells ±1 % → tables migrated fine, parameters did not. Session ended by deliberate key-off. |
| Jul 21, ~20:08 | After ~20 min driving: large oscillation at a stop in Drive, rpm < 500, **engine stalled**. First real stall on this installation. |
| Jul 21, 21:12 | Pre-fix baseline log: `LambdaCorrFast` pinned ≈ 0 for the whole 4.4 min (24 wipes); \|`AccelTotalCorr`\| > 5 in 52 % of samples. |
| Jul 21, 21:19 | Same map re-flashed through the 4.1.7 app: corrupted tail reproduced byte-exact (write == readback) → app re-flash ruled out as a fix. |
| Jul 21 | Param2 tail diffed against all 10 calibrations shipped with the 4.1.7 app: tail block identical in 8/10, idx30 never 0 in any → stock-tail transplant judged safe. |
| Jul 21, 21:30 | Repaired map (own readout + stock 4.1.7 tail idx28–40) flashed, read back value-exact. |
| Jul 21, 21:48 | 7-min gap-free 20 Hz verification log: blips 6/6 survived, `AccelTotalCorr` quiet (\|>5\| in 10 % of samples), no divergent drive surge, `LambdaCorrFast` builds to +14 and holds. 12 small periodic resets remained (later identified as adaptation banking). |
| Jul 21, 22:52 | Head harmonization flashed (idx9 100→80, idx15 0→90), read back value-exact. |
| Jul 21, ~23:02 | Second verification log: resets 11 vs 12 (unchanged → idx9/15 were not their cause), blips 7/8+ survived, steadiest idle ever logged (neutral 780–820, σ 21–34; drive 675–688, σ 24–30, no deepening). Stall behavior gone two sessions in a row. |
| Jul 21, ~23:10 | App's **Lambda control → Adaptation → View** shows per-cell values accumulating (−2, +1, +7, −1, −13 across 1200–2400 rpm columns) → periodic resets identified as designed adaptation banking. System assessed healthy on 4.1.7 + repaired map. |

### The numbers side by side

Same engine, same map content; "broken" = after positional migration, "repaired" = stock
4.1.7 tail transplanted (idx28–40) + head harmonization (idx9/idx15).

| Metric | 4.1.6 healthy | 4.1.7 broken | 4.1.7 repaired |
|---|---|---|---|
| `LambdaCorrFast` wipes | 1 per log | ~30 per drive log; pinned ≈ 0 for 4.4 min in baseline | blips survive 6/6, 7/8; only periodic adaptation banking (11–12/session, normal) |
| `AccelTotalCorr` activity | nonzero ~29 % of drive samples | nonzero 84 % of drive samples (−22…+61, +8 % standing); \|>5\| 52 % stationary | \|>5\| 9–10 % of samples, ≈ 0 at steady idle |
| Trim accumulation | settles 0…+7 % | cannot accumulate (wiped) | builds (e.g. +14 %) and holds tens of seconds |
| Mixture balance | on target | ~5 % rich drive; neutral lean dwell 76 % | lean bias reduced; adaptation accumulating |
| Drive idle at a stop | stable | troughs deepening 645→555 rpm (~1.46 rpm/s) | 675–688 rpm, σ 24–30, no deepening |
| Neutral idle σ | 25.5–25.7 (best) | jumpy, lean-biased | 21–34 (steadiest session logged) |
| Stalls | 0 | 1 (at a stop in Drive) | 0 in verification sessions |

Channel notes: `LambdaCorrFast` is telemetry byte s8[21] and `AccelTotalCorr` is d[28] in the
live-telemetry frame (see `docs/PROTOCOL.md`; d28 was identified during this investigation).
Both are also available as columns in InventCommander's own CSV logging.

---

*Unofficial, reverse-engineered documentation for interoperability with the author's own
hardware. Not affiliated with or endorsed by the vendor. No vendor binaries or firmware
are distributed in this repository; the sample map is the author's own ECU read-out,
shared for reference — obtain the vendor software and original base calibrations from the
vendor. Modifying ECU data is at your own risk; verify stationary before driving.*
