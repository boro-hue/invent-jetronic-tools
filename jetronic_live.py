#!/usr/bin/env python3
"""
jetronic_live.py - live tuning copilot for the Invent Jetronic ECU.

Streams telemetry while you drive, attributes lambda fast-correction to VE
cells in real time, and has the next map revision already built (and
check-summed) the moment you pull over. It NEVER writes to the ECU - flashing
stays a stopped-car operation done in InventCommander, followed by Read All
and an md5 compare of the read-back against the proposal file.

Why build-only, on purpose:
  * a malformed write to an ECU that is metering fuel at speed fails lean;
  * every flash resets the ECU's lambda adaptation, so flashing more often
    than ~15-20 min intervals makes convergence SLOWER, not faster.
  The copilot therefore compresses the analysis step to zero and leaves the
  flash itself deliberate, verified, and stationary.

Modes:
  --live            stream from the ECU (default COM3), accumulate, propose.
                    Keys:  b = build proposal now and keep logging
                           q = build proposal and quit
                    Ctrl+C = build proposal and quit
                    Engine off >8 s = auto-build, keep listening (restart-safe).
  --replay CSV      run the identical pipeline over a recorded jlog CSV
                    (validation / offline analysis, no hardware needed).

Every build also EXPORTS a session report (exports/jlive_report_*.json + .md
next to the proposals): per-cell statistics, the rejection funnel (why samples
were discarded), warning events, health flags, and the proposal itself. The
report is self-contained so a human OR an AI can audit the run - it is the
backup path for when the internal math is not enough.

AI backup (optional, advisory-only):
  --ai on-fail      when health flags fire (low acceptance, scattered samples,
                    high-spread cells, sign conflicts), send the report to an
                    AI for diagnosis. Default backend is the Claude Code CLI
                    (`claude -p`, uses your existing login).
  --ai always       send the report after every build.
  --ai-cmd CMD      use any other AI/backend: CMD receives the markdown report
                    on stdin and its stdout is saved + printed
                    (e.g. --ai-cmd "ollama run llama3").
  The AI output is advice saved to exports/ai_advice_*.md. It never edits
  maps and nothing it says is applied automatically.

Options:
  --maps-dir DIR    where proposal .imf files are written, and whose history/
                    subfolder (InventCommander's read_all/write_all audit
                    trail) supplies the default base map. Default: env
                    JETRONIC_MAPS_DIR, else the current directory. Point this
                    at Commander's Maps\\<EcuName>\\ folder.
  --base FILE       base .imf to patch (default: newest *_read_all.imf in
                    <maps-dir>/history = what is actually in the ECU)
  --min-n N         samples required before a cell may be corrected (default 200)
  --damp F          fraction of measured correction to apply (default 0.5)
  --max-step F      per-build clamp on any cell change (default 0.08 = 8%)
  --min-corr F      dead-band: |mean corrF| below this is left alone (default 3.0)

The proposal only ever touches cells the drive actually visited with steady,
warm, closed-loop samples. Everything else is byte-identical to the base map.
Validated by replaying a 25-minute drive log: its proposals matched an
independently hand-derived revision cell-for-cell in direction.
"""

import argparse
import datetime
import glob
import hashlib
import json
import math
import os
import subprocess
import sys
import time

from jetronic_logger import (PORT_DEFAULT, BAUD_DEFAULT, LOG_DIR, CHANNELS,
                             POLL_LIVE, _decode, open_port, read_frame,
                             query_ascii, CMD_VER, CMD_NAME)

# ---- sample-quality gates (tuned on real M103 drive logs, 2026-08-06) ----
CLT_MIN = 75          # degC: warm engine only
TARGET_AFR_MIN = 14.0  # closed-loop stoich region only (skip full-load enrichment)
INJ_MIN_MS = 0.8      # skip fuel-cut / decel
STEADY_WIN = 12       # samples (~0.6 s) the operating point must hold still
STEADY_RPM = 150      # max rpm spread inside the window
STEADY_MAP = 6        # max MAP spread (kPa)
STEADY_TPS = 1.5      # max TPS spread (%)
PROX_RPM = 0.30       # accept sample only within 30% of the gap to the next rpm breakpoint
PROX_MAP = 3.0        # ...and within 3 kPa of the row centre

