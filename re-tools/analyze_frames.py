#!/usr/bin/env python3
"""Frame-level analysis of the Invent Jetronic protocol streams."""
import os, sys
from collections import Counter

CAP = os.path.join(os.path.dirname(os.path.abspath(__file__)), "captures")
SYNC = bytes([0x55, 0x00, 0xAA, 0x00])

def load(name):
    with open(os.path.join(CAP, name), "rb") as f:
        return f.read()

def extract_frames(stream):
    """Split on sync marker; return list of (offset, body) where body starts at len byte."""
    frames = []
    i = 0
    while True:
        j = stream.find(SYNC, i)
        if j < 0:
            break
        k = stream.find(SYNC, j + 4)
        body = stream[j + 4: k if k > 0 else len(stream)]
        frames.append((j, body))
        if k < 0:
            break
        i = k
    return frames

def cksum_ok(body):