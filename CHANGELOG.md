# Changelog

## 2026-08-06 — New tool: `jetronic_live.py` (live tuning copilot)

Streams telemetry while driving, attributes lambda fast-correction to VE cells in real
time (steadiness / warm / closed-loop / cell-proximity gates), and builds a damped,
check-summed `.imf` proposal on demand or automatically at engine-off. Build-only by
design — it never writes to the ECU; flashing stays a stopped-car operation in
InventCommander, verified via Read All + md5. Auto-reconnects across USB dropouts and
writes its raw CSV line-buffered so a crash cannot lose a log. Validated by replaying a
25-minute drive log: proposals matched a hand-derived revision cell-for-cell in direction.
See the "Live tuning copilot" section in the README.

## 2026-08-06 — Correction: `Param1[7]` is cranking "Cold enrichment", NOT injector flow

Earlier revisions of these docs decoded `.imf` `Param1raw` byte 7 as "injector flow in
cc/min" (150 in the M103 base cal) and recommended setting it to the real injector size as
the preferred rich-running fix. **Flash testing on the documented car proved that decode
wrong:**

- **`Param1[7]` is the "Cold enrichment" field** on Commander's *Injection → Engine start*
  tab — a **cranking-only** enrichment (stock M103 value 150). The UI field tracked the
  byte exactly across two flashes (150 → 190).
- It does **not** scale running fuel: the running mixture was unchanged by `Param1[7]`,
  while VE-table changes moved fueling 1:1. **Running fuel follows the VE table alone —
  the ECU has no injector-flow field**, so the global VE rescale is the only injector-size
  correction.
- Two more anchors from the same tab were verified: **`Param1[23]` = "Idle position"**
  (the engine-start idle-valve preset, also the valve's park position at low runlevels)
  and **`Param1[24]` = "Idle pos cold add"**.

Corrected in `docs/M103_CONVERSION_NOTES.md` (trap section rewritten, FuelConsumed
inference retracted, verified `Param1raw` anchor table added), `docs/VEHICLE_SETUP.md`,
`samples/M103_190cc_fw417_reference_patch.md`, `samples/README.md`, `README.md`, and
`drafts/FORUM_POST.md`.
