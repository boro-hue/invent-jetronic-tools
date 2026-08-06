# M103 conversion field notes — Invent Jetronic

Practical findings from converting a Mercedes M103 3.0 inline-6 (W124/W126, automatic)
from Bosch KE-Jetronic (CIS-E) to the Invent Jetronic standalone ECU. Everything below
was measured on the repo owner's own car with the vendor's InventCommander software and
the logging tools in this repo, on firmware **4.1.6** and later **4.1.7_update1**, starting
from the vendor's M103 base calibration (`v4.1.5_MB_6cyl_M103`).

Most of this should transfer to other CIS-era conversions (Mercedes M102, Audi 5-cylinder,
etc.) running the same ECU — the traps described here live in the configuration, not in the
engine.

> **Unofficial.** This repository is not affiliated with, endorsed by, or supported by
> Invent. It documents behavior reverse-engineered for interoperability with the author's
> own hardware. The vendor's software, firmware, and calibration files are their property —
> do not redistribute them; this repo contains only independent tools and notes.
>
> **Safety.** Editing ECU calibrations can make an engine stall, run dangerously lean, or
> damage the engine and catalyst. Make one change at a time, keep numbered backups, and
> verify every change with the engine **stationary** and warm before driving. During this
> project one bad parameter set caused a real on-road stall. Use everything here at your
> own risk.

## Reference setup

