#!/usr/bin/env python3
"""
align_decode.py  --  finalize the full 50-byte channel map with ZERO guessing.

Input: a USBPcap capture (.pcap) taken WHILE InventCommander was logging its own CSV
       (USBPcap sniffs below the COM port, so both run at once).
Method:
  1. Parse pcap -> timestamped 0x01 live frames (50 data bytes each).
  2. Parse Commander CSV (its own Time column + named channels).
  3. Time-align the two streams by cross-correlating a known channel (Map = d3).
  4. For every CSV channel, linear-regress it against every candidate byte decode
     (8-bit, 16-bit LE/BE, with scale+bias). Best R^2 wins -> exact offset+scale.
  5. Emit a ready-to-paste CONFIRMED{} map + an R^2 confidence report.

Usage (from the repo root):
  py re-tools\\align_decode.py captures\\combo.pcap "<path to Commander CSV log>"
"""
import sys, os, csv, struct, statistics

SYNC = bytes([0x55, 0x00, 0xAA, 0x00])

# ---------- pcap -> timestamped live frames ----------
def parse_pcap_live(path):
    with open(path, "rb") as f:
        data = f.read()
    assert struct.unpack_from("<I", data, 0)[0] == 0xA1B2C3D4, "not a little-endian pcap"
    off = 24
    buf = bytearray()
    tstamp = []  # timestamp per byte in buf
    while off + 16 <= len(data):
        ts_s, ts_us, incl, orig = struct.unpack_from("<IIII", data, off)
        off += 16
        rec = data[off:off+incl]; off += incl
        if len(rec) < 27:
            continue
        headerLen, = struct.unpack_from("<H", rec, 0)
        endpoint = rec[21]; transfer = rec[22]
        dataLength, = struct.unpack_from("<I", rec, 23)
        if transfer != 3 or not (endpoint & 0x80) or dataLength <= 2:
            continue
        payload = rec[headerLen+2:headerLen+dataLength]  # strip 2-byte FTDI status
        t = ts_s + ts_us/1e6
        buf.extend(payload)
        tstamp.extend([t]*len(payload))
    # frame-split
    frames = []
    i, n = 0, len(buf)
    while i < n-6:
        if buf[i:i+4] == SYNC and buf[i+4] == 0x33:
            body = bytes(buf[i+5:i+5+0x33])
            if len(body) == 0x33 and body[0] == 0x01:
                frames.append((tstamp[i], body[1:51]))  # 50 data bytes
                i += 5+0x33; continue
        i += 1
    return frames

# ---------- csv ----------
def parse_time(s):
    # "HH:MM:SS.mmm"
    hh, mm, ss = s.split(":")
    return int(hh)*3600 + int(mm)*60 + float(ss)

def load_csv(path):
    rows = []
    with open(path, newline="") as f:
        rd = csv.DictReader(f)
        names = rd.fieldnames
        for row in rd:
            try:
                t = parse_time(row["Time"])
            except Exception:
                continue
            rows.append((t, row))
    return names, rows

def pearson(a, b):
    n = len(a)
    if n < 3: return 0.0
    ma, mb = sum(a)/n, sum(b)/n
    va = sum((x-ma)**2 for x in a); vb = sum((x-mb)**2 for x in b)
    if va == 0 or vb == 0: return 0.0
    cov = sum((a[i]-ma)*(b[i]-mb) for i in range(n))
    return cov/((va*vb)**0.5)

def linfit(x, y):
    """return (slope, intercept, r2)"""
    n = len(x)
    mx, my = sum(x)/n, sum(y)/n
    sxx = sum((xi-mx)**2 for xi in x); sxy = sum((x[i]-mx)*(y[i]-my) for i in range(n))
    if sxx == 0: return 0,0,0
    slope = sxy/sxx; inter = my - slope*mx
    ss_tot = sum((yi-my)**2 for yi in y)
    ss_res = sum((y[i]-(slope*x[i]+inter))**2 for i in range(n))
    r2 = 1 - ss_res/ss_tot if ss_tot else 0
    return slope, inter, r2