HIGH_SPREAD_STD = 6.0  # per-cell corrF std above this = suspect (misfire/sensor noise)

AI_PROMPT = """You are auditing one session of jetronic_live, a build-only live tuning
copilot for an Invent Jetronic ECU (M103-class engine, narrowband lambda, fuel+idle
only). Attached is the session report: per-VE-cell lambda-correction statistics, the
sample rejection funnel, warning events, health flags, and the proposal (if any).

Task: diagnose whether the internal math produced a trustworthy result, and if not, why.
Consider: contamination by misfire or sensor noise (high per-cell spread), transient
leakage, cells with conflicting neighbours, low acceptance and which gate caused it,
whether the proposal direction is consistent with the warning events.

Rules: advisory only. Never recommend writing to the ECU while the vehicle is moving.
Never invent cell values; reason only from the data given. If the data is insufficient,
say what to record next instead of guessing. Answer in under 400 words: 1) verdict on
the proposal (flash / don't flash / flash with caveats), 2) diagnosis, 3) next actions.
"""


# ---------------------------------------------------------------- imf handling
def parse_imf(path):
    raw = open(path, "rb").read()
    lines = raw.decode("ascii").split("\r\n")
    kv = {}
    for ln in lines:
        if "=" in ln:
            k, v = ln.split("=", 1)
            kv[k] = v
    return raw, lines, kv


def table(kv, key):
    return [int(t, 16) & 0xFF for t in kv[key].split(",") if t]


def newest_readall(history_dir):
    hits = glob.glob(os.path.join(history_dir, "*_read_all.imf"))
    if not hits:
        sys.exit(f"No read_all files in {history_dir} - pass --base (or --maps-dir "
                 f"pointing at Commander's Maps\\<EcuName>\\ folder).")
    return max(hits, key=os.path.getmtime)


