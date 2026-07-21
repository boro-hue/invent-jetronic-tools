# Flash hygiene — safe ECU map & firmware work

Working habits for flashing maps and updating firmware on the Invent Jetronic
standalone ECU, distilled from a real project: a classic Mercedes M103
converted from KE-Jetronic. Every rule below was paid for on a real car —
including a firmware-update settings-migration bug (4.1.6 → 4.1.7_update1)
that eventually stalled the engine mid-drive. Nothing here is theoretical.

> **Unofficial.** This repository is not affiliated with or endorsed by the
> vendor (Invent). It documents reverse-engineering done by the repo owner for
> interoperability with their own hardware (ECU "Jetronic_XXXX"). The migration
> bug below is described factually so it can be reproduced and fixed — any
> settings-migration code can have this class of bug.
>
> ⚠ **Safety.** Modifying ECU maps, parameters, or firmware can stall the
> engine, damage the engine or catalytic converter, or leave you stranded.
> Verify every change stationary before driving. Use at your own risk.
>
> **Vendor files.** Get the Commander app and firmware packages from the
> vendor. Do not redistribute them — this repo never will.

## The rules at a glance

1. Number every revision — never overwrite.
2. Write → read back → byte-compare **before** starting the engine.
3. One change per flash.
4. Pre-register pass/fail criteria before each change.
5. Logs are the referee, not seat-of-pants.
6. Firmware updates migrate settings — never trust the migration.
7. Keep a living notes file.
8. Verify stationary; know your rollback path.

---

### 1. Number every revision — never overwrite

You can only roll back to a file that still exists.

- Save each edit as a **new** file: `r1`, `r2`, `r3`… plus date and a one-word
  purpose, e.g. `mycar_r2_idletarget_20072026.imf`.
- Keep the Commander app's `Maps/<device>/history/` folder intact — it
  automatically snapshots every read (`..._read_all.imf`) and write
  (`..._write_all.imf`).

*From this project:* the map that fixed the stall (r3) was built directly on a
history read-back taken minutes earlier. Without that snapshot there would
have been no trusted base to build on.

### 2. Write → read back → byte-compare before starting the engine

The only map that matters is the one the ECU actually holds. A read-back costs
about 30 seconds; a corrupted flash discovered at 3000 rpm costs more.

