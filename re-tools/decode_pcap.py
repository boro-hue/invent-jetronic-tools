#!/usr/bin/env python3
"""Parse a USBPcap capture of the InventCommander<->ECU FTDI session.
Extracts bulk transfer payloads into two direction streams and prints stats.
Outputs: captures/out_stream.bin (host->ECU), captures/in_stream.bin (ECU->host, FTDI 2-byte status stripped),
         captures/frames.txt (time-segmented view of both directions interleaved)
"""
import struct, sys, os

CAP = os.path.join(os.path.dirname(os.path.abspath(__file__)), "captures")
PCAP = os.path.join(CAP, "commander_session.pcap")

def parse(path):
    with open(path, "rb") as f:
        data = f.read()
    magic = struct.unpack_from("<I", data, 0)[0]
    assert magic == 0xA1B2C3D4, hex(magic)
    network = struct.unpack_from("<I", data, 20)[0]
    off = 24
    pkts = []
    while off + 16 <= len(data):
        ts_sec, ts_usec, incl, orig = struct.unpack_from("<IIII", data, off)
        off += 16
        rec = data[off:off + incl]
        off += incl
        if len(rec) < 27:
            continue
        headerLen, = struct.unpack_from("<H", rec, 0)
        status, function = struct.unpack_from("<IH", rec, 10)
        info = rec[16]
        bus, device = struct.unpack_from("<HH", rec, 17)
        endpoint = rec[21]
        transfer = rec[22]
        dataLength, = struct.unpack_from("<I", rec, 23)
        payload = rec[headerLen:headerLen + dataLength]
        t = ts_sec + ts_usec / 1e6
        pkts.append(dict(t=t, dev=device, ep=endpoint, xfer=transfer, info=info,
                         func=function, dlen=dataLength, pl=payload))
    return network, pkts

def main():
    network, pkts = parse(PCAP)
    print(f"link type {network}, total records: {len(pkts)}")
    bulk = [p for p in pkts if p["xfer"] == 3 and p["dlen"] > 0]
    devs = {}
    for p in bulk:
        devs.setdefault((p["dev"], p["ep"]), [0, 0])
        devs[(p["dev"], p["ep"])][0] += 1
        devs[(p["dev"], p["ep"])][1] += p["dlen"]
    print("bulk endpoints (device, ep): count, bytes")
    for k, v in sorted(devs.items()):
        print(f"  dev {k[0]} ep 0x{k[1]:02X}: {v[0]} pkts, {v[1]} bytes")

    t0 = min(p["t"] for p in pkts)
    out_stream = bytearray()   # host -> ECU
    in_stream = bytearray()    # ECU -> host (2-byte FTDI status stripped per IN packet)
    events = []                # (t, dir, bytes)
    for p in bulk:
        if p["ep"] & 0x80:  # IN
            if p["dlen"] <= 2:
                continue    # status-only poll
            body = p["pl"][2:]
            in_stream.extend(body)
            events.append((p["t"] - t0, "IN ", body))
        else:               # OUT
            if p["info"] & 1:
                continue    # completion of OUT, data already counted on request
            out_stream.extend(p["pl"])
            events.append((p["t"] - t0, "OUT", p["pl"]))

    with open(os.path.join(CAP, "out_stream.bin"), "wb") as f:
        f.write(out_stream)
    with open(os.path.join(CAP, "in_stream.bin"), "wb") as f:
        f.write(in_stream)
    print(f"host->ECU: {len(out_stream)} bytes | ECU->host: {len(in_stream)} bytes")

    events.sort(key=lambda e: e[0])
    with open(os.path.join(CAP, "frames.txt"), "w") as f:
        last_t = None
        for t, d, b in events:
            gap = 0 if last_t is None else (t - last_t) * 1000
            last_t = t
            hx = " ".join(f"{x:02X}" for x in b[:48])
            more = f" ...(+{len(b)-48})" if len(b) > 48 else ""
            f.write(f"{t:9.3f}s [+{gap:7.1f}ms] {d} {len(b):4d}B  {hx}{more}\n")
    print(f"first OUT bytes: {' '.join(f'{x:02X}' for x in out_stream[:32])}")
    print(f"first IN  bytes: {' '.join(f'{x:02X}' for x in in_stream[:32])}")
    print("frames.txt written")

if __name__ == "__main__":
    main()
