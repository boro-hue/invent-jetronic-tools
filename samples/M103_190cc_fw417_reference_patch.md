# Reference patch — M103 + 190 cc injectors on firmware 4.1.7_update1

This is the **complete, exact set of changes** applied to the stock Invent M103 base calibration to produce the road-verified state described in [`../docs/VEHICLE_SETUP.md`](../docs/VEHICLE_SETUP.md). Apply these edits to **your own** ECU read-out; the author's full map is in `M103_190cc_fw417_reference_map.imf` next to this file if you prefer to diff directly.

`.imf` files are plain-text INI. You can make every change below either in the Commander UI or with a text editor on a saved copy of your read-out. Follow [`../docs/FLASH_HYGIENE.md`](../docs/FLASH_HYGIENE.md) for every flash: numbered copy → write → read back → byte-compare → verify stationary with a log.

> ⚠ **These values are correct only for this install** (190 cc @ 3-bar-rated injectors on a 4-bar non-vacuum-referenced regulator, otherwise-stock M103 base VE, M103 NA, narrowband-behaving lambda). Blindly applying the VE change to a car whose VE is already right for its injectors makes it **~21 % lean** — engine-damage territory. Read the reasoning in [`../docs/M103_CONVERSION_NOTES.md`](../docs/M103_CONVERSION_NOTES.md) first. Use at your own risk.

## 1. Injector-mismatch VE compensation (fixes ~27–30 % rich)

`[Tables] InjVe3d` (16×16, hex bytes): multiply **every** cell by `150/190 ≈ 0.7895`, rounding to nearest integer.

Example in Python, applied to the `InjVe3d=` line of your read-out:

```python
vals = [int(x, 16) for x in line.split("=")[1].split(",")]
vals = [round(v * 150 / 190) for v in vals]
```

*Correction (2026-08-06):* an earlier revision offered setting an "injector-flow field" (`Param1raw` byte 7) as the alternative fix. That decode was wrong — **`Param1[7]` is the cranking-only "Cold enrichment" field** on Commander's *Injection → Engine start* tab and does not scale running fuel. The VE rescale is the only fix; see the correction note in [`../docs/M103_CONVERSION_NOTES.md`](../docs/M103_CONVERSION_NOTES.md).

## 2. Idle lambda-target flatten (fixes a ~1.3 s idle limit cycle)

`[Tables] InjLambdaMult3d` (16×16, row-major, rows = load 20+10·row kPa, cols = RPM axis 600,700,800,…):

Set these **12 cells** from `0x91` (13.0 AFR) to `0x80` (14.7 AFR) — rows 0–5 (20–70 kPa) × columns 0–1 (600, 700 rpm). As flat 0-based indexes into the 256-value line: **0, 1, 16, 17, 32, 33, 48, 49, 64, 65, 80, 81.**

Leave the 80–100 kPa rows rich (tip-in cover while accel enrichment is unconfigured).

## 3. Post-migration parameter repair (ONLY if you updated 4.1.6 → 4.1.7 with a custom map)

Full background: [`../docs/MIGRATION_BUG_4.1.6_to_4.1.7.md`](../docs/MIGRATION_BUG_4.1.6_to_4.1.7.md).

`[Parameters] Param2raw` (41 values in the 4.1.7 layout), 0-based indexes — set:

| idx | value (dec) | value (hex) |
|----|----|----|
| 9  | 80 | 0x50 |
| 15 | 90 | 0x5A |
| 28–40 | 20, 15, 50, 26, 10, 20, 15, 18, 40, 22, 15, 20, 65 | 0x14, 0x0F, 0x32, 0x1A, 0x0A, 0x14, 0x0F, 0x12, 0x28, 0x16, 0x0F, 0x14, 0x41 |

(These are the stock 4.1.7 M103 values for those slots; idx9/idx15 only if your read-out still shows the old defaults 100/0 — if you customized them, keep yours.) Formatting note: the app writes values ≥ 0x80 sign-extended as `0xFFFFFFxx` — it parses plain `0xXX` fine, but match the app's style if you edit by hand.

## 4. Idle target (installer preference, optional)

`[Tables] IdleRpm` warm cells (~60–100 °C range of the CLT axis): `0x4A` (740 rpm) → `0x50` (800 rpm). Raw × 10 = rpm.

## Verified result on this install

Warm neutral idle ~780–820 rpm (σ 21–34), Drive idle ~675–690, closed loop live everywhere at idle/cruise with single-digit corrections, adaptation banking normally, no stall tendency over multiple logged sessions. Your numbers will differ — verify with the logger, not by ear.
