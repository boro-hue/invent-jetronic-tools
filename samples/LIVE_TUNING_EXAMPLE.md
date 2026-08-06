# Worked example — one real drive through the live tuning copilot

This folder contains a complete, real end-to-end example of the `jetronic_live.py`
pipeline: a genuine 25-minute / 14.35 km road log from the reference M103, the session
report the tool exported from it, and (below) what the numbers mean and what happened
next. Reproduce it yourself in one command — no hardware needed:

```
py jetronic_live.py --replay samples\example_drive_25min_rb15.csv --maps-dir <your Maps folder>
```

## The input

`example_drive_25min_rb15.csv` — 30,000 rows at 20 Hz: warm-up already done (CLT 80–88),
mixed town driving, held steady speeds, nine in-gear stops at lights, a couple of pulls
to ~3000 rpm, idle at the end. The map on the ECU during this drive ("rb15") had a
freshly corrected idle region but an untouched — and, it turned out, uniformly rich —
cruise area.

## What the tool did with it

**Rejection funnel** (the point of the gates — most data is not evidence):

| gate | rejected | why it exists |
|---|---|---|
| between rpm cells | 10,395 | operating point smeared between columns — attribution would lie |
| transient | 8,317 | accel/decel enrichment pollutes the correction signal |
| between MAP rows | 4,499 | same as columns, vertical |
| not running / open-loop / warming | 328 | no usable lambda control in these states |
| **accepted** | **6,461 (21.5 %)** | warm, steady, closed-loop, cell-centred |

Those 6,461 samples landed in 35 distinct VE cells; 9 cells collected enough clean data
(≥200 samples) with a correction worth acting on (≥3 %):

| cell | rpm | MAP | n | mean corrF | VE change |
|---|---|---|---|---|---|
| r03c05 | 1200 | 50 | 226 | −9.7 | 56.0 → 53.5 % |
| r04c00 | 600 | 60 | 552 | −8.6 | 51.0 → 49.0 % |
| r04c01 | 700 | 60 | 540 | −7.6 | 52.0 → 50.0 % |
| r02c03 | 900 | 40 | 484 | −5.0 | 45.0 → 44.0 % |
| r01c05 | 1200 | 30 | 579 | −4.9 | 55.0 → 53.5 % |
| r02c04 | 1000 | 40 | 354 | −4.5 | 50.5 → 49.5 % |
| r03c02 | 800 | 50 | 732 | −4.2 | 46.0 → 45.0 % |
| r03c01 | 700 | 50 | 399 | −4.0 | 46.0 → 45.0 % |
| r01c06 | 1600 | 30 | 362 | −3.6 | 55.5 → 54.5 % |

Every change is half the measured error (damping 0.5), so nothing overshoots; cells the
drive never visited are byte-identical to the base map. The proposal `.imf` came out
check-summed and ready to flash — stationary, in Commander, verified with Read All.

## Why we trust it: the blind cross-check

This drive had **already been analysed by hand** (hours of per-cell work) before the
tool existed, producing a hand-built revision. The tool, given the same CSV, proposed
the **same cells in the same direction** — the ECU's own lambda correction is the ground
truth for both, so agreement is expected, but it is still the strongest validation a
one-command replay can give. The hand analysis took an evening; the replay takes seconds.

## The health flags caught something real

```
high_spread_cells (misfire/sensor noise?): r01c06 std=7.9, r02c03 std=6.6,
r02c04 std=6.6, r03c05 std=9.5, r04c00 std=8.0, r04c01 std=8.0
```

The reference engine has a known intermittent per-cylinder misfire (~1.5–1.8 events/s at
idle, hardware, present across every map revision). The tool has no idea that fault
exists — yet it flagged elevated correction *spread* in exactly the idle and in-gear
idle cells where a misfire dumps unburnt oxygen past the lambda sensor. That is the
`--ai on-fail` trigger doing its job: when per-cell variance says "this signal is
contaminated", the exported report (see `example_live_tuning_report.md`) is handed to a
human or an AI instead of being silently trusted.

Practical reading of the flags here: the *means* of those cells are still usable (the
misfire adds noise, not bias, and n is in the hundreds), but anyone tuning a similar
engine should fix ignition/injector hardware before chasing the last few percent of
fuel — the spread column tells you when you have reached that point.

## What happened on the car

The same evidence, applied as a (slightly larger, hand-reviewed) revision on the
reference car, produced: lambda correction at idle from −20 % → ≈ −1 %, healthy
narrowband switching restored (0.45 Hz), a stall-free 25-minute drive with nine
survived in-gear stops — the drive in this very log — and this cruise proposal as the
next iteration. Two to three damped iterations like this one are what convergence
looks like: each drive, the corrections get smaller.

## Files

| file | what it is |
|---|---|
| `example_drive_25min_rb15.csv` | the raw 20 Hz drive log (30,000 rows) |
| `example_live_tuning_report.md` | the session report `jetronic_live.py` exported from it — the AI-readable hand-off artifact |