def main():
    if len(sys.argv) < 3:
        print(__doc__); return
    pcap, csvp = sys.argv[1], sys.argv[2]
    frames = parse_pcap_live(pcap)
    names, rows = load_csv(csvp)
    print(f"live frames: {len(frames)}   csv rows: {len(rows)}")
    if len(frames) < 20 or len(rows) < 20:
        print("need more data (drive a bit during the combined capture)"); return

    ft0 = frames[0][0]
    frt = [t - ft0 for t, _ in frames]          # frame times relative to pcap start
    f_d3 = [d[3] for _, d in frames]            # known Map channel
    ct0 = rows[0][0]
    cst = [t - ct0 for t, _ in rows]            # csv times relative to csv start
    c_map = [float(r["Map"]) for _, r in rows]

    import bisect
    frt_last = frt[-1]

    def overlap_pairs(L):
        """(csv_index, frame_index) for csv rows whose shifted time lands inside the frame span."""
        out = []
        for j, cs in enumerate(cst):
            tt = cs + L
            if tt < 0 or tt > frt_last:
                continue
            k = bisect.bisect_right(frt, tt) - 1
            if k < 0:
                k = 0
            out.append((j, k))
        return out

    def corr_at_lag(L):
        pairs = overlap_pairs(L)
        if len(pairs) < 30:
            return -2.0, 0
        a = [f_d3[k] for _, k in pairs]
        b = [c_map[j] for j, _ in pairs]
        return pearson(a, b), len(pairs)

    # CSV logging started at an unknown offset into the frame stream (and may run past pcap end).
    dur = frt[-1]
    best = (0.0, -2.0)
    L = -dur
    while L <= dur:                              # coarse 0.25 s over full range (both signs)
        r, npair = corr_at_lag(L)
        if r > best[1]:
            best = (L, r)
        L += 0.25
    L0 = best[0]                                 # refine +-0.5 s at 5 ms
    L = L0 - 0.5
    while L <= L0 + 0.5:
        r, npair = corr_at_lag(L)
        if r > best[1]:
            best = (L, r)
        L += 0.005
    bestL, bestr = best
    print(f"coarse alignment (Map): lag {bestL:.2f}s, r={bestr:.3f}")

    # --- fine alignment on the most dynamic channel (Rpm): sharpens fast channels ---
    if "Rpm" in names:
        def pairs_at(L):
            return overlap_pairs(L)
        # find best rpm proxy at coarse lag (any 16-bit pair, either endianness)
        p0 = pairs_at(bestL)
        crpm = [float(rows[j][1]["Rpm"]) for j, _ in p0]
        proxies = []
        for i in range(L-1 if False else len(frames[0][1])-1):
            le = [frames[k][1][i] | (frames[k][1][i+1] << 8) for _, k in p0]
            be = [(frames[k][1][i] << 8) | frames[k][1][i+1] for _, k in p0]
            proxies.append((f"LE{i}", i, "le", abs(pearson(crpm, le))))
            proxies.append((f"BE{i}", i, "be", abs(pearson(crpm, be))))
        proxies.sort(key=lambda x: -x[3])
        _, pi, pend, pr = proxies[0]
        def proxy_series(pairs):
            if pend == "le":
                return [frames[k][1][pi] | (frames[k][1][pi+1] << 8) for _, k in pairs]
            return [(frames[k][1][pi] << 8) | frames[k][1][pi+1] for _, k in pairs]
        # refine lag +-3s at 10ms maximizing |corr(csv Rpm, proxy)|
        fb = (bestL, pr)
        L = bestL - 3.0
        while L <= bestL + 3.0:
            pp = pairs_at(L)
            if len(pp) > 50:
                cr = [float(rows[j][1]["Rpm"]) for j, _ in pp]
                pv = proxy_series(pp)
                rr = abs(pearson(cr, pv))
                if rr > fb[1]:
                    fb = (L, rr)
            L += 0.01
        bestL = fb[0]
        print(f"fine alignment (Rpm proxy d[{pi}] {pend}): lag {bestL:.2f}s, |r|={fb[1]:.3f}")

    pairs = overlap_pairs(bestL)
    print(f"{len(pairs)} overlapping rows used")

    # aligned frame-data restricted to the overlap window
    csv_idx = [j for j, _ in pairs]
    aligned = [frames[k][1] for _, k in pairs]

    # candidate decodes
    L = 50
    def cand_series():
        for i in range(L):
            yield (f"d[{i}]", [d[i] for d in aligned])
        for i in range(L-1):
            yield (f"LE[{i},{i+1}]", [d[i] | (d[i+1]<<8) for d in aligned])
            yield (f"BE[{i},{i+1}]", [(d[i]<<8) | d[i+1] for d in aligned])
        # signed 8-bit and 16-bit (corrections / errors can be negative)
        for i in range(L):
            yield (f"s8[{i}]", [(d[i]-256 if d[i]>127 else d[i]) for d in aligned])
        for i in range(L-1):
            def s16le(d, i):
                v = d[i] | (d[i+1] << 8)
                return v - 65536 if v > 32767 else v
            yield (f"s16LE[{i},{i+1}]", [s16le(d, i) for d in aligned])
        # reciprocal of 16-bit pairs (RPM stored as period -> RPM = k / period)
        for i in range(L-1):
            yield (f"invLE[{i},{i+1}]", [ (1e6/(d[i] | (d[i+1]<<8))) if (d[i] | (d[i+1]<<8)) else 0.0 for d in aligned])

    cands = list(cand_series())
    numeric = []
    for key in names:
        if key in ("Time",): continue
        try:
            y = [float(rows[j][1][key]) for j in csv_idx]   # restricted to overlap window
        except (ValueError, KeyError):
            continue
        numeric.append((key, y))

    print(f"\n{'channel':16s} {'best byte':12s} {'scale':>10s} {'bias':>8s} {'R2':>6s}")
    result = {}
    for key, y in numeric:
        best = None
        for cname, x in cands:
            if len(set(x)) < 2 and len(set(y)) > 1:
                continue
            slope, inter, r2 = linfit(x, y)
            if best is None or r2 > best[3]:
                best = (cname, slope, inter, r2)
        if best:
            result[key] = best
            flag = "  <== locked" if best[3] > 0.98 else (" ok" if best[3] > 0.9 else " weak")
            print(f"{key:16s} {best[0]:12s} {best[1]:10.4f} {best[2]:8.2f} {best[3]:6.3f}{flag}")

    # emit paste-ready map
    out = os.path.join(os.path.dirname(os.path.abspath(pcap)), "channel_map.txt")
    with open(out, "w") as f:
        f.write("# CONFIRMED map from align_decode (channel: byte, scale, bias, R2)\n")
        for key, (cname, slope, inter, r2) in result.items():
            f.write(f"{key:18s} {cname:12s} scale={slope:.5f} bias={inter:.3f} R2={r2:.3f}\n")
    print(f"\nwrote {out}")

if __name__ == "__main__":
    main()
