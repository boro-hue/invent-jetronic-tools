# Changelog

## 2026-08-06 — Session-report exports + AI backup, `CLAUDE.md`, migration recommendation

- `jetronic_live.py` now **exports a session report on every build**
  (`exports/jlive_report_*.json` + `.md`): per-cell stats with spread, the sample
  rejection funnel, warning events, health flags, and the proposal. The report is a
  self-contained hand-off artifact for deeper (human or AI) analysis when the internal
  math is not enough.
- New **AI backup option** (advisory-only, never applied automatically): `--ai on-fail`
  consults an AI when health flags fire (low acceptance, scattered samples, high-spread
  cells, neighbour sign conflicts), `--ai always` after every build. Default backend is
  the Claude Code CLI (`claude -p`); any model via `--ai-cmd` (report on stdin). Advice
  is saved to `exports/ai_advice_*.md`.
- New **`CLAUDE.md`**: full project context for AI assistants — verified decode anchors,
  `.imf` format rules, discovered firmware behaviours (idle anchor, run-level demotion
  trapdoor, adaptation resets, correction-speed asymmetry), and hard safety rules.
- README: the 4.1.6 → 4.1.7 section now carries an explicit recommendation — **do not
  migrate a 4.1.6 map; start from the stock 4.1.7 preset** and re-enter settings. The
  documented car's migration was effectively brute-forced back to health and still
  produced weeks of subtle after-effects.
- New **worked example** in `samples/`: a real 25-minute drive log
  (`example_drive_25min_rb15.csv`), the session report exported from it, and
  `LIVE_TUNING_EXAMPLE.md` — the full walkthrough including the blind cross-check
  against a hand-built revision and the health flags catching a real hardware misfire
  from correction spread alone.

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