| Item | Value |
|---|---|
| Engine | M103 3.0 NA inline-6, 9.2:1 (catalyst version, 177 hp stock) |
| ECU scope | **Fuel injection + idle control valve (ICV) only** |
| Ignition | Stock Mercedes **EZL** module retained (see [Misc](#5-misc-observations)) |
| Load sensing | MAP, speed-density |
| Injectors | Bosch **190 cc/min** (EV1 style), replacing the CIS mechanical injectors |
| Injection mode | Batch, no cam sync (`BatchInjectionFlag=1`, `PhaseLockFlag=0`) |
| Lambda input | Behaves as **narrowband** in logs (see [Misc](#5-misc-observations)) |
| Firmware | 4.1.6, later updated to 4.1.7_update1 |
| Device | `Jetronic_XXXX` (see [PROTOCOL.md](PROTOCOL.md) for logging) |

**If a fresh M103 conversion runs badly, check in this order:**

1. The mismatch between the base calibration and the injectors actually fitted (section 1).
2. The AFR target table's idle columns (section 2).
3. What the closed-loop correction is telling you (section 3).
4. Idle valve closed loop and input-switch configuration (section 4).

---

## 1. The injector-mismatch trap — check this FIRST

**The M103 base calibration fuels roughly 27–30 % rich with 190 cc/min injectors.** The
base cal ships with the injector preset listed as "Bosch 107" in the software and is
evidently calibrated for smaller injectors; most M103 conversions fit **190 cc/min @
3 bar** injectors, and on the unchanged base map the test engine ran **≈ 27 % rich
everywhere, from the first start** (a uniform error matching 190/150 = +26.7 % almost
exactly).

> **Correction (2026-08-06) — there is no injector-flow field; the old decode was wrong.**
> Earlier revisions of this page decoded `Param1raw` byte 7 (`0x96` = 150 in the base cal)
> as "injector flow in cc/min" and recommended setting it to the real flow as the preferred
> fix. Flash testing disproved that: **Param1[7] is the "Cold enrichment" field on
> Commander's *Injection → Engine start* tab — a cranking-only enrichment (stock M103
> value 150; that it matched the old "150 cc/min" story is coincidence)**. The UI field
> tracked the byte exactly across two flashes (150 → 190), and the running mixture did not
> change with it, while VE-table changes moved fueling 1:1. **Running fuel follows the VE
> table alone — the only injector-size correction on this ECU is the global VE rescale
> below.**

### How it looks

| Symptom | Measured on the test car |
|---|---|
| Lambda correction at warm idle (CLT 80–86 °C) | `LambdaCorrFast` oscillating **−27…−34 %**, centered ≈ **−30 %** (healthy bang-bang regulation, not railed) |
| Same at 60–70 °C coolant | −22…−23 % (slightly different cells → small VE shape error on top of the global one) |
| Fuel economy | **22.7 L/100 km indicated** city (normal M103 automatic: ~13–15; the channel's absolute scale is unverified — see note below) |
| Idle fuel burn | ~4.4 L/h warm idle (later ~1.9 L/h once fixed) |
| Subjective | Feels ~30 % down on power, sooted plugs, slight idle misfire, poor economy. Rich running also damages the catalyst |

**FuelConsumed caveat:** the flow constant behind the ECU's fuel-used channel has not been
decoded (it is *not* Param1[7] — see the correction above). An earlier revision claimed the
channel under-read by exactly 190/150 and inferred a "true" ≈ 29 L/100 km; that arithmetic
rested on the wrong decode and is retracted. Treat the channel as *relative* — it did drop
in step with the fueling fix — and calibrate its absolute scale against a full-tank
fill-up before trusting litre figures.

### The fix — one global VE rescale, applied exactly once

Scale the whole VE table (`InjVe3d`) by 150/190 = **−21.05 %**, in one uniform step.
(Earlier revisions offered "set the injector flow field to 190" as the preferred
alternative — see the correction above: that field does not exist, and the byte it pointed
at only adds cranking fuel.)

Result on the test car: next warm idle log showed corrections collapse from ~−30 % to
**0…+6 %**. That single change removed the rich running, the soot, and most of the wasted
fuel.

> **Warning — lean risk.** The rescale is a one-time correction for *this* mismatch. Apply
> it to a map whose VE was already corrected — or to a car with different injectors — and
> the engine runs **~21 % lean**, enough to stall at idle and dangerously lean under load.
> Record the rescale in your revision notes. (What the software's injector-preset dropdown
> actually rewrites is not decoded — leave it alone on a corrected map, and re-verify with
> a log if you must change it.)

### Verified `Param1raw` anchors — Commander's *Injection → Engine start* tab

Byte decodes confirmed against the Commander UI on 4.1.7_update1 (0-based indexes into
`Param1raw`):

| Byte | UI field | Meaning |
|---|---|---|
| `Param1[7]` | **Cold enrichment** | Cranking-only enrichment; stock M103 = 150. No effect on running fuel |
| `Param1[23]` | **Idle position** | Idle-valve preset for engine start — also the valve's park position at low runlevels |
| `Param1[24]` | **Idle pos cold add** | Cold-start additive on top of *Idle position* |

### Injector sizing sanity check

190 cc/min × 6 supports roughly 175–190 hp at 80–87 % duty — adequate for a stock M103,
with no headroom for modifications. Rule of thumb from the project research:
`(HP × 0.5 BSFC) / (6 injectors × 0.8 duty)` → ~215–220 cc/min for a 200 hp target.

---

## 2. The idle target-table ledge (13.0 AFR at 600–700 rpm)

The M103 base calibration's AFR target table (`InjLambdaMult3d` in the `.imf` file) commands:

- **13.0 AFR** (`0x91`) in the **600 and 700 rpm columns**, at *every* NA load row
  (20–100 kPa), and
- **14.7 AFR** (`0x80`) from the **800 rpm column** up.

The warm idle target is 800 rpm — so a correctly-fueled warm idle sits **exactly on this
ledge**. Presumably the rich low-rpm columns are meant as anti-stall enrichment; combined
with a stoich 800 column they form a 13 % fuel step right where the engine lives.

### Why you only see it after fixing the fueling

With the ~27 % rich base (section 1), an extra 13 % of fuel barely changed combustion, so
the ledge was invisible. Once fueling is correct, the stoich cells make proper torque and
the step becomes a real torque step. The result is a textbook limit cycle, measured on the
test car:

1. RPM sags below ~770 → target interpolates toward the 13.0 cells, **and** MAP rises
   (48 → 62 kPa), so speed-density adds even more fuel → injection time jumps
   2.0 → 2.7–3.1 ms (**+30–50 % commanded fuel**).
2. Torque surges, RPM recovers to ~820 → target snaps back to 14.7 → fuel steps down
   ~−25 % → RPM sags again.

Measured: a **±25 % fuel see-saw with a ~1.3 s period** (1.34 s measured), idle mean
756 rpm, σ = 76–98 rpm, dips to 559 rpm, throttle closed the whole time.

### The second cost: open loop at every red light

Closed-loop lambda correction only runs in cells whose target is stoich (14.7).
Entering a non-stoich cell **disengages closed loop and resets the learned correction
to 0**. In-gear idle on an automatic (~650–750 rpm at 60+ kPa under converter load) lives
permanently in the 13.x cells — so on the unfixed base map the car sat open-loop at a true
~11–12 AFR at every red light, on top of the global rich error from section 1.

### The fix — flatten the low-load idle cells only

Set the **20–70 kPa rows** of the **600 and 700 rpm columns** to 14.7 (12 cells,
`0x91` → `0x80`).

**Deliberately keep the high-load rows (80–100 kPa) rich** if your acceleration enrichment
is not yet configured. During tip-in from idle the engine crosses 600–700 rpm at 86–96 kPa,
and on the test car `AccelCorr` was 0.00 through every throttle blip — those rich cells were
the *only* tip-in fuel. Removing them widens the lean tip-in hole.

Result on the test car (3-minute warm log): idle σ 92.5 → **25.5 rpm**, minimum rpm
559 → 807, no periodicity left, target = 14.7 in 100 % of samples, and closed loop ran
in Drive for the first time (drive cells trimmed to ≈ 0 %).

> **Honest trade-off:** after the fix, in-gear idle is leaner and sits lower (test car:
> mean ~692 rpm, σ ~29, vs. 738–742, σ 10–15 on the old rich map), and the N→D engagement
> dip got deeper (658 → 502 rpm) — the old smoothness was bought with fuel a catalyst
> cannot tolerate. A working idle-valve closed loop (section 4) is the correct cure. Until
> it works, stall margin at engagement is thin: avoid dropping into Drive with the A/C on
> or the steering loaded, and verify behavior stationary first.

### Decoding the raw table (for people diffing `.imf` files)

`.imf` calibrations are plain-text INI with hex byte tables. Anchors verified on 4.1.6:

- `InjLambdaMult3d`: **AFR = 14.7 × 128 / raw** → `0x80` = 14.7, `0x91` = 13.0,
  `0x97` = 12.5, `0xA4` = 11.5. Values above `0x80` are enrichment multipliers (raw/128).
- RPM axis (`GridRpm16`): raw × 50 → 600, 700, 800, 900, 1000, 1200, 1600, 2000, 2400,
  3100, 3500, 4100, 4400, 5100, 6100, 7000 rpm.
- Load axis: ≈ 20 + 10 × row, in kPa. Rows 0–8 are the NA region (20–100 kPa); rows 9+
  are boost rows for turbo variants.

The same edits can of course be made in the software's table editor — the raw decode is
mainly useful for diffing a calibration before/after flashing.

---

## 3. Read the closed-loop correction as a configuration-error detector

With a narrowband sensor the fast lambda correction bang-bangs; read the **center** of its
oscillation, not single samples, and only trust warm data (CLT ≥ 80 °C, where warm-up
enrichment is zero). Then:

| What you see | What it means | What to do |
|---|---|---|
| Large offset (beyond ~±15–20 %), roughly the **same in every warm cell** | **Global calibration error** — injector size vs what the calibration assumes, fuel pressure, dead time. Not a VE-shape problem | Correct it once, globally (for an injector mismatch that is one uniform VE rescale — section 1; there is no flow field). Do not tune it out cell-by-cell |
| Small offsets (single digits) that **differ per cell** | VE table shape error | Closed loop absorbs it; polish the cells later from logged data |
| Correction **pinned at exactly 0** | Cell is probably open-loop (non-stoich target), or the correction was just reset (transient / adaptation banking) | No information — check the cell's target AFR before concluding anything |

Worked example from the test car: a uniform ≈ **−30 %** at warm idle matched the predicted
+26.7 % injector mismatch almost exactly; after the single global VE rescale the
corrections read 0…+6 % — and the small remaining offset (+4…7 % lean, at ~900 rpm /
46 kPa cells) was a genuine per-cell VE shape error, left to closed loop.

The general lesson: **a large, stable, uniform correction is the ECU telling you something
global is wrong.** Cancel it with one documented global correction — never cell-by-cell,
which bakes the mistake into the table's shape and makes later changes dangerous (see the
lean-risk warning in section 1).

---

## 4. Idle valve: base duty follows intake air temperature

On this install the idle valve's base duty is scheduled from **IAT (intake air
temperature), not coolant** — measured ≈ **0.35 %/°C**, consistent with the car's "IAT
compensation" setting of 3.5 %. Evidence: a warm-up log sweeping coolant 34 → 86 °C held a
constant ~51 % valve duty, while across sessions the duty floor tracked intake temperature
(50.8 % on a cooler day → 54.3–55.1 % warm).

**Consequence:** if the idle closed loop has weak gains, idle speed drifts with the
weather, because the only thing actually moving the valve is the IAT schedule.

On the test car the closed loop was effectively dead, quantified over full logs:

- Valve position took only **3 discrete values** in a 3-minute log (54.3 / 54.7 / 55.1 %).
- **Zero net response** to steady errors of +102 rpm (2 min) and −108 rpm (1 min).
- Transient gain ~0.2–0.27 % duty per 100 rpm of error, where a working loop needs roughly
  2–5 %/100 rpm; integral action absent.

### Checklist if your idle valve seems frozen

1. **Idle / full-load switch configuration first.** On the test car the Commander log
   channels `InputSwIdleFlag` and `InputSwFullLoadFlag` were both stuck = 1 in every row —
   even at 95 % throttle. A contradictory pair means the inputs are unwired or
   mis-configured, and if the firmware gates the idle closed loop on a valid idle-switch
   state, this alone freezes the valve.
2. **PID gains and authority.** Check the idle control PID settings (this car showed
   kp 7 / ki 10 / kd 4 with an adjustment range of ~14 %). With integral action provably
   absent, raising ki is the first candidate. A small adjustment range also clamps how far
   the loop may move the valve from base duty.
3. **Base / minimum duty.** A high minimum duty stops the valve from ever closing —
   this car idled 1100–1450 rpm cold in neutral with the valve pinned near 50 %.
4. **Manual position test** (if the software offers one): force the duty down. If RPM does
   not fall, the problem is a vacuum leak or throttle-stop setting, not the valve.
5. **In-gear target:** verify whether Drive uses the same idle target or a separate one.

Do all of this with the engine warm and stationary, one change at a time, with a short log
between changes.

---

## 5. Misc observations

- **Batch injection, no cam sync.** Logs show `BatchInjectionFlag=1`, `PhaseLockFlag=0` —
  injection is batch-fired without a cam reference on this setup. Do not chase phase or
  sequential settings that the hardware cannot honor.

- **EZL-retained setups: never touch the ignition tables.** With the stock EZL distributor
  module doing ignition, the ECU only feeds it an emulated air-flow-pot load signal
  (`SensorAfsEmulator`, log channel `MafEmu`). The ignition/dwell tables present in the
  calibration file are **inert** on such installs — editing them does nothing except create
  confusion. Timing changes with load are normal (the emulated load signal steps ~+8 % at
  N→D, so EZL advance shifts with it by design).

- **Adaptation banking — periodic correction resets are NORMAL.** On firmware
  4.1.7_update1 the fast lambda correction periodically drops back to 0 from small values
  (every ~20–30 s, from single digits, ~11–12 times per short session, unrelated to
  throttle). This is **designed behavior**: the fast trim is folded ("banked") into a
  per-cell adaptation table and restarts from zero. Verify it yourself under *Lambda
  control → Adaptation → View* — the table accumulates small per-cell values (−13…+7
  observed). Do not chase these resets as a bug.

- **Narrowband vs. the wideband option.** Believe the log, not the checkbox. On the test
  car the AFR channel only ever showed 14.0 / 14.7 / 15.4 and `LambdaVoltage` swung
  0.44–0.85 V — classic narrowband bang-bang, although a wideband was assumed to be
  fitted. With a narrowband-behaving input, closed loop only works near stoich and **you
  cannot verify full-load mixture — avoid sustained wide-open throttle** until a real
  wideband reading exists.

- **Firmware updates can silently change what your settings mean.** The 4.1.6 →
  4.1.7_update1 updater migrated one settings block (Param2) positionally into a new,
  shorter layout (43 → 41 bytes), leaving stale values in re-purposed slots — e.g. a
  transient threshold read 0 where the 4.1.7 default is 50 (byte index 30), and two fields
  were clamped from 140/180 to 30 (indexes 34/37). On a byte-identical map this produced
  hyperactive transient fuel, repeated wipes of the lambda correction, a growing idle
  surge, and eventually a **real on-road stall**. After any firmware update: read the ECU
  back, compare every settings page against a fresh stock calibration for the new version,
  and verify with a stationary log before driving.

- **Log continuously, not with the vendor app while driving.** Commander's CSV logging
  suffered burst sampling and dropouts on the move (one 22-minute drive kept only
  stationary moments; another session lost 93 % of its duration). The standalone logger in
  this repo records gap-free at 20 Hz over USB or Bluetooth — see
  [PROTOCOL.md](PROTOCOL.md) and [BLUETOOTH.md](BLUETOOTH.md).

---

## 6. Method that worked

- **One change per flash** while diagnosing. Number every calibration revision (r1, r2, …)
  and never overwrite — `.imf` files are plain text, so diff before/after every change.
- **Read the ECU back after flashing** and verify it matches what you sent; keep the
  software's flash-history folder intact.
- **Predict before you flash.** Write down the expected effect (e.g. "corrections should
  drop from −30 % to single digits"), then check the next log against it.
- **Warm data only** for fueling conclusions (CLT ≥ 80 °C), except when deliberately
  tuning cold start.
- **Verify stationary before driving**, and no sustained wide-open throttle until the
  full-load mixture is actually measured.