- Flash → read the ECU back → diff the read-back against the file you intended
  to write (see [byte-diff how-to](#byte-diff-in-30-seconds) below).
- Expect value-exact equality. Investigate every difference before key-on.

*From this project:* read-backs proved each fix flash (r3, r4) was value-exact
before the engine ran — and, earlier, proved that re-flashing the same map
through the 4.1.7 app reproduced the same wrong parameter bytes. That one
comparison separated "bad flash" from "migration bug" instantly.

### 3. One change per flash

Change two things at once and you will never know which one did what.
Attribution is the whole product of a test.

*From this project:* revision r4 changed exactly two bytes (Param2 idx9
100→80, idx15 0→90) to test whether they caused some suspicious fuel-trim
resets. Result: 11 resets per session vs 12 before — unchanged. Those two
bytes were cleanly ruled out in a single log. (The resets later turned out to
be the ECU's normal adaptation banking, visible in the app under
*Lambda control → Adaptation → View*.) With a two-byte change, one log settled
it; with a ten-change flash, nothing would have.

### 4. Pre-register pass/fail criteria before each change

Decide what "worked" means *before* you flash, or you will grade your own
homework afterwards.

- Write down concrete numbers: e.g. "neutral idle σ < 30 rpm, corrections
  settle in the predicted band, no correction wipes."
- Pre-register the expected *side effects* too, and what you will do about
  them.

*From this project:* the r2 flash had three pre-registered criteria (idle
σ < 30, corrections within the predicted +3…+8 band, AFR target pinned at
14.7). It measured σ 25.5 — pass. The plan also predicted drive idle would
drop and said "report, don't re-richen." When it dropped, that line prevented
a panic edit that would have recreated the original oscillation.

### 5. Logs are the referee, not seat-of-pants

Human rpm-perception is real data, but it is uncalibrated in both directions.

*From this project, both failure modes:*

- **Felt worse, was better.** After r2 the driver reported "rpm now not
  stable, idle a bit rough." The log said neutral idle σ went 92.5 → 25.5
  (~4× steadier) and the worst dip went 559 → 807 rpm. The old "smooth" idle
  had been bought with a rich mixture the catalyst can't tolerate.
- **Felt fine, was broken.** The 4.1.7 migration regression was nearly
  invisible at a standstill — base fuel delivery within ±1 % of before. The
  log showed closed-loop trim being wiped to zero ~30 times per session
  (vs once on 4.1.6, triggered by ~2 kPa MAP wiggles) and a slowly growing
  idle oscillation in drive. Twenty minutes into a drive, that oscillation
  stalled the engine.

Log stationary after every flash. Log while driving when you drive.

### 6. Firmware updates migrate settings — never trust the migration

Your map may be byte-identical after an update and the car can still behave
completely differently, because the *parameter blocks* around it were
translated by migration code.

*From this project (fw 4.1.6 → 4.1.7_update1):* an installer-settings block
(Param2) changed layout between versions — 43 bytes → 41 — and the updater
migrated the old values **positionally**. Old bytes landed in re-purposed
slots: idx30 ended up 0 where the new firmware expects 50 (a transient
threshold — 0 means "react to everything"), and two values arrived as 140/180
but were clamped to 30 by the new valid range (idx34, idx37). Effects:
hyperactive transient enrichment, fuel trim repeatedly wiped to zero, lean
idle bias, a growing oscillation in drive — and eventually a real stall.
Re-flashing the old map through the new app did **not** fix it; the converter
produced the same values. The cure was transplanting the 13-byte parameter
tail from a stock 4.1.7 calibration (that tail is near-universal — identical
in 8 of 10 calibrations shipped with the 4.1.7 app, and idx30 is never 0 in
any of them).

Procedure for any firmware update:

1. Read the ECU out **before** updating. Keep the file.
2. Update firmware.
3. Read the ECU out **after**. Diff the two.
4. Understand every changed byte — "the updater probably knows best" is not
   an explanation.
5. Re-verify behavior with a short stationary log before driving.

### 7. Keep a living notes file

Three flashes later you will not remember why r2 exists. Your notes are the
only institutional memory a one-person project has.

- One entry per flash: **what** changed (file, exact cells/bytes), **when**
  (timestamp), **why** (hypothesis), and the verdict once logged.
- Append; don't rewrite history. If a tool or sync process can overwrite the
  file, keep dated addendum files. (This project's main notes file was
  clobbered three times by concurrent-session syncs; the addendum pattern
  saved the record.)

### 8. Verify stationary; know your rollback path

- After any flash: idle it, log it, blip it, check the numbers — **before**
  the first drive. First drives happen with the logger running.
- Know, at all times, the exact pair that last worked: **map revision +
  firmware version**. That pair is your rollback unit — a map verified on
  4.1.6 is *unverified* on 4.1.7, which is exactly how this project's stall
  story started.
- Don't drive on a partially verified state. This project ran a strict
  "DO NOT DRIVE until the verification cycle passes" flag between flash and
  sign-off, after learning the alternative the hard way.

---

## Byte-diff in 30 seconds

`.imf` calibration files are plain text (INI style): `Name=v1,v2,v3,…` lines,
one per table or parameter block. Any diff tool works:

```text
# VS Code:  right-click file 1 → "Select for Compare", file 2 → "Compare with Selected"

# git (works on any two files, no repo needed; word-diff shows changed cells):
git diff --no-index --word-diff intended_r3.imf readback.imf

# Windows built-in:
fc /N intended_r3.imf readback.imf

# Linux / macOS:
diff intended_r3.imf readback.imf
```

Practical tips:

- Use a **word-level** diff mode when you can. Table lines are hundreds of
  values long; a line-level diff only tells you *which table* changed, a
  word-level diff shows *which cells*.
- On parameter lines, also compare the **value count**. A different count
  means the layout changed between firmware versions — with positional
  migration, treat every byte at and after the change point as suspect.
- Known false alarm: the app may rewrite a value in a different but
  numerically identical token format when writing to the ECU. Compare the
  numbers, not the text formatting, before declaring corruption.

---

*Modifying ECU data can stall or damage an engine. Verify stationary, log
everything, and drive only on a verified map + firmware pair — at your own
risk.*
