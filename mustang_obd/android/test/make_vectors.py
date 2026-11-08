#!/usr/bin/env python3
"""Emit decoder test vectors from the Python tool, so the Java port can be checked
byte-for-byte against it:   python3 make_vectors.py > vectors.txt

Line formats:
  P <pid hex> <data hex> <value1>|<value2>|...   (values as mustang_obd.fmt prints them)
  D <a> <b> <dtc>
  M <record hex> <value>|<min>|<max>|<unit>|<pass>
"""
import os
import random
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
import mustang_obd as M  # noqa: E402

random.seed(42)
for pid, (_desc, cols) in sorted(M.PIDS.items()):
    if not cols:
        continue
    for n in range(1, 9):
        for _ in range(40):
            data = bytes(random.randrange(256) for _ in range(n))
            vals = [M.fmt(v) for _, v in M.decode_pid(pid, data)]
            print(f"P {pid:02X} {data.hex().upper()} {'|'.join(vals)}")
for _ in range(500):
    a, b = random.randrange(256), random.randrange(256)
    print(f"D {a} {b} {M.dtc_str(a, b)}")
for uas in sorted(M.UASID) + [0x77]:
    for _ in range(20):
        rec = bytes([random.choice([0x01, 0x21, 0xA3]), random.randrange(256), uas]
                    + [random.randrange(256) for _ in range(6)])
        r = M.parse_mode06(b"\x46" + rec)[0]
        print(f"M {rec.hex().upper()} {r['value']}|{r['min']}|{r['max']}|{r['unit']}|{r['pass']}")