# ---------------------------------------------------------------- accumulator
class CellStats:
    """Per-VE-cell accumulation of lambda fast correction."""

    def __init__(self, base_imf):
        self.base_path = base_imf
        self.raw, self.lines, self.kv = parse_imf(base_imf)
        self.base_md5 = hashlib.md5(self.raw).hexdigest()[:12]
        self.ve = table(self.kv, "InjVe3d")
        grid = table(self.kv, "GridRpm16")
        self.rpm_bp = [g * 50 for g in grid]            # 16 rpm breakpoints
        self.map_bp = [20 + 10 * r for r in range(16)]  # 16 MAP rows
        self.n = [0] * 256
        self.s = [0.0] * 256
        self.s2 = [0.0] * 256   # sum of squares, for per-cell spread
        self.window = []        # ring buffer for the steadiness gate
        self.accepted = 0
        self.seen = 0
        self.rejects = {}       # gate name -> count

    def _rej(self, why):
        self.rejects[why] = self.rejects.get(why, 0) + 1
        return None

    def _nearest(self, bps, v):
        c = min(range(len(bps)), key=lambda i: abs(bps[i] - v))
        return c, abs(bps[c] - v)

    def feed(self, smp):
        """smp: dict of decoded channels. Returns cell idx if accepted else None."""
        self.seen += 1
        self.window.append(smp)
        if len(self.window) > STEADY_WIN:
            self.window.pop(0)
        if smp["Runlevel"] != 3:
            return self._rej("not_running")
        if smp["Clt"] < CLT_MIN:
            return self._rej("engine_cold")
        if smp["TargetAfr"] < TARGET_AFR_MIN:
            return self._rej("open_loop_enrich")
        if smp["InjTime"] < INJ_MIN_MS:
            return self._rej("fuel_cut")
        if len(self.window) < STEADY_WIN:
            return self._rej("window_filling")
        rpms = [x["Rpm"] for x in self.window]
        maps = [x["Map"] for x in self.window]
        tpss = [x["Tps"] for x in self.window]
        if (max(rpms) - min(rpms) > STEADY_RPM or max(maps) - min(maps) > STEADY_MAP
                or max(tpss) - min(tpss) > STEADY_TPS):
            return self._rej("transient")
        c, drpm = self._nearest(self.rpm_bp, smp["Rpm"])
        gaps = [abs(self.rpm_bp[i + 1] - self.rpm_bp[i])
                for i in (max(c - 1, 0), min(c, 14))]
        if drpm > PROX_RPM * min(gaps):
            return self._rej("between_rpm_cells")
        r, dmap = self._nearest(self.map_bp, smp["Map"])
        if dmap > PROX_MAP:
            return self._rej("between_map_rows")
        i = r * 16 + c
        v = smp["LambdaCorrFast"]
        self.n[i] += 1
        self.s[i] += v
        self.s2[i] += v * v
        self.accepted += 1
        return i

    def cell_mean(self, i):
        return self.s[i] / self.n[i]

    def cell_std(self, i):
        if self.n[i] < 2:
            return 0.0
        m = self.cell_mean(i)
        return math.sqrt(max(self.s2[i] / self.n[i] - m * m, 0.0))

    def ready_cells(self, min_n, min_corr):
        out = []
        for i in range(256):
            if self.n[i] >= min_n:
                m = self.cell_mean(i)
                if abs(m) >= min_corr:
                    out.append((i, self.n[i], m))
        return out

    def visited_cells(self, min_n=20):
        return [i for i in range(256) if self.n[i] >= min_n]

    def propose(self, min_n, damp, max_step, min_corr, out_path):
        """Build a patched .imf. Returns (path, md5, changes) or (None, None, [])."""
        changes = []
        new = list(self.ve)
        for i, n, m in self.ready_cells(min_n, min_corr):
            factor = 1.0 + damp * m / 100.0
            factor = max(1.0 - max_step, min(1.0 + max_step, factor))
            v = max(1, min(255, round(self.ve[i] * factor)))
            if v != self.ve[i]:
                new[i] = v
                changes.append((i, n, m, self.ve[i], v))
        if not changes:
            return None, None, []
        out = []
        for ln in self.lines:
            if ln.startswith("InjVe3d="):
                ln = "InjVe3d=" + ",".join(f"0x{x:02X}" for x in new)
            out.append(ln)
        blob = "\r\n".join(out).encode("ascii")
        with open(out_path, "wb") as f:
            f.write(blob)
        return out_path, hashlib.md5(blob).hexdigest()[:12], changes

    # ---------------- health assessment (drives the on-fail AI trigger) ----
    def health_flags(self, min_n, min_corr, changes):
        flags = []
        if self.seen >= 2000:
            acc = self.accepted / max(self.seen, 1)
            if acc < 0.05:
                top = sorted(self.rejects.items(), key=lambda kv: -kv[1])[:3]
                flags.append("very_low_acceptance: %.0f%% accepted; top rejects: %s"
                             % (100 * acc, ", ".join(f"{k}={v}" for k, v in top)))
        if self.accepted > 1000 and not any(self.n[i] >= min_n for i in range(256)):
            flags.append("scattered_samples: no cell reached min-n=%d - drive "
                         "steadier or lower --min-n" % min_n)
        noisy = [i for i in self.visited_cells(min_n)
                 if self.cell_std(i) > HIGH_SPREAD_STD]
        if noisy:
            flags.append("high_spread_cells (misfire/sensor noise?): " +
                         ", ".join(f"r{i//16:02d}c{i%16:02d} std={self.cell_std(i):.1f}"
                                   for i in noisy[:8]))
        # sign conflicts between adjacent converged cells
        conf = []
        for i, n, m in self.ready_cells(min_n, min_corr):
            r, c = divmod(i, 16)
            for j in (i - 1, i + 1, i - 16, i + 16):
                if 0 <= j < 256 and self.n[j] >= min_n:
                    mj = self.cell_mean(j)
                    if abs(mj) >= min_corr and m * mj < 0 and abs(m - mj) > 10:
                        conf.append(f"r{r:02d}c{c:02d}({m:+.0f}) vs "
                                    f"r{j//16:02d}c{j%16:02d}({mj:+.0f})")
        if conf:
            flags.append("sign_conflict_neighbours: " + "; ".join(conf[:5]))
        return flags


