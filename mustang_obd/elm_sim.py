#!/usr/bin/env python3
"""
elm_sim.py - a simulated ELM327 adapter plugged into a simulated car, for
`mustang_obd.py --demo` and the tests:
  vehicle="mustang": 2016 Mustang on CAN (PCM at 7E0/7E8, a TCM at 7E1/7E9)
  vehicle="w211":    2005 W211 E220 CDI on K-line, ISO 14230-4 KWP2000 (engine ECU 0x10)

ALL VALUES ARE SYNTHETIC. The VIN is deliberately fake ("SIMULATED0MUSTANG")
and the Mode 22 identifiers below are made up for exercising the code paths -
nothing here is a reference for what a real car reports.

The adapter side follows the ELM327 datasheet behaviour that matters to a
client: echo/spaces/headers/linefeed switches, '>' prompt, SEARCHING... on the
first request in auto-protocol, NO DATA, CAN ISO-TP multi-frame output (with
padding, like a real adapter), interleaved answers from several ECUs, and the
optional trailing response-count digit.
"""

import math
import time


def _bitmaps(ids):
    """Supported-id bitmaps keyed by base (00, 20, 40, ...), chained like J1979."""
    ids = set(ids)
    out = {}
    top = max(ids) if ids else 0
    base = 0
    while base <= top and base <= 0xE0:
        bits = 0
        for i in range(32):
            pid = base + i + 1
            if pid in ids or (pid == base + 0x20 and pid <= top):
                bits |= 1 << (31 - i)
        out[base] = bits.to_bytes(4, "big")
        base += 0x20
    return out


class SimEcu:
    def __init__(self, car, pids, dtcs=(), pending=(), permanent=(), mode09=None,
                 freeze=(), mode06=None, dids=None):
        self.car = car
        self.pids = set(pids)
        self.dtcs, self.pending = list(dtcs), list(pending)
        self.permanent = None if permanent is None else list(permanent)
        self.mode09 = mode09 or {}
        self.freeze = set(freeze)
        self.mode06 = mode06 or {}
        self.dids = dids or {}

    @staticmethod
    def _dtc_bytes(code):
        a = "PCBU".index(code[0]) << 6 | int(code[1]) << 4 | int(code[2], 16)
        return [a, int(code[3:5], 16)]

    def _dtc_reply(self, sid, codes):
        out = [sid + 0x40, len(codes)]
        for c in codes:
            out += self._dtc_bytes(c)
        return bytes(out)

    def respond(self, req):
        sid = req[0]
        if sid == 0x01:
            pids = list(req[1:])
            if not pids or len(pids) > 6:
                return None
            out = [0x41]
            for p in pids:
                if p % 0x20 == 0:
                    bm = _bitmaps(self.pids).get(p)
                    if bm:
                        out += [p] + list(bm)
                elif p in self.pids:
                    out += [p] + list(self.car.pid(p))
            return bytes(out) if len(out) > 1 else None
        if sid == 0x02 and len(req) >= 3:
            pid, frame = req[1], req[2]
            if frame != 0:
                return None
            if pid % 0x20 == 0:
                bm = _bitmaps(self.freeze).get(pid)
                return bytes([0x42, pid, 0] + list(bm)) if bm else None
            if pid not in self.freeze:
                return None
            data = (bytes(self._dtc_bytes(self.dtcs[0])) if self.dtcs else bytes(2)) if pid == 0x02 \
                else self.car.pid(pid, frozen=True)
            return bytes([0x42, pid, 0]) + data
        if sid == 0x03:
            return self._dtc_reply(0x03, self.dtcs)
        if sid == 0x07:
            return self._dtc_reply(0x07, self.pending)
        if sid == 0x0A:
            return None if self.permanent is None else self._dtc_reply(0x0A, self.permanent)
        if sid == 0x06 and len(req) == 2:
            mid = req[1]
            if mid % 0x20 == 0:
                bm = _bitmaps(self.mode06).get(mid)
                return bytes([0x46, mid] + list(bm)) if bm else None
            recs = self.mode06.get(mid)
            if not recs:
                return None
            out = [0x46]
            for tid, uas, val, lo, hi in recs:
                out += [mid, tid, uas, val >> 8, val & 0xFF, lo >> 8, lo & 0xFF, hi >> 8, hi & 0xFF]
            return bytes(out)
        if sid == 0x09 and len(req) == 2:
            info = req[1]
            if info % 0x20 == 0:
                bm = _bitmaps(self.mode09).get(info)
                return bytes([0x49, info] + list(bm)) if bm else None
            data = self.mode09.get(info)
            return bytes([0x49, info]) + data if data is not None else None
        if sid == 0x22 and len(req) == 3:
            did = req[1] << 8 | req[2]
            fn = self.dids.get(did)
            if fn is None:
                return bytes([0x7F, 0x22, 0x31])
            return bytes([0x62, req[1], req[2]]) + fn()
        return bytes([0x7F, sid, 0x11])


