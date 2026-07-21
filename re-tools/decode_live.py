#!/usr/bin/env python3
"""Correlate the 49 data bytes of the live telemetry frame (CMD 0x01) to channels.
Prints per-byte min/max/mean + 16-bit LE candidate decodes for wide-range channels.
NOTE: early-stage tool, written under the initial "49 data bytes + CRC" assumption.
The payload is actually 50 data bytes -- d49 is a status byte, not a checksum (see
docs/PROTOCOL.md) -- so this script simply ignores d49. align_decode.py handles all 50."""
import os, struct, collections

CAP = os.path.join(os.path.dirname(os.path.abspath(__file__)), "captures")
SYNC = bytes([0x55, 0x00, 0xAA, 0x00])

def frames(stream):
    i, out, n = 0, [], len(stream)
    while i < n - 6:
        if stream[i:i+4] == SYNC:
            length = stream[i+4]
            body = stream[i+5:i+5+length]
            out.append((length, body))
            i += 5 + length if length else 6
        else:
            i += 1
    return out

with open(os.path.join(CAP, "in_stream.bin"), "rb") as f:
    data = f.read()
live = [body for length, body in frames(data) if length == 0x33 and body and body[0] == 0x01]
print(f"{len(live)} live frames; body = CMD(1) + 50 bytes")

# data payload = bytes after CMD (index1) up to CRC. Use indices 1..49 (49 data bytes) + last=CRC.
payloads = [b[1:50] for b in live if len(b) >= 50]
L = 49
cols = list(zip(*payloads))  # cols[i] = tuple of values at data-offset i across all frames

print("\n8-bit per-offset stats:")
for i in range(L):
    v = cols[i]
    mn, mx = min(v), max(v)
    mean = sum(v)/len(v)
    span = mx - mn
    tag = "  <-- varies" if span > 2 else ""
    print(f"  d[{i:2d}] min={mn:3d} max={mx:3d} mean={mean:6.1f} span={span:3d}{tag}")

print("\n16-bit LE candidates (offset i,i+1) where value could exceed 255 (e.g. RPM):")
for i in range(L-1):
    vals = [c0 | (c1 << 8) for c0, c1 in zip(cols[i], cols[i+1])]
    mn, mx = min(vals), max(vals)
    if 200 < mx < 9000 and mx - mn > 50:
        print(f"  LE[{i:2d},{i+1:2d}] min={mn:5d} max={mx:5d}  (RPM-like if ~600-3000)")

print("\nfirst 3 full payloads:")
for b in payloads[:3]:
    print("  " + " ".join(f"{x:02X}" for x in b))
print("\nCSV column order for reference:")
print("  Time,Runlevel,Rpm,Map,Clt,Iat,InjTime,InjCount,InjDuty,InjEndAngle,Tps,Afr,TargetAfr,")
print("  LambdaCorrFast,LambdaCorrSlow,IdlePos,AccelCorr,Voltage,Load16,AccelTotalCorr,ChargeTemp,")
print("  InjChargeTCorr,Airflow,MafEmu,FuelConsumed,MapVoltage,CltVoltage,IatVoltage,TpsVoltage,")
print("  LambdaVoltage,Angle,Dwell,Debug1,Debug2,<flags...>")
