#!/usr/bin/env python3
"""Split the ECU<->host byte streams into 55 00 AA 00 framed packets and classify them."""
import os, collections

CAP = os.path.join(os.path.dirname(os.path.abspath(__file__)), "captures")
SYNC = bytes([0x55, 0x00, 0xAA, 0x00])

def frames(stream):
    """Yield (cmd_or_type, length_field, payload_bytes, raw) using sync + length."""
    i = 0
    out = []
    n = len(stream)
    while i < n - 6:
        if stream[i:i+4] == SYNC:
            length = stream[i+4]          # byte after sync = payload length field
            # frame = sync(4) + len(1) + [length bytes incl cmd + data + crc]
            body = stream[i+5 : i+5+length]
            raw = stream[i : i+5+length]
            out.append((length, body, raw))
            i += 5 + length if length > 0 else 6
        else:
            i += 1
    return out

def main():
    for name in ("out_stream.bin", "in_stream.bin"):
        with open(os.path.join(CAP, name), "rb") as f:
            data = f.read()
        fr = frames(data)
        print(f"\n===== {name}: {len(fr)} frames =====")
        # classify by (length, first body byte = command/type)
        classes = collections.Counter()
        examples = {}
        for length, body, raw in fr:
            cmd = body[0] if body else None
            key = (length, cmd)
            classes[key] += 1
            if key not in examples:
                examples[key] = raw
        for (length, cmd), cnt in classes.most_common(15):
            ex = examples[(length, cmd)]
            hx = " ".join(f"{x:02X}" for x in ex[:40])
            cmds = f"0x{cmd:02X}" if cmd is not None else "-"
            print(f"  len={length:3d} cmd={cmds} x{cnt:5d}  {hx}{'...' if len(ex)>40 else ''}")

    # Focus: the dominant IN data frame (live telemetry). Show several consecutive to see which bytes move.
    with open(os.path.join(CAP, "in_stream.bin"), "rb") as f:
        data = f.read()
    fr = frames(data)
    lens = collections.Counter(length for length, _, _ in fr)
    live_len = lens.most_common(1)[0][0]
    print(f"\n===== dominant IN frame length = {live_len}: byte-variance across instances =====")
    same = [body for length, body, raw in fr if length == live_len]
    print(f"  {len(same)} instances")
    L = min(len(b) for b in same)
    changing = []
    for pos in range(L):
        vals = set(b[pos] for b in same)
        if len(vals) > 1:
            changing.append(pos)
    print(f"  frame body length {L}; {len(changing)} of {L} byte positions vary")
    print(f"  varying byte offsets: {changing}")
    print("  first 6 live frames (hex):")
    for b in same[:6]:
        print("   ", " ".join(f"{x:02X}" for x in b[:L]))

if __name__ == "__main__":
    main()
