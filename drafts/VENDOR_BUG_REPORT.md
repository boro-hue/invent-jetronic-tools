# Draft — bug report to Invent (email / support forum)

> Template kept in `drafts/` for reuse. Fill the [bracketed] fields before sending.
> Public repo note: this draft contains no personal data; add your own contact details only
> in the copy you actually send.

---

**Subject:** Firmware update 4.1.6 → 4.1.7_update1: Param2 settings migrated positionally — corrupted transient-fuel parameters, engine stall

Hello Invent team,

first of all, thank you for the Jetronic ECU — it has been working well on my engine
(M103 3.0 inline-6, fuel + idle control only, stock ignition module retained). I am
reporting a firmware-update issue that I believe affects anyone updating with a saved map,
and that in my case led to one engine stall. I hope the details below make it easy to
reproduce and fix.

**Summary.** Updating ECU firmware from 4.1.6 to 4.1.7_update1 migrates the `Param2`
settings block positionally (byte-by-byte), although the block changed from 43 to 41 bytes
and its layout changed. The old tail bytes (idx28–40, 0-based) therefore land in re-purposed
slots. On my unit, after the update: idx30 = 0 where the stock 4.1.7 value is 50 (a
transient-fuel threshold — 0 makes the transient logic trigger on ~2 kPa MAP noise), and
idx34/idx37 carried old-layout values 140/180 that were clamped to 30. The fuel/lambda
tables themselves migrated correctly (base fuel delivery within ±1 % at matched cells).

**Affected versions.** Firmware 4.1.7_update1 updater, upgrading from 4.1.6 with an existing
map. Re-flashing the same map through InventCommander 4.1.7_update1 does not repair it — the
app's converter produces the identical bytes (verified write == readback), so users cannot
fix it from the UI.

**Steps to reproduce.**
1. ECU on firmware 4.1.6 with any user-saved map (mine: stock M103 calibration with small
   VE/lambda-table edits).
2. Update firmware to 4.1.7_update1.
3. Read the ECU back and compare `Param2raw` idx28–40 with the shipped stock 4.1.7
   calibration → mismatch, including idx30 = 0 (never 0 in any of the 10 shipped 4.1.7
   calibrations; the tail block is identical in 8 of 10).
4. Log the warm engine: `AccelTotalCorr` becomes nonzero in 84 % of drive samples
   (vs ~29 % before the update) and `LambdaCorrFast` is reset to 0 on ~2 kPa MAP transients
   about 30 times per log (vs 1 before), so closed-loop trim never accumulates.

**Impact.** Jumpy lean-biased idle, ~5 % rich in drive, growing rpm oscillation at a stop in
Drive — and after ~20 minutes of driving, one documented engine stall at a stop in Drive
(rpm dropped below 500, engine died). I consider this safety-relevant for cars in traffic.

**Workaround verified on my unit.** Replacing `Param2raw` idx28–40 in my readout with the
stock 4.1.7 values (plus updating the stale old defaults idx9 100→80, idx15 0→90) and
flashing that restored normal behavior: transient correction quiet at steady state, lambda
trim accumulates and holds, no oscillation growth, no further stalls — the steadiest idle I
have logged on this installation.

**Suggested fix.** Migrate `Param2` semantically (per-parameter mapping between layouts), or
where a parameter has no equivalent, reset it to the new version's default rather than
carrying the old byte positionally. A release-note warning for 4.1.7 users who updated from
4.1.6 ("verify/re-save your settings after updating") would help existing installations, and
the InventCommander converter could detect and repair old-layout values on load.

**Evidence available on request.** Before/after ECU readouts (.imf), byte-level diff of
`Param2`, timestamped CSV logs (~20 Hz) of the broken and repaired behavior, and a full
timeline. I am happy to test a fixed updater or beta build on my unit, and to share my
write-up before publication if you would like to comment.

Device: Jetronic_XXXX (full serial available privately). Firmware reported: 4.1.7.
App: InventCommander 4.1.7_update1.

Best regards,
[your name]
[your email / forum handle]
