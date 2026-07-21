#!/usr/bin/env python3
"""Replay combo-capture live frames through the logger's CHANNELS decoder; sanity-check values."""
import os, importlib.util, struct
TOOLS = os.path.dirname(os.path.abspath(__file__))
# jetronic_logger.py lives in the repo root, one level above re-tools/
spec = importlib.util.spec_from_file_location("jl", os.path.join(os.path.dirname(TOOLS), "jetronic_logger.py"))
jl = importlib.util.module_from_spec(spec); spec.loader.exec_module(jl)
SYNC = jl.SYNC

# parse combo pcap live frames (data = 50 bytes)
data = open(os.path.join(TOOLS, "captures", "combo_session.pcap"), "rb").read()
off = 24; buf = bytearray()
while off + 16 <= len(data):
    ts_s, ts_us, incl, orig = struct.unpack_from("<IIII", data, off); off += 16
    rec = data[off:off+incl]; off += incl
    if len(rec) < 27: continue
    hL, = struct.unpack_from("<H", rec, 0)
    ep = rec[21]; xfer = rec[22]; dlen, = struct.unpack_from("<I", rec, 23)
    if xfer == 3 and (ep & 0x80) and dlen > 2:
        buf.extend(rec[hL+2:hL+dlen])
frames = []
i = 0
while i < len(buf)-6:
    if buf[i:i+4] == SYNC and buf[i+4] == 0x33:
        body = bytes(buf[i+5:i+5+0x33])
        if len(body) == 0x33 and body[0] == 0x01:
            frames.append(body[1:51]); i += 5+0x33; continue
    i += 1

print(f"{len(frames)} frames. Decoded channels for 5 frames spread across the session:")
names = [c[0] for c in jl.CHANNELS]
show = ["Rpm","Map","Clt","Iat","InjTime","Tps","Afr","TargetAfr","LambdaCorrFast","IdlePos","Voltage","MafEmu"]
idxs = [0, len(frames)//4, len(frames)//2, 3*len(frames)//4, len(frames)-1]
for fi in idxs:
    d = frames[fi]
    vals = {n: jl._decode(k, d, i, mul, add) for (n, k, i, mul, add) in jl.CHANNELS}
    print("  " + "  ".join(f"{n}={vals[n]}" for n in show))