# ---------------------------------------------------------------- exports
def export_report(cs, args, mode, duration_s, proposal, changes, flags, events,
                  export_dir, stamp, build_no):
    os.makedirs(export_dir, exist_ok=True)
    base = os.path.join(export_dir, f"jlive_report_{stamp}_{build_no:02d}")
    cells = []
    for i in cs.visited_cells(20):
        r, c = divmod(i, 16)
        cells.append(dict(r=r, c=c, rpm=cs.rpm_bp[c], map=cs.map_bp[r],
                          n=cs.n[i], mean=round(cs.cell_mean(i), 2),
                          std=round(cs.cell_std(i), 2),
                          ve_pct=round(cs.ve[i] * 0.5, 1)))
    doc = dict(
        tool="jetronic_live", mode=mode, stamp=stamp, build=build_no,
        duration_s=round(duration_s, 1),
        base_map=os.path.basename(cs.base_path), base_md5=cs.base_md5,
        settings=dict(min_n=args.min_n, damp=args.damp, max_step=args.max_step,
                      min_corr=args.min_corr),
        samples=dict(seen=cs.seen, accepted=cs.accepted,
                     acceptance_pct=round(100 * cs.accepted / max(cs.seen, 1), 1),
                     rejects=dict(sorted(cs.rejects.items(), key=lambda kv: -kv[1]))),
        cells=sorted(cells, key=lambda d: -d["n"]),
        proposal=(dict(path=os.path.basename(proposal[0]), md5=proposal[1],
                       changes=[dict(r=i // 16, c=i % 16, rpm=cs.rpm_bp[i % 16],
                                     map=cs.map_bp[i // 16], n=n,
                                     mean=round(m, 2), ve_old=old, ve_new=new)
                                for i, n, m, old, new in changes])
                  if proposal and proposal[0] else None),
        health_flags=flags,
        events=events[-200:],
    )
    with open(base + ".json", "w") as f:
        json.dump(doc, f, indent=1)
    # markdown twin: same content, prompt-friendly
    md = [f"# jetronic_live session report ({mode}, {stamp}, build {build_no})",
          f"base map: {doc['base_map']} (md5 {cs.base_md5}) | duration {doc['duration_s']} s",
          f"samples: {cs.seen} seen, {cs.accepted} accepted "
          f"({doc['samples']['acceptance_pct']}%)",
          "rejection funnel: " + ", ".join(f"{k}={v}" for k, v in
                                           doc["samples"]["rejects"].items()),
          "", "## visited cells (n>=20)",
          "| cell | rpm | MAP | n | mean corrF | std | VE% |", "|--|--|--|--|--|--|--|"]
    for d in doc["cells"]:
        md.append(f"| r{d['r']:02d}c{d['c']:02d} | {d['rpm']} | {d['map']} | {d['n']} "
                  f"| {d['mean']:+.1f} | {d['std']:.1f} | {d['ve_pct']} |")
    md.append("")
    if doc["proposal"]:
        md.append(f"## proposal: {doc['proposal']['path']} (md5 {doc['proposal']['md5']})")
        for ch in doc["proposal"]["changes"]:
            md.append(f"- r{ch['r']:02d}c{ch['c']:02d} rpm{ch['rpm']} MAP{ch['map']}: "
                      f"VE {ch['ve_old']*0.5:.1f}% -> {ch['ve_new']*0.5:.1f}% "
                      f"(corr {ch['mean']:+.1f}, n={ch['n']})")
    else:
        md.append("## proposal: NONE (all converged cells within dead-band, or no cell converged)")
    md.append("")
    md.append("## health flags")
    md += [f"- {f}" for f in flags] if flags else ["- none"]
    if events:
        md.append("")
        md.append("## events (last %d)" % min(len(events), 200))
        md += [f"- t={e['t']:.0f}s {e['kind']} (rpm {e.get('rpm','?')})"
               for e in events[-200:]]
    with open(base + ".md", "w") as f:
        f.write("\n".join(md) + "\n")
    print(f"report: {base}.md (+ .json)")
    return base + ".md"


# ---------------------------------------------------------------- AI backup
def run_ai(report_md_path, args, flags, export_dir, stamp, build_no):
    if args.ai == "off":
        return
    if args.ai == "on-fail" and not flags:
        return
    report = open(report_md_path).read()
    payload = AI_PROMPT + "\n\n---- SESSION REPORT ----\n" + report
    cmd = args.ai_cmd or "claude -p"   # claude -p reads the prompt from stdin
    print(f"\n[AI backup] consulting `{cmd}` ...")
    try:
        res = subprocess.run(cmd, shell=True, input=payload,
                             capture_output=True, text=True, timeout=180)
        advice = (res.stdout or "").strip()
        if not advice:
            print(f"  AI backend gave no output (exit {res.returncode}; "
                  f"stderr: {res.stderr.strip()[:300]}).")
            print(f"  No CLI? The report works standalone - paste it into any AI chat")
            print(f"  (Claude Code CLI install: npm i -g @anthropic-ai/claude-code).")
            print(f"  Report: {report_md_path}")
            return
    except subprocess.TimeoutExpired:
        print(f"  AI timed out. Report saved for manual analysis: {report_md_path}")
        return
    out = os.path.join(export_dir, f"ai_advice_{stamp}_{build_no:02d}.md")
    with open(out, "w", encoding="utf-8") as f:
        f.write(advice + "\n")
    print("-" * 74)
    print(advice)
    print("-" * 74)
    print(f"[AI backup] advice saved: {out}  (ADVISORY ONLY - nothing was applied)")


def print_proposal(cs, path, md5, changes):
    print("\n" + "=" * 74)
    if not changes:
        print("NO CHANGES PROPOSED - every converged cell is within the dead-band.")
        print("=" * 74)
        return
    print(f"PROPOSAL: {path}")
    print(f"md5 {md5}   (base: {os.path.basename(cs.base_path)})")
    print(f"{'cell':>7} {'rpm':>5} {'MAP':>4} {'n':>6} {'corrF':>7} {'VE old':>7} {'VE new':>7}")
    for i, n, m, old, newv in sorted(changes, key=lambda c: -abs(c[2])):
        r, c = divmod(i, 16)
        print(f"  r{r:02d}c{c:02d} {cs.rpm_bp[c]:>5} {cs.map_bp[r]:>4} {n:>6} "
              f"{m:>+7.1f} {old * 0.5:>6.1f}% {newv * 0.5:>6.1f}%")
    print("-" * 74)
    print("FLASH WITH THE CAR STOPPED: Commander -> File-Open this file -> write")
    print(f"-> Read All -> verify md5 {md5}. Adaptation resets on flash;")
    print("judge the result after ~5 min of driving, not the first minute.")
    print("=" * 74)


# ---------------------------------------------------------------- live mode
def run_live(args, cs, maps_dir):
    try:
        import msvcrt
    except ImportError:
        msvcrt = None

    os.makedirs(LOG_DIR, exist_ok=True)
    stamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    csv_path = os.path.join(LOG_DIR, f"jlive_{stamp}.csv")
    export_dir = os.path.join(maps_dir, "exports")
    chan_names = [c[0] for c in CHANNELS]
    build_count = 0
    engine_dead_since = None
    events = []
    last_warn = ""
    t0 = time.time()

    def build(tag):
        nonlocal build_count
        build_count += 1
        out = os.path.join(maps_dir, f"rb_auto_{stamp}_{build_count:02d}.imf")
        path, md5, changes = cs.propose(args.min_n, args.damp, args.max_step,
                                        args.min_corr, out)
        print(f"\n[{tag}]")
        print_proposal(cs, path, md5, changes)
        flags = cs.health_flags(args.min_n, args.min_corr, changes)
        report = export_report(cs, args, "live", time.time() - t0, (path, md5),
                               changes, flags, events, export_dir, stamp, build_count)
        if flags:
            print("health flags: " + " | ".join(flags))
        run_ai(report, args, flags, export_dir, stamp, build_count)

    ser = open_port(args.port, args.baud, timeout=0.3)
    print("Handshake:")
    query_ascii(ser, CMD_VER, 0xF1, "firmware")
    query_ascii(ser, CMD_NAME, 0x91, "ecu name")
    print(f"Raw log: {csv_path}")
    print("LIVE. Keys: b=build now, q=build+quit. Ctrl+C=build+quit.\n")

    period = 1.0 / args.hz
    next_t = t0
    # line-buffered so a crash or USB dropout never loses more than one row
    with open(csv_path, "w", buffering=1, newline="") as f:
        f.write("t_s," + ",".join(chan_names) + "\n")
        try:
            while True:
                if msvcrt and msvcrt.kbhit():
                    k = msvcrt.getwch().lower()
                    if k == "b":
                        build("manual build - keep driving")
                    elif k == "q":
                        build("final build")
                        break
                try:
                    ser.reset_input_buffer()
                    ser.write(POLL_LIVE)
                    r = read_frame(ser, 0x01)
                except Exception as e:
                    print(f"\nserial error ({e}) - reconnecting...")
                    events.append(dict(t=time.time() - t0, kind="serial_dropout"))
                    try:
                        ser.close()
                    except Exception:
                        pass
                    time.sleep(2.0)
                    for _k in range(10):
                        try:
                            ser = open_port(args.port, args.baud, timeout=0.3)
                            print("reconnected.")
                            break
                        except SystemExit:
                            raise
                        except Exception:
                            time.sleep(2.0)
                    else:
                        build("final build (link lost)")
                        break
                    continue
                now = time.time()
                if r and len(r[1]) >= 50:
                    d = r[1][:50]
                    smp = {nm: _decode(kd, d, i, mul, add)
                           for (nm, kd, i, mul, add) in CHANNELS}
                    f.write(f"{now - t0:.3f}," +
                            ",".join(f"{smp[nm]:g}" for nm in chan_names) + "\n")
                    cell = cs.feed(smp)
                    # engine-off auto-build (key-off at end of drive)
                    if smp["Rpm"] < 100:
                        if engine_dead_since is None:
                            engine_dead_since = now
                        elif now - engine_dead_since > 8 and cs.accepted > 200:
                            build("engine off - auto build")
                            engine_dead_since = None  # arm again for a restart
                            cs.accepted = 0           # require fresh data for next build
                    else:
                        engine_dead_since = None
                    warn = ""
                    if 100 < smp["Rpm"] < 550 and smp["Tps"] < 2:
                        warn = " DIP!"
                    elif smp["LambdaCorrFast"] <= -15:
                        warn = " RICH"
                    elif smp["LambdaCorrFast"] >= 15:
                        warn = " LEAN"
                    if warn and warn != last_warn:
                        events.append(dict(t=now - t0, kind=warn.strip(" !"),
                                           rpm=round(smp["Rpm"])))
                    last_warn = warn
                    ready = len(cs.ready_cells(args.min_n, args.min_corr))
                    tag = f"r{cell // 16:02d}c{cell % 16:02d}" if cell is not None else "  --  "
                    print(f"\r{now - t0:6.0f}s CLT{smp['Clt']:3.0f} rpm{smp['Rpm']:5.0f} "
                          f"MAP{smp['Map']:4.0f} {tag} corr{smp['LambdaCorrFast']:+6.1f} "
                          f"ready:{ready:3d}{warn}   ", end="")
                next_t += period
                dt = next_t - time.time()
                if dt > 0:
                    time.sleep(dt)
        except KeyboardInterrupt:
            build("final build (Ctrl+C)")
    try:
        ser.close()
    except Exception:
        pass
    print(f"\nraw log kept: {csv_path}")


# ---------------------------------------------------------------- replay mode
def run_replay(args, cs, maps_dir):
    import csv as csvmod
    rows = list(csvmod.DictReader(open(args.replay)))
    if not rows:
        sys.exit("empty CSV")
    for row in rows:
        smp = {nm: float(row[nm]) for (nm, *_x) in CHANNELS if nm in row}
        cs.feed(smp)
    print(f"replayed {cs.seen} rows, accepted {cs.accepted} "
          f"({100.0 * cs.accepted / max(cs.seen, 1):.0f}%) from {args.replay}")
    stamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    out = os.path.join(maps_dir, f"rb_auto_replay_{stamp}.imf")
    path, md5, changes = cs.propose(args.min_n, args.damp, args.max_step,
                                    args.min_corr, out)
    print_proposal(cs, path, md5, changes)
    flags = cs.health_flags(args.min_n, args.min_corr, changes)
    dur = float(rows[-1]["t_s"]) if "t_s" in rows[-1] else 0.0
    export_dir = os.path.join(maps_dir, "exports")
    report = export_report(cs, args, "replay", dur, (path, md5), changes, flags,
                           [], export_dir, stamp, 1)
    if flags:
        print("health flags: " + " | ".join(flags))
    run_ai(report, args, flags, export_dir, stamp, 1)


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--port", default=PORT_DEFAULT)
    ap.add_argument("--baud", type=int, default=BAUD_DEFAULT)
    ap.add_argument("--hz", type=float, default=20.0)
    ap.add_argument("--maps-dir", default=os.environ.get("JETRONIC_MAPS_DIR", "."),
                    help="Commander Maps\\<EcuName>\\ folder (proposals + exports/ "
                         "written here; history/ inside it supplies the base map)")
    ap.add_argument("--base", default=None, help="base .imf (default: newest ECU read_all)")
    ap.add_argument("--min-n", type=int, default=200)
    ap.add_argument("--damp", type=float, default=0.5)
    ap.add_argument("--max-step", type=float, default=0.08)
    ap.add_argument("--min-corr", type=float, default=3.0)
    ap.add_argument("--ai", choices=["off", "on-fail", "always"], default="off",
                    help="AI backup: consult an AI with the session report "
                         "(advisory only, never applied automatically)")
    ap.add_argument("--ai-cmd", default=None,
                    help="AI backend command reading the report on stdin "
                         "(default backend: Claude Code CLI `claude -p`)")
    mode = ap.add_mutually_exclusive_group(required=True)
    mode.add_argument("--live", action="store_true")
    mode.add_argument("--replay", metavar="CSV")
    args = ap.parse_args()

    maps_dir = os.path.abspath(args.maps_dir)
    base = args.base or newest_readall(os.path.join(maps_dir, "history"))
    print(f"base map: {base}")
    cs = CellStats(base)
    if args.replay:
        run_replay(args, cs, maps_dir)
    else:
        run_live(args, cs, maps_dir)


if __name__ == "__main__":
    main()