class SimCar:
    VIN = "SIMULATED0MUSTANG"

    def __init__(self, clock=time.monotonic, vehicle="mustang"):
        self.clock = clock
        self.t0 = clock()
        self.forced_offline = False
        self.offline_windows = []    # [(t_from, t_to)] seconds since start: "ignition off"
        self.vehicle = vehicle
        self.legacy = vehicle == "w211"   # K-line instead of CAN
        if vehicle == "w211":
            self.VIN = "SIMULATED0W211E22"
            self.pcm = SimEcu(
                self, [0x01, 0x04, 0x05, 0x0B, 0x0C, 0x0D, 0x0F, 0x10, 0x11, 0x1C, 0x1F, 0x21, 0x23,
                       0x2C, 0x30, 0x31, 0x33],
                dtcs=["P0401", "P0670"], pending=[], permanent=None,
                mode09={0x02: b"\x01" + self.VIN.encode(),
                        0x04: b"\x01" + b"SIMCAL-CDI-0001".ljust(16, b"\x00")},
                freeze=[0x02, 0x05, 0x0C, 0x0D])
            self.tcm = None
            return
        pcm_pids = [0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0B, 0x0C, 0x0D, 0x0E,
                    0x0F, 0x10, 0x11, 0x13, 0x15, 0x19, 0x1C, 0x1F, 0x21, 0x2E, 0x2F, 0x30,
                    0x31, 0x32, 0x33, 0x34, 0x38, 0x3C, 0x3D, 0x41, 0x42, 0x43, 0x44, 0x45,
                    0x46, 0x47, 0x49, 0x4A, 0x4C, 0x4D, 0x4E, 0x51]
        misfire = {0xA2 + c: [(0x0B, 0x24, n, 0, 0xFFFF), (0x0C, 0x24, n, 0, 0xFFFF)]
                   for c, n in enumerate([0, 1, 37, 0, 2, 0])}
        misfire[0xA1] = [(0x0C, 0x24, 40, 0, 0xFFFF)]
        self.pcm = SimEcu(
            self, pcm_pids, dtcs=["P0303"], pending=["P0171"], permanent=[],
            mode09={0x02: b"\x01" + self.VIN.encode(),
                    0x04: b"\x01" + b"SIMCAL-PCM-0001".ljust(16, b"\x00"),
                    0x06: b"\x01\x1a\x2b\x3c\x4d",
                    0x0A: b"\x01" + b"ECM\x00-EngineControl".ljust(20, b"\x00")},
            freeze=[0x02, 0x04, 0x05, 0x0C, 0x0D],
            mode06={0x01: [(0x01, 0x0A, 7000, 4000, 0xFFFF)],
                    0x21: [(0x80, 0x20, 48, 0, 192)], **misfire},
            dids={0x1E1C: lambda: self._s16(round(self.tft() * 16)),
                  0xDD01: lambda: (123456).to_bytes(3, "big"),
                  0xF190: lambda: self.VIN.encode(),
                  0xF188: lambda: b"SIM-STRATEGY-01",
                  0x4242: lambda: b"\x12\x34"})
        self.tcm = SimEcu(
            self, [0x01, 0x0C, 0x0D, 0xA4],
            mode09={0x0A: b"\x01" + b"TCM\x00-TransmissionCtrl"[:20].ljust(20, b"\x00")})

    @staticmethod
    def _s16(v):
        return (v & 0xFFFF).to_bytes(2, "big")

    def t(self):
        return self.clock() - self.t0

    @property
    def online(self):
        t = self.t()
        return not self.forced_offline and not any(a <= t < b for a, b in self.offline_windows)

    def tft(self):
        return min(85.0, 40.0 + self.t() * 0.4)

    def state(self):
        t = self.t()
        drive = 0.5 - 0.5 * math.cos(2 * math.pi * t / 20) if t > 5 else 0.0
        rpm = 750 + 2600 * drive
        if self.vehicle == "w211":
            rpm = 760 + 2900 * drive
        return {
            "rail_bar": 280 + 1050 * drive, "boost_kpa": 100 + 115 * drive, "egr": 40 * (1 - drive),
            "rpm": rpm, "speed": max(0, (rpm - 750) / 26), "ect": min(90, 45 + t * 0.6),
            "load": 18 + 55 * drive, "tps": 12 + 40 * drive, "map": 30 + 60 * drive,
            "maf": 3.2 + 75 * drive, "stft1": 2.5 * math.sin(t * 1.7),
            "stft2": -1.8 * math.sin(t * 1.3 + 1), "ltft1": 3.1, "ltft2": 2.3,
            "timing": 14 + 18 * (1 - drive), "lambda": 1.0 + 0.02 * math.sin(t * 2.1),
            "cat1": 420 + 300 * drive, "cat2": 410 + 290 * drive, "runtime": int(t),
        }

    def pid(self, p, frozen=False):
        s = self.state()
        if frozen:
            s.update(rpm=2050, speed=64, ect=88, load=47)
        u16 = lambda v: [int(v) >> 8 & 0xFF, int(v) & 0xFF]       # noqa: E731
        trim = lambda v: int(round(v * 128 / 100 + 128)) & 0xFF   # noqa: E731
        pct = lambda v: int(round(v * 255 / 100)) & 0xFF          # noqa: E731
        table = {
            0x01: [0x81, 0x07, 0x65, 0x00], 0x03: [0x02, 0x00], 0x04: [pct(s["load"])],
            0x05: [int(s["ect"]) + 40], 0x06: [trim(s["stft1"])], 0x07: [trim(s["ltft1"])],
            0x08: [trim(s["stft2"])], 0x09: [trim(s["ltft2"])], 0x0B: [int(s["map"])],
            0x0C: u16(s["rpm"] * 4), 0x0D: [int(s["speed"])],
            0x0E: [int((s["timing"] + 64) * 2)], 0x0F: [25 + 40], 0x10: u16(s["maf"] * 100),
            0x11: [pct(s["tps"])], 0x13: [0x33], 0x15: [130, 0xFF], 0x19: [128, 0xFF],
            0x1C: [0x01], 0x1F: u16(s["runtime"]), 0x21: [0, 0], 0x2E: [pct(12)],
            0x2F: [pct(62)], 0x30: [17], 0x31: u16(412), 0x32: u16(-36 & 0xFFFF),
            0x33: [99], 0x34: u16(s["lambda"] * 32768) + [0x80, 0x10],
            0x38: u16(s["lambda"] * 32768) + [0x7F, 0xF0], 0x3C: u16((s["cat1"] + 40) * 10),
            0x3D: u16((s["cat2"] + 40) * 10), 0x41: [0x00, 0x07, 0xE1, 0x00],
            0x42: u16(14210), 0x43: u16(s["load"] * 255 / 100), 0x44: u16(32768),
            0x45: [pct(s["tps"] - 10)], 0x46: [18 + 40], 0x47: [pct(s["tps"] + 2)],
            0x49: [pct(s["tps"] + 5)], 0x4A: [pct(s["tps"] / 2 + 3)], 0x4C: [pct(s["tps"])],
            0x4D: [0, 0], 0x4E: u16(5130), 0x51: [0x01],
            0x23: u16(s["rail_bar"] * 10), 0x2C: [pct(s["egr"])],
            0xA4: [0x01, (1 + int(s["speed"] // 25)) << 4] + u16(4171 // (1 + int(s["speed"] // 25))),
        }
        if self.vehicle == "w211":
            table.update({0x0B: [int(s["boost_kpa"])], 0x1C: [0x06], 0x01: [0x01, 0x04, 0x00, 0x00]})
        return bytes(table[p])

    def ecus(self, header):
        if self.legacy:               # K-line: one engine ECU, functional addressing only
            return [("10", self.pcm)]
        h = header.upper()
        if h == "7DF":
            return [("7E8", self.pcm), ("7E9", self.tcm)]
        if h == "7E0":
            return [("7E8", self.pcm)]
        if h == "7E1":
            return [("7E9", self.tcm)]
        return []


class SimElm327:
    """count_semantics: how the trailing response-count digit is honoured -
    'messages' (count ISO-TP messages), 'frames' (count CAN frames) or 'ignore'."""

    def __init__(self, car=None, count_semantics="messages", interleave=True, quirks=(),
                 vehicle="mustang"):
        self.car = car or SimCar(vehicle=vehicle)
        self.count_semantics = count_semantics
        self.interleave = interleave
        # "no_s0": ignores ATS0, "no_h1": ignores ATH1 (bad clones);
        # "iso9141": K-line car answers as ISO 9141-2 (48 6B header) instead of KWP2000
        self.quirks = set(quirks)
        self._defaults()

    def _defaults(self):
        self.echo, self.linefeed, self.spaces, self.headers = True, False, True, False
        self.protocol, self.connected, self.header = "0", False, "7DF"

    def _eol(self):
        return "\r\n" if self.linefeed else "\r"

    def handle(self, line):
        cmd = line.strip().upper().replace(" ", "")
        out = []
        if self.echo:
            out.append(line)
        body = self._at(cmd[2:]) if cmd.startswith("AT") else self._obd(cmd)
        if cmd == "ATZ":
            body = ["", "ELM327 v1.5"]
        out += body
        eol = self._eol()
        return eol.join(out) + eol + eol + ">"

    def _at(self, c):
        if c in ("Z", "WS", "D"):
            self._defaults()
            return ["OK"] if c == "D" else ["ELM327 v1.5"]
        if c == "I":
            return ["ELM327 v1.5"]
        if c == "@1":
            return ["OBDII to RS232 Interpreter"]
        if c == "RV":
            return ["14.2V" if self.car.online else "12.4V"]
        auto = self.protocol.startswith("A") or self.protocol == "0"
        if c == "DPN":
            return [("A" if auto else "") + self._proto()]
        if c == "DP":
            name = {"3": "ISO 9141-2", "5": "ISO 14230-4 (KWP FAST)"}.get(self._proto(), "ISO 15765-4 (CAN 11/500)")
            return [("AUTO, " if auto else "") + name]
        flags = {"E0": ("echo", False), "E1": ("echo", True), "L0": ("linefeed", False),
                 "L1": ("linefeed", True), "S0": ("spaces", False), "S1": ("spaces", True),
                 "H0": ("headers", False), "H1": ("headers", True)}
        if c in flags:
            if not ((c == "S0" and "no_s0" in self.quirks) or (c == "H1" and "no_h1" in self.quirks)):
                setattr(self, *flags[c])
            return ["OK"]
        if c.startswith("SP"):
            self.protocol = c[2:]
            self.connected = False
            return ["OK"]
        if c.startswith("SH") and len(c) == 5:
            self.header = c[2:]
            return ["OK"]
        if c in ("AT0", "AT1", "AT2", "CAF0", "CAF1", "AL", "CFC1") or c.startswith(("ST", "CRA")):
            return ["OK"]
        return ["?"]

    def _proto(self):
        if not self.car.legacy:
            return "6"
        return "3" if "iso9141" in self.quirks else "5"

    def _legacy_split(self, r):
        """J1979 non-CAN layout: Mode 09 items in numbered 4-byte messages, DTCs 3 per message."""
        if r[0] == 0x49 and len(r) > 3 and r[1] in (0x02, 0x04, 0x06, 0x0A):
            data = bytes(r[3:])
            data = bytes(-len(data) % 4) + data
            return [bytes([0x49, r[1], i // 4 + 1]) + data[i:i + 4] for i in range(0, len(data), 4)]
        if r[0] in (0x43, 0x47, 0x4A):
            codes = bytes(r[2:])
            codes += bytes(-len(codes) % 6 or (6 if not codes else 0))
            return [bytes([r[0]]) + codes[i:i + 6] for i in range(0, len(codes), 6)]
        return [r]

    def _legacy_fmt(self, src, m):
        if self.headers:
            head = [0x48, 0x6B, int(src, 16)] if "iso9141" in self.quirks else [0x80 | len(m), 0xF1, int(src, 16)]
            b = bytes(head) + m
            b += bytes([sum(b) & 0xFF])
        else:
            b = m
        return (" " if self.spaces else "").join(f"{x:02X}" for x in b)

    def _fmt(self, hdr, data):
        hexes = [f"{b:02X}" for b in data]
        sep = " " if self.spaces else ""
        return sep.join(([hdr] if self.headers else []) + hexes)

    def _frames(self, payload):
        """ISO-TP segmentation -> list of 8-byte CAN data fields (padded)."""
        if len(payload) <= 7:
            return [bytes([len(payload)]) + payload + bytes(7 - len(payload))]
        frames = [bytes([0x10 | (len(payload) >> 8), len(payload) & 0xFF]) + payload[:6]]
        rest, sn = payload[6:], 1
        while rest:
            chunk, rest = rest[:7], rest[7:]
            frames.append(bytes([0x20 | sn]) + chunk + bytes(7 - len(chunk)))
            sn = (sn + 1) & 0x0F
        return frames

    def _obd(self, cmd):
        if not cmd or any(ch not in "0123456789ABCDEF" for ch in cmd):
            return ["?"]
        count = None
        if len(cmd) % 2:
            count, cmd = int(cmd[-1], 16), cmd[:-1]
        req = bytes.fromhex(cmd)
        lines = []
        auto = self.protocol == "0" or self.protocol.startswith("A")
        if self.car.legacy and not auto and self.protocol not in ("3", "4", "5"):
            return ["CAN ERROR"]                     # client insists on CAN: car is on K-line
        if not self.connected and auto:
            lines.append("SEARCHING...")
        elif not self.connected and self.car.legacy:
            lines.append("BUS INIT: ...OK")
        if not self.car.online:
            return lines + (["UNABLE TO CONNECT"] if lines else ["NO DATA"])
        self.connected = True
        if self.car.legacy:
            if req[0] == 0x01 and len(req) > 2:      # multi-PID requests are CAN-only
                return lines + ["NO DATA"]
            msgs = []
            for hdr, ecu in self.car.ecus(self.header):
                r = ecu.respond(req)
                if r is not None:
                    msgs += [(hdr, m) for m in self._legacy_split(r)]
            if count and self.count_semantics != "ignore":
                msgs = msgs[:count]
            return lines + ([self._legacy_fmt(h, m) for h, m in msgs] or ["NO DATA"])
        answers = []
        for hdr, ecu in self.car.ecus(self.header):
            r = ecu.respond(req)
            if r is not None:
                answers.append((hdr, r))
        if not answers:
            return lines + ["NO DATA"]
        if count and self.count_semantics == "messages":
            answers = answers[:count]
        per_ecu = [[(hdr, f) for f in self._frames(r)] for hdr, r in answers]
        ordered = []
        if self.interleave:
            i = 0
            while any(i < len(x) for x in per_ecu):
                ordered += [x[i] for x in per_ecu if i < len(x)]
                i += 1
        else:
            for x in per_ecu:
                ordered += x
        if count and self.count_semantics == "frames":
            ordered = ordered[:count]
        if self.headers:
            return lines + [self._fmt(h, f) for h, f in ordered]
        # headers off (CAF on): single frames without PCI; multi-frame in "0:" style
        out = []
        for hdr, r in answers:
            if len(r) <= 7:
                out.append(self._fmt(hdr, r))
            else:
                out.append(f"{len(r):03X}")
                out.append("0: " + self._fmt(hdr, r[:6]))
                rest, n = r[6:], 1
                while rest:
                    out.append(f"{n & 0xF:X}: " + self._fmt(hdr, rest[:7]))
                    rest, n = rest[7:], n + 1
        return lines + out


class SimTransport:
    """Transport that talks to SimElm327 in-process (same interface as the real ones)."""
    name = "demo (simulated Mustang - synthetic data)"

    def __init__(self, latency=0.0, chunk=0, fail_after_writes=None, **kw):
        self.elm = SimElm327(**kw)
        if self.elm.car.vehicle == "w211":
            self.name = "demo (simulated W211 E220 CDI - synthetic data)"
        self.sent = []               # every command line written (for the read-only tests)
        self.latency = latency
        self.chunk = chunk
        self.fail_after = fail_after_writes
        self.writes = 0
        self.out = bytearray()
        self.inbuf = ""

    def open(self):
        pass

    def close(self):
        pass

    def write(self, data):
        self.writes += 1
        if self.fail_after is not None and self.writes > self.fail_after:
            self.fail_after = None
            raise ConnectionError("simulated Bluetooth drop")
        for ch in data.decode("ascii"):
            if ch == "\r":
                self.sent.append(self.inbuf)
                self.out += self.elm.handle(self.inbuf).encode("ascii")
                self.inbuf = ""
            else:
                self.inbuf += ch

    def read_some(self):
        if self.latency:
            time.sleep(self.latency)
        if not self.out:
            time.sleep(0.002)
            return b""
        n = self.chunk or len(self.out)
        b = bytes(self.out[:n])
        del self.out[:n]
        return b

    def drain(self):
        self.out.clear()
