#!/usr/bin/env python3
"""
mustang_obd.py - catch-all OBD-II data logger for a 2016 Ford Mustang 3.7 V6,
over a Bluetooth (or USB / WiFi) ELM327-compatible adapter.

Works on any CAN-bus OBD-II car (all US cars 2008+, most EU cars 2004+). The
Mustang-specific part is only the optional enhanced-data profile
(profile_mustang_2016_37.json).

READ-ONLY BY DESIGN. Only these diagnostic services are ever sent:
  01 live data, 02 freeze frame, 03/07/0A stored/pending/permanent DTCs,
  06 on-board monitor results, 09 vehicle info, 22 ReadDataByIdentifier.
It can NOT clear codes (04), write (2E/2F/3B), run routines (31) or change the
diagnostic session (10/27) - anything else is refused before it is sent.

Modes (pick one):
  --list         list adapter ports (serial / Bluetooth COM; add --ble to scan BLE)
  --handshake    connect, show adapter, battery volts, protocol, ECUs, VIN
  --snapshot     one-shot dump of everything the car reports -> JSON + TXT
  --log          continuous CSV of every supported live PID (+ enhanced data)
  --scan-dids    discover enhanced (Mode 22) data identifiers on one ECU

Connection (pick one; default is --bt):
  --bt                 auto-detect the paired Bluetooth SPP COM port
  --port COM7          explicit serial port (Bluetooth SPP or USB; Linux /dev/rfcomm0)
  --rfcomm MAC         direct Bluetooth RFCOMM socket (no COM port needed)
  --ble [ADDRESS]      Bluetooth Low Energy adapter (needs: py -m pip install bleak)
  --tcp HOST:PORT      WiFi adapter (usually 192.168.0.10:35000)
  --demo               built-in simulated Mustang - try everything without the car

Examples:
  py mustang_obd.py --demo --log --seconds 30
  py mustang_obd.py --bt --handshake
  py mustang_obd.py --bt --snapshot
  py mustang_obd.py --bt --log
  py mustang_obd.py --port COM7 --log --seconds 1800
  py mustang_obd.py --bt --scan-dids quick
"""

import argparse
import ast
import csv
import datetime
import importlib.util
import json
import math
import os
import re
import socket
import sys
import threading
import time

try:
    import serial                      # pyserial
    from serial.tools import list_ports
except ImportError:                    # only needed for COM-port transports
    serial = None
    list_ports = None

HERE = os.path.dirname(os.path.abspath(__file__))
LOG_DIR = os.path.join(HERE, "logs")
DEFAULT_PROFILE = os.path.join(HERE, "profile_mustang_2016_37.json")
TOOL_VERSION = "1.0"
RECONNECT_DELAY = 2.0        # seconds before the first reconnect attempt (doubles, max 15)

# =============================================================================
# Read-only guard. Every byte sent to the adapter passes through guard_at() or
# guard_obd(); anything not explicitly whitelisted raises GuardError instead.
# =============================================================================
READ_ONLY_SERVICES = {
    0x01: "current data", 0x02: "freeze frame data", 0x03: "stored DTCs",
    0x06: "on-board monitor results", 0x07: "pending DTCs", 0x09: "vehicle information",
    0x0A: "permanent DTCs", 0x22: "ReadDataByIdentifier",
}
_AT_ALLOWED = re.compile(
    r"^AT(Z|WS|D|I|@1|RV|DP|DPN|E[01]|L[01]|S[01]|H[01]|AL|CAF[01]|CFC1|AT[012]|ST[0-9A-F]{2}"
    r"|SPA?[0-9A-C]|SH[0-9A-F]{3}|CRA|CRA[0-9A-F]{3})$")
_HEX = set("0123456789ABCDEF")


class GuardError(Exception):
    """A command outside the read-only whitelist was about to be sent."""


def guard_at(cmd):
    c = cmd.replace(" ", "").upper()
    if not _AT_ALLOWED.match(c):
        raise GuardError(f"adapter command {cmd!r} is not on the read-only whitelist")
    return c


def guard_obd(req):
    r = req.replace(" ", "").upper()
    if len(r) < 2 or len(r) % 2 or not set(r) <= _HEX:
        raise GuardError(f"malformed request {req!r}")
    sid = int(r[:2], 16)
    if sid not in READ_ONLY_SERVICES:
        raise GuardError(f"service 0x{sid:02X} is not a read service - refusing to send {req!r}")
    return r


# =============================================================================
# Transports: anything with open/close/write/read_some/drain. read_some()
# returns whatever bytes arrived within ~50 ms (b"" if none) and raises OSError
# when the link is gone.
# =============================================================================
class SerialTransport:
    def __init__(self, port, baud):
        self.port, self.baud, self.ser = port, baud, None
        self.name = f"serial {port}"

    def open(self):
        if serial is None:
            sys.exit("pyserial missing - run:  py -m pip install pyserial")
        self.ser = serial.Serial(self.port, self.baud, timeout=0.05, write_timeout=3)

    def close(self):
        if self.ser:
            try:
                self.ser.close()
            except Exception:
                pass
            self.ser = None

    def write(self, data):
        self.ser.write(data)

    def read_some(self):
        b = self.ser.read(1)
        if b:
            n = self.ser.in_waiting
            if n:
                b += self.ser.read(n)
        return b

    def drain(self):
        self.ser.reset_input_buffer()


class SocketTransport:
    """TCP (WiFi adapters) or a raw Bluetooth RFCOMM socket."""

    def __init__(self, kind, addr):
        self.kind, self.addr, self.sock = kind, addr, None
        self.name = f"{kind} {addr[0]}:{addr[1]}"

    def open(self):
        if self.kind == "tcp":
            s = socket.create_connection(self.addr, timeout=10)
        else:
            if not hasattr(socket, "AF_BLUETOOTH"):
                sys.exit("This Python has no Bluetooth socket support. Pair the adapter and use\n"
                         "its COM port instead (--bt or --port COMx).")
            s = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_STREAM, socket.BTPROTO_RFCOMM)
            s.settimeout(20)
            s.connect(self.addr)
        s.settimeout(0.05)
        self.sock = s

    def close(self):
        if self.sock:
            try:
                self.sock.close()
            except Exception:
                pass
            self.sock = None

    def write(self, data):
        self.sock.sendall(data)

    def read_some(self):
        try:
            d = self.sock.recv(4096)
        except socket.timeout:
            return b""
        if not d:
            raise ConnectionError("adapter closed the connection")
        return d

    def drain(self):
        for _ in range(50):
            if not self.read_some():
                return


class BleTransport:
    """Bluetooth Low Energy ELM327 clones (Vgate iCar Pro BLE, Veepeak BLE, ...).
    They expose a UART-like GATT service: one notify characteristic (adapter ->
    us) and one write characteristic (us -> adapter)."""

    KNOWN = [  # (service, notify, write) seen on common BLE OBD adapters
        ("0000fff0-0000-1000-8000-00805f9b34fb", "0000fff1-0000-1000-8000-00805f9b34fb",
         "0000fff2-0000-1000-8000-00805f9b34fb"),
        ("0000ffe0-0000-1000-8000-00805f9b34fb", "0000ffe1-0000-1000-8000-00805f9b34fb",
         "0000ffe1-0000-1000-8000-00805f9b34fb"),
        ("e7810a71-73ae-499d-8c15-faa9aef0c3f2", "bef8d6c9-9c21-4c9e-b632-bd58c1009f9f",
         "bef8d6c9-9c21-4c9e-b632-bd58c1009f9f"),
    ]
    NAME_HINTS = ("OBD", "ELM", "VLINK", "V-LINK", "VEEPEAK", "VGATE", "KONNWEI", "LELINK",
                  "CARISTA", "IOS-VLINK", "ANDROID-VLINK", "OBDII", "OBD2")

    def __init__(self, address=None):
        self.address = address
        self.name = f"BLE {address or '(auto)'}"
        self.loop = self.client = self.wchar = None
        self.buf = bytearray()
        self.lock = threading.Lock()
        self.event = threading.Event()
        self.write_response = False

    def _run(self, coro, timeout):
        import asyncio
        return asyncio.run_coroutine_threadsafe(coro, self.loop).result(timeout)

    def open(self):
        if importlib.util.find_spec("bleak") is None:
            sys.exit("BLE needs the 'bleak' package:  py -m pip install bleak")
        import asyncio
        self.loop = asyncio.new_event_loop()
        threading.Thread(target=self.loop.run_forever, daemon=True).start()
        self._run(self._connect(), 60)

    @classmethod
    async def scan(cls, seconds=8.0):
        from bleak import BleakScanner
        devs = await BleakScanner.discover(timeout=seconds)
        return [(d.address, d.name or "") for d in devs]

    async def _connect(self):
        from bleak import BleakClient
        addr = self.address
        if not addr:
            found = await self.scan()
            hits = [(a, n) for a, n in found if any(h in n.upper() for h in self.NAME_HINTS)]
            if not hits:
                raise ConnectionError("no BLE OBD adapter found (names seen: "
                                      + ", ".join(n for _, n in found if n)[:300] + ")")
            addr = hits[0][0]
            print(f"  BLE adapter: {hits[0][1]} [{addr}]")
            self.name = f"BLE {hits[0][1]} [{addr}]"
        self.client = BleakClient(addr)
        await self.client.connect(timeout=20)
        services = self.client.services
        notify = write = None
        for svc_uuid, n_uuid, w_uuid in self.KNOWN:
            svc = services.get_service(svc_uuid)
            if svc:
                notify = svc.get_characteristic(n_uuid)
                write = svc.get_characteristic(w_uuid)
                if notify and write:
                    break
        if not (notify and write):  # generic fallback: first notify + first write char
            for svc in services:
                if svc.uuid.startswith(("00001800", "00001801", "0000180a")):
                    continue
                for ch in svc.characteristics:
                    if not notify and ("notify" in ch.properties or "indicate" in ch.properties):
                        notify = ch
                    if not write and ("write" in ch.properties
                                      or "write-without-response" in ch.properties):
                        write = ch
        if not (notify and write):
            raise ConnectionError("adapter has no usable notify/write characteristics")
        self.write_response = "write-without-response" not in write.properties
        self.wchar = write
        await self.client.start_notify(notify, self._on_notify)

    def _on_notify(self, _sender, data):
        with self.lock:
            self.buf += data
        self.event.set()

    def close(self):
        if self.client:
            try:
                self._run(self.client.disconnect(), 10)
            except Exception:
                pass
            self.client = None
        if self.loop:
            self.loop.call_soon_threadsafe(self.loop.stop)
            self.loop = None

    def write(self, data):
        if not self.client or not self.client.is_connected:
            raise ConnectionError("BLE link lost")
        for i in range(0, len(data), 20):
            self._run(self.client.write_gatt_char(self.wchar, data[i:i + 20],
                                                  response=self.write_response), 10)

    def read_some(self):
        if not self.client or not self.client.is_connected:
            raise ConnectionError("BLE link lost")
        self.event.wait(0.05)
        with self.lock:
            out = bytes(self.buf)
            self.buf.clear()
            self.event.clear()
        return out

    def drain(self):
        with self.lock:
            self.buf.clear()
            self.event.clear()


# =============================================================================
# ELM327 reply parsing (headers ON, CAN). Reassembles ISO-TP multi-frame
# messages per responding ECU, so several ECUs may answer one request.
# =============================================================================
class Reply:
    def __init__(self, lines):
        self.lines = list(lines)
        self.msgs = {}       # response header -> [complete message bytes, ...]
        self.partial = {}    # response header -> bytes of an incomplete multi-frame message
        self.status = []     # non-data lines: NO DATA, CAN ERROR, ?, ...

    def positive(self, service):
        sid = service + 0x40
        return [(h, m) for h, ms in sorted(self.msgs.items()) for m in ms if m and m[0] == sid]

    def negative(self, service):
        return [(h, m[2]) for h, ms in sorted(self.msgs.items()) for m in ms
                if len(m) >= 3 and m[0] == 0x7F and m[1] == service]


def parse_reply(lines, hdr_len=3):
    rep = Reply(lines)
    pending = {}  # hdr -> [total_len, bytearray, next_sequence_number]
    for ln in lines:
        s = ln.replace(" ", "").upper()
        if not s or s.startswith("SEARCHING") or s.startswith("BUSINIT"):
            continue
        if not set(s) <= _HEX or len(s) < hdr_len + 2 or (len(s) - hdr_len) % 2:
            rep.status.append(ln.strip())
            continue
        hdr, body = s[:hdr_len], bytes.fromhex(s[hdr_len:])
        kind = body[0] >> 4
        if kind == 0:                                   # single frame
            n = body[0] & 0x0F
            if n == 0 or n > len(body) - 1:
                rep.status.append(ln.strip())
                continue
            rep.msgs.setdefault(hdr, []).append(body[1:1 + n])
        elif kind == 1 and len(body) >= 2:              # first frame
            total = ((body[0] & 0x0F) << 8) | body[1]
            pending[hdr] = [total, bytearray(body[2:]), 1]
        elif kind == 2:                                 # consecutive frame
            p = pending.get(hdr)
            if p is None:
                continue
            if (body[0] & 0x0F) != p[2]:                # lost/out-of-order frame
                rep.partial[hdr] = bytes(p[1])
                del pending[hdr]
                continue
            p[1] += body[1:]
            p[2] = (p[2] + 1) & 0x0F
            if len(p[1]) >= p[0]:
                rep.msgs.setdefault(hdr, []).append(bytes(p[1][:p[0]]))
                del pending[hdr]
    for hdr, p in pending.items():
        rep.partial[hdr] = bytes(p[1])
    return rep


def frames_for(payload_len):
    """CAN frames an ISO-TP message of payload_len bytes occupies."""
    return 1 if payload_len <= 7 else 1 + math.ceil((payload_len - 6) / 7)


# =============================================================================
# ELM327 driver
# =============================================================================
class LinkError(Exception):
    """Adapter link failed (Bluetooth dropped, port vanished, no prompt)."""


class CarSilent(Exception):
    """Adapter answers, but no ECU does (ignition off?)."""


class Elm327:
    def __init__(self, transport, trace=None):
        self.t = transport
        self.trace = trace
        self.version = "?"
        self.proto_num = "?"
        self.proto_name = "?"
        self.hdr_len = 3
        self.can11 = True
        self.header = None
        self.cra = None
        self.count_mode = None   # response-count shortcut: None / "sf" / "messages" / "frames"

    def _log(self, direction, text):
        if self.trace:
            self.trace.write(f"{time.time():.3f} {direction} {text!r}\n")

    def _xfer(self, cmd, timeout):
        # NEVER send a bare CR: an ELM327 repeats its previous command on an empty
        # line, and that command could have come from some other app's session.
        if not cmd:
            raise GuardError("refusing to send an empty line (ELM327 would repeat the last command)")
        try:
            self.t.drain()
            self._log(">>", cmd)
            self.t.write((cmd + "\r").encode("ascii"))
            buf = bytearray()
            deadline = time.monotonic() + timeout
            while b">" not in buf:
                if time.monotonic() > deadline:
                    self._log("<<", buf.decode("ascii", "replace") + " [TIMEOUT]")
                    raise LinkError(f"adapter did not answer {cmd!r} within {timeout:.1f}s")
                buf += self.t.read_some()
        except OSError as e:
            raise LinkError(f"{type(e).__name__}: {e}") from e
        text = buf[:buf.index(b">")].decode("ascii", "replace")
        self._log("<<", text)
        echo = cmd.replace(" ", "").upper()
        out = []
        for ln in re.split(r"[\r\n]+", text):
            ln = "".join(ch for ch in ln if 32 <= ord(ch) < 127).strip()
            if ln and ln.replace(" ", "").upper() != echo:
                out.append(ln)
        return out

    def at(self, cmd, timeout=2.0):
        return self._xfer(guard_at(cmd), timeout)

    def obd(self, req, timeout=4.0, count=None):
        r = guard_obd(req)
        if count and 1 <= count <= 15:
            r += f"{count:X}"
        return parse_reply(self._xfer(r, timeout), self.hdr_len)

    def init(self, protocol="6"):
        lines = []
        for attempt in range(2):
            try:
                lines = self.at("ATZ", timeout=5.0)
                break
            except LinkError:
                if attempt:
                    raise
        self.version = next((ln for ln in lines if re.search(r"ELM|STN|OBD|v\d", ln, re.I)),
                            " ".join(lines) or "?")
        for c in ("ATE0", "ATL0", "ATS0", "ATH1", "ATAT1", "ATCAF1"):
            self.at(c)
        self.at("ATSP" + protocol)
        self.header = self.cra = None

    def _read_protocol(self):
        dpn = self.at("ATDPN")
        code = dpn[0][-1].upper() if dpn and dpn[0] else "?"
        self.proto_num = code
        self.hdr_len = 8 if code in "79" else 3
        self.can11 = code in "68"
        dp = self.at("ATDP")
        self.proto_name = dp[0] if dp else "?"

    def connect_car(self, protocol="6"):
        """First request to the car. Returns the 0100 reply. Raises CarSilent."""
        attempts = [protocol] if protocol == "0" else [protocol, "0"]
        rep = None
        for i, proto in enumerate(attempts):
            if i:
                self.at("ATSP" + proto)
            rep = self.obd("0100", timeout=20.0)
            self._read_protocol()
            rep = parse_reply(rep.lines, self.hdr_len)
            if rep.positive(0x01):
                break
        if not rep.positive(0x01):
            hexish = [s for s in rep.status if set(s.replace(" ", "").upper()) <= _HEX]
            if hexish:
                sys.exit("The adapter answers without CAN headers (it ignores ATH1). This clone\n"
                         "is not supported - an ELM327 v1.4+/STN-based adapter is needed.")
            raise CarSilent(", ".join(rep.status) or "no answer")
        if self.proto_num not in "6789":
            sys.exit(f"Protocol {self.proto_name} is not CAN. This tool needs a CAN car "
                     f"(the 2016 Mustang is CAN 11-bit 500k).")
        return rep

    def volts(self):
        m = re.search(r"(\d+(?:\.\d+)?)\s*V", " ".join(self.at("ATRV")), re.I)
        return float(m.group(1)) if m else None

    def set_header(self, req_hdr):
        """req_hdr = physical address of one ECU (11-bit, e.g. '7E0'); None = broadcast 7DF."""
        if not self.can11:
            return
        target = req_hdr or "7DF"
        if target != self.header:
            self.at("ATSH" + target)
            self.header = target
        resp = resp_for(req_hdr) if req_hdr else None
        want_cra = resp if (resp and not ("7E8" <= resp <= "7EF")) else None
        if want_cra != self.cra:
            self.at("ATCRA" + (want_cra or ""))
            self.cra = want_cra

    def count_for(self, frames):
        """Response-count digit to append (lets the adapter return without waiting
        for its timeout), or None when it is not known to be safe."""
        if self.count_mode == "messages":
            return 1
        if self.count_mode == "frames":
            return frames if frames <= 15 else None
        if self.count_mode == "sf":
            return 1 if frames == 1 else None
        return None


def resp_for(req_hdr):
    return f"{int(req_hdr, 16) + 8:03X}"


def req_for(resp_hdr):
    if len(resp_hdr) != 3:
        return None
    return f"{int(resp_hdr, 16) - 8:03X}"


# =============================================================================
# Standard OBD-II PIDs (SAE J1979 / ISO 15031-5). Each PID -> description and
# decoded columns. Raw bytes of every PID are logged regardless, so a PID
# without a decoder here is still captured.
# =============================================================================
def _pct(a):
    return a * 100.0 / 255.0


def _trim(a):
    return (a - 128) * 100.0 / 128.0


def _u16(d, i=0):
    return (d[i] << 8) | d[i + 1]


def _s16(d, i=0):
    v = _u16(d, i)
    return v - 0x10000 if v & 0x8000 else v


def dtc_str(a, b):
    return f"{'PCBU'[a >> 6]}{(a >> 4) & 3}{a & 0xF:X}{b >> 4:X}{b & 0xF:X}"


PIDS = {}


def _pid(pid, desc, *cols):
    PIDS[pid] = (desc, cols)


O2_LOC = ("B1S1", "B1S2", "B1S3", "B1S4", "B2S1", "B2S2", "B2S3", "B2S4")

_pid(0x01, "Monitor status since DTCs cleared (MIL, DTC count)",
     ("MIL", lambda d: d[0] >> 7), ("DtcCount", lambda d: d[0] & 0x7F))
_pid(0x02, "DTC that caused freeze frame",
     ("FreezeDTC", lambda d: dtc_str(d[0], d[1]) if (d[0] or d[1]) else "none"))
_pid(0x03, "Fuel system status (1=open loop cold, 2=closed loop, 4=open loop load/decel, "
           "8=open loop fault, 16=closed loop fault)",
     ("FuelSys1", lambda d: d[0]), ("FuelSys2", lambda d: d[1]))
_pid(0x04, "Calculated engine load", ("Load_pct", lambda d: _pct(d[0])))
_pid(0x05, "Engine coolant temperature", ("Coolant_C", lambda d: d[0] - 40))
_pid(0x06, "Short term fuel trim bank 1", ("STFT_B1_pct", lambda d: _trim(d[0])))
_pid(0x07, "Long term fuel trim bank 1", ("LTFT_B1_pct", lambda d: _trim(d[0])))
_pid(0x08, "Short term fuel trim bank 2", ("STFT_B2_pct", lambda d: _trim(d[0])))
_pid(0x09, "Long term fuel trim bank 2", ("LTFT_B2_pct", lambda d: _trim(d[0])))
_pid(0x0A, "Fuel pressure (gauge)", ("FuelPress_kPa", lambda d: d[0] * 3))
_pid(0x0B, "Intake manifold absolute pressure", ("MAP_kPa", lambda d: d[0]))
_pid(0x0C, "Engine speed", ("Rpm", lambda d: _u16(d) / 4))
_pid(0x0D, "Vehicle speed", ("Speed_kmh", lambda d: d[0]))
_pid(0x0E, "Ignition timing advance (cyl 1)", ("Timing_deg", lambda d: d[0] / 2 - 64))
_pid(0x0F, "Intake air temperature", ("IAT_C", lambda d: d[0] - 40))
_pid(0x10, "Mass air flow", ("MAF_gs", lambda d: _u16(d) / 100))
_pid(0x11, "Absolute throttle position", ("Throttle_pct", lambda d: _pct(d[0])))
_pid(0x12, "Commanded secondary air status", ("SecAir", lambda d: d[0]))
_pid(0x13, "O2 sensors present (2-bank layout)", ("O2Present", lambda d: d[0]))
for _i, _s in enumerate(O2_LOC):
    _pid(0x14 + _i, f"O2 sensor {_s} narrowband (voltage, trim)",
         (f"O2_{_s}_V", lambda d: d[0] / 200),
         (f"O2_{_s}_Trim_pct", lambda d: None if d[1] == 0xFF else _trim(d[1])))
_pid(0x1C, "OBD standard the vehicle conforms to", ("ObdStandard", lambda d: d[0]))
_pid(0x1D, "O2 sensors present (4-bank layout)", ("O2Present4", lambda d: d[0]))
_pid(0x1E, "Auxiliary input status (PTO)", ("AuxInput", lambda d: d[0] & 1))
_pid(0x1F, "Run time since engine start", ("RunTime_s", lambda d: _u16(d)))
_pid(0x21, "Distance traveled with MIL on", ("DistMIL_km", lambda d: _u16(d)))
_pid(0x22, "Fuel rail pressure (relative to manifold)", ("FuelRailRel_kPa", lambda d: _u16(d) * 0.079))
_pid(0x23, "Fuel rail gauge pressure", ("FuelRailGauge_kPa", lambda d: _u16(d) * 10))
for _i, _s in enumerate(O2_LOC):
    _pid(0x24 + _i, f"O2 sensor {_s} wide range (lambda, voltage)",
         (f"Lambda_{_s}", lambda d: _u16(d) * 2 / 65536),
         (f"O2_{_s}_WR_V", lambda d: _u16(d, 2) * 8 / 65536))
_pid(0x2C, "Commanded EGR", ("CmdEGR_pct", lambda d: _pct(d[0])))
_pid(0x2D, "EGR error", ("EGRError_pct", lambda d: _trim(d[0])))
_pid(0x2E, "Commanded evaporative purge", ("CmdPurge_pct", lambda d: _pct(d[0])))
_pid(0x2F, "Fuel tank level input", ("FuelLevel_pct", lambda d: _pct(d[0])))
_pid(0x30, "Warm-ups since codes cleared", ("WarmupsSinceClear", lambda d: d[0]))
_pid(0x31, "Distance traveled since codes cleared", ("DistSinceClear_km", lambda d: _u16(d)))
_pid(0x32, "Evap system vapor pressure", ("EvapPress_Pa", lambda d: _s16(d) / 4))
_pid(0x33, "Absolute barometric pressure", ("Baro_kPa", lambda d: d[0]))
for _i, _s in enumerate(O2_LOC):
    _pid(0x34 + _i, f"O2 sensor {_s} wide range (lambda, current)",
         (f"Lambda_{_s}", lambda d: _u16(d) * 2 / 65536),
         (f"O2_{_s}_mA", lambda d: _u16(d, 2) / 256 - 128))
for _i, _s in enumerate(("B1S1", "B2S1", "B1S2", "B2S2")):
    _pid(0x3C + _i, f"Catalyst temperature {_s}", (f"CatTemp_{_s}_C", lambda d: _u16(d) / 10 - 40))
_pid(0x41, "Monitor status this drive cycle (bitfield, raw)")
_pid(0x42, "Control module voltage", ("ModuleVolts_V", lambda d: _u16(d) / 1000))
_pid(0x43, "Absolute load value", ("AbsLoad_pct", lambda d: _u16(d) * 100 / 255))
_pid(0x44, "Commanded equivalence ratio (lambda)", ("CmdLambda", lambda d: _u16(d) * 2 / 65536))
_pid(0x45, "Relative throttle position", ("RelThrottle_pct", lambda d: _pct(d[0])))
_pid(0x46, "Ambient air temperature", ("Ambient_C", lambda d: d[0] - 40))
_pid(0x47, "Absolute throttle position B", ("ThrottleB_pct", lambda d: _pct(d[0])))
_pid(0x48, "Absolute throttle position C", ("ThrottleC_pct", lambda d: _pct(d[0])))
_pid(0x49, "Accelerator pedal position D", ("PedalD_pct", lambda d: _pct(d[0])))
_pid(0x4A, "Accelerator pedal position E", ("PedalE_pct", lambda d: _pct(d[0])))
_pid(0x4B, "Accelerator pedal position F", ("PedalF_pct", lambda d: _pct(d[0])))
_pid(0x4C, "Commanded throttle actuator", ("CmdThrottle_pct", lambda d: _pct(d[0])))
_pid(0x4D, "Time run with MIL on", ("TimeMIL_min", lambda d: _u16(d)))
_pid(0x4E, "Time since trouble codes cleared", ("TimeSinceClear_min", lambda d: _u16(d)))
_pid(0x4F, "Maximum values: lambda, O2 voltage, O2 current, MAP",
     ("MaxLambda", lambda d: d[0]), ("MaxO2_V", lambda d: d[1]),
     ("MaxO2_mA", lambda d: d[2]), ("MaxMAP_kPa", lambda d: d[3] * 10))
_pid(0x50, "Maximum MAF value", ("MaxMAF_gs", lambda d: d[0] * 10))
_pid(0x51, "Fuel type", ("FuelType", lambda d: d[0]))
_pid(0x52, "Ethanol fuel %", ("Ethanol_pct", lambda d: _pct(d[0])))
_pid(0x53, "Absolute evap system vapor pressure", ("EvapAbs_kPa", lambda d: _u16(d) / 200))
_pid(0x54, "Evap system vapor pressure (wide)", ("EvapPress2_Pa", lambda d: _u16(d) - 32767))
_pid(0x55, "Short term secondary O2 trim bank 1", ("STFT2_B1_pct", lambda d: _trim(d[0])))
_pid(0x56, "Long term secondary O2 trim bank 1", ("LTFT2_B1_pct", lambda d: _trim(d[0])))
_pid(0x57, "Short term secondary O2 trim bank 2", ("STFT2_B2_pct", lambda d: _trim(d[0])))
_pid(0x58, "Long term secondary O2 trim bank 2", ("LTFT2_B2_pct", lambda d: _trim(d[0])))
_pid(0x59, "Fuel rail absolute pressure", ("FuelRailAbs_kPa", lambda d: _u16(d) * 10))
_pid(0x5A, "Relative accelerator pedal position", ("RelPedal_pct", lambda d: _pct(d[0])))
_pid(0x5B, "Hybrid battery pack remaining life", ("HybridBatt_pct", lambda d: _pct(d[0])))
_pid(0x5C, "Engine oil temperature", ("OilTemp_C", lambda d: d[0] - 40))
_pid(0x5D, "Fuel injection timing", ("InjTiming_deg", lambda d: _u16(d) / 128 - 210))
_pid(0x5E, "Engine fuel rate", ("FuelRate_Lh", lambda d: _u16(d) / 20))
_pid(0x5F, "Emission requirements", ("EmissionReq", lambda d: d[0]))
_pid(0x61, "Driver's demand engine torque", ("DemandTorque_pct", lambda d: d[0] - 125))
_pid(0x62, "Actual engine torque", ("ActualTorque_pct", lambda d: d[0] - 125))
_pid(0x63, "Engine reference torque", ("RefTorque_Nm", lambda d: _u16(d)))
_pid(0x64, "Engine percent torque data",
     ("TorqueIdle_pct", lambda d: d[0] - 125), ("TorquePt1_pct", lambda d: d[1] - 125),
     ("TorquePt2_pct", lambda d: d[2] - 125), ("TorquePt3_pct", lambda d: d[3] - 125),
     ("TorquePt4_pct", lambda d: d[4] - 125))
_pid(0x66, "Mass air flow sensors A/B",
     ("MAF_A_gs", lambda d: _u16(d, 1) / 32 if d[0] & 1 else None),
     ("MAF_B_gs", lambda d: _u16(d, 3) / 32 if d[0] & 2 else None))
_pid(0x67, "Engine coolant temperature sensors 1/2",
     ("ECT1_C", lambda d: d[1] - 40 if d[0] & 1 else None),
     ("ECT2_C", lambda d: d[2] - 40 if d[0] & 2 else None))
_pid(0x8E, "Engine friction percent torque", ("FrictionTorque_pct", lambda d: d[0] - 125))
_pid(0xA2, "Cylinder fuel rate", ("CylFuelRate_mg", lambda d: _u16(d) / 32))
_pid(0xA4, "Transmission actual gear",
     ("Gear", lambda d: d[1] >> 4), ("GearRatio", lambda d: _u16(d, 2) / 1000))
_pid(0xA6, "Odometer", ("Odometer_km", lambda d: (_u16(d) << 16 | _u16(d, 2)) / 10))

# Snapshot-only: constant for the whole drive, captured in the snapshot / log meta.
STATIC_PIDS = {0x13, 0x1C, 0x1D, 0x1E, 0x4F, 0x50, 0x51, 0x5F, 0x63, 0x64}
# Polled every cycle; everything else rotates through the slow tier.
FAST_PIDS = ({0x04, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x10, 0x11, 0x22,
              0x23, 0x43, 0x44, 0x45, 0x47, 0x48, 0x49, 0x4A, 0x4B, 0x4C, 0x59, 0x5A, 0x5E,
              0x61, 0x62, 0x66, 0xA2, 0xA4}
             | set(range(0x14, 0x1C)) | set(range(0x24, 0x2C)) | set(range(0x34, 0x3C)))


def decode_pid(pid, data):
    """-> [(column, value)] for a known PID; value None when not applicable."""
    out = []
    for col, fn in PIDS.get(pid, ("", ()))[1]:
        try:
            v = fn(data)
        except (IndexError, ZeroDivisionError):
            v = None
        if isinstance(v, float):
            v = round(v, 4)
        out.append((col, v))
    return out


def split_mode01(msg, lengths):
    """Walk a (multi-PID) 0x41 response using per-PID data lengths -> {pid: bytes}."""
    out = {}
    if not msg or msg[0] != 0x41:
        return out
    i = 1
    while i < len(msg):
        pid = msg[i]
        n = lengths.get(pid)
        if n is None or i + 1 + n > len(msg):
            break
        out[pid] = bytes(msg[i + 1:i + 1 + n])
        i += 1 + n
    return out


def fmt(v):
    if v is None:
        return ""
    if isinstance(v, float):
        s = f"{v:.4f}".rstrip("0").rstrip(".")
        return "0" if s in ("-0", "") else s
    return str(v)


def ascii_of(data):
    return bytes(b for b in data if 32 <= b < 127).decode("ascii").strip()


# =============================================================================
# Enhanced data (Mode 22 DIDs) - tiny safe expression language for decodes:
# A..Z = data bytes, + - * / // % & | ^ << >>, and S8() U16() S16() U24() U32().
# =============================================================================
_EXPR_FUNCS = {
    "S8": lambda a: a - 256 if a > 127 else a,
    "U16": lambda a, b: (a << 8) | b,
    "S16": lambda a, b: ((a << 8) | b) - 65536 if a > 127 else (a << 8) | b,
    "U24": lambda a, b, c: (a << 16) | (b << 8) | c,
    "U32": lambda a, b, c, d: (a << 24) | (b << 16) | (c << 8) | d,
}
_EXPR_NODES = (ast.Expression, ast.BinOp, ast.UnaryOp, ast.Constant, ast.Name, ast.Load,
               ast.Call, ast.Add, ast.Sub, ast.Mult, ast.Div, ast.FloorDiv, ast.Mod, ast.USub,
               ast.UAdd, ast.BitAnd, ast.BitOr, ast.BitXor, ast.LShift, ast.RShift)


def compile_expr(expr):
    tree = ast.parse(expr, mode="eval")
    for node in ast.walk(tree):
        if not isinstance(node, _EXPR_NODES):
            raise ValueError(f"not allowed in expression: {type(node).__name__}")
        if isinstance(node, ast.Name) and not (re.fullmatch(r"[A-Z]", node.id)
                                               or node.id in _EXPR_FUNCS):
            raise ValueError(f"unknown name {node.id!r} (use A..Z and {', '.join(_EXPR_FUNCS)})")
        if isinstance(node, ast.Call) and (not isinstance(node.func, ast.Name)
                                           or node.func.id not in _EXPR_FUNCS or node.keywords):
            raise ValueError("only S8/U16/S16/U24/U32 calls are allowed")
        if isinstance(node, ast.Constant) and (isinstance(node.value, bool)
                                               or not isinstance(node.value, (int, float))):
            raise ValueError("only numeric constants are allowed")
    code = compile(tree, "<expr>", "eval")

    def fn(data):
        env = dict(_EXPR_FUNCS)
        env.update({chr(65 + i): b for i, b in enumerate(data[:26])})
        try:
            v = eval(code, {"__builtins__": {}}, env)  # validated AST: arithmetic only
        except (NameError, ZeroDivisionError, TypeError):
            return None
        return round(v, 4) if isinstance(v, float) else v
    return fn


def load_profile(path):
    prof = {"vehicle": "", "dids": [], "ident_dids": []}
    if not path or path.lower() == "none":
        return prof
    if not os.path.exists(path):
        if os.path.abspath(path) == DEFAULT_PROFILE:
            return prof
        sys.exit(f"profile not found: {path}")
    with open(path, encoding="utf-8") as f:
        raw = json.load(f)
    prof["vehicle"] = raw.get("vehicle", "")
    for key in ("dids", "ident_dids"):
        for e in raw.get(key, []):
            ecu, did = str(e.get("ecu", "7E0")).upper(), str(e["did"]).upper()
            if not re.fullmatch(r"[0-9A-F]{3}", ecu) or not re.fullmatch(r"[0-9A-F]{4}", did):
                sys.exit(f"profile {path}: bad ecu/did in {e}")
            name = e.get("name") or f"DID_{did}"
            if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*", name):
                sys.exit(f"profile {path}: bad name {name!r}")
            d = {"ecu": ecu, "did": int(did, 16), "name": name, "expr": e.get("expr"),
                 "status": e.get("status", ""), "fn": None}
            if d["expr"]:
                try:
                    d["fn"] = compile_expr(d["expr"])
                except (SyntaxError, ValueError) as ex:
                    sys.exit(f"profile {path}: {name}: bad expr {d['expr']!r}: {ex}")
            prof[key].append(d)
    return prof


def load_extra_dids(path):
    """DIDs from a --scan-dids result CSV (status ok) -> raw-only profile entries."""
    out = []
    with open(path, newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            if row.get("status") == "ok":
                did = int(row["did"], 16)
                out.append({"ecu": row["ecu"].upper(), "did": did, "name": f"DID_{did:04X}",
                            "expr": None, "status": "from scan", "fn": None})
    return out


# =============================================================================
# Discovery and reads
# =============================================================================
def discover_bitmap(elm, service, extra=""):
    """Walk the 'supported' bitmaps of service 01/02/06/09 by broadcast.
    -> {response header: sorted list of supported ids (bitmap ids excluded)}"""
    elm.set_header(None)
    sup = {}
    base = 0
    off = 3 if extra else 2
    while True:
        rep = elm.obd(f"{service:02X}{base:02X}{extra}")
        for hdr, m in rep.positive(service):
            if len(m) >= off + 4 and m[1] == base:
                bits = int.from_bytes(bytes(m[off:off + 4]), "big")
                s = sup.setdefault(hdr, set())
                for i in range(32):
                    if bits >> (31 - i) & 1:
                        s.add(base + i + 1)
        nxt = base + 0x20
        if nxt > 0xE0 or not any(nxt in s for s in sup.values()):
            break
        base = nxt
    return {h: sorted(p for p in s if p % 0x20) for h, s in sorted(sup.items())}


def target(elm, resp_hdr):
    """Address one ECU physically when possible; returns the response header to accept."""
    elm.set_header(req_for(resp_hdr) if elm.can11 else None)
    return resp_hdr


def probe_pids(elm, resp_hdr, pids):
    """Query each PID alone -> (lengths {pid: n}, samples {pid: bytes})."""
    target(elm, resp_hdr)
    lengths, samples = {}, {}
    for pid in pids:
        rep = elm.obd(f"01{pid:02X}")
        for hdr, m in rep.positive(0x01):
            if hdr == resp_hdr and len(m) >= 2 and m[1] == pid:
                lengths[pid] = len(m) - 2
                samples[pid] = bytes(m[2:])
                break
    return lengths, samples


def multi_ok(elm, resp_hdr, pids, lengths):
    """Does this ECU answer multi-PID requests (up to 6 PIDs per request)?"""
    sample = [p for p in pids if p in lengths][:6]
    if len(sample) < 2:
        return False
    target(elm, resp_hdr)
    rep = elm.obd("01" + "".join(f"{p:02X}" for p in sample))
    for hdr, m in rep.positive(0x01):
        if hdr == resp_hdr and set(sample) <= set(split_mode01(m, lengths)):
            return True
    return False


def calibrate_count(elm, resp_hdr):
    """Find out if/how the adapter honours the trailing response-count digit,
    which saves the adapter's ~100-200 ms 'wait for more ECUs' timeout per request."""
    elm.count_mode = None
    if not elm.can11:
        return None
    target(elm, resp_hdr)
    rep = elm.obd("0100", count=1)
    if not [m for h, m in rep.positive(0x01) if h == resp_hdr] or rep.partial:
        return None
    ref = [m for h, m in elm.obd("0902").positive(0x09) if h == resp_hdr]
    if not ref or len(ref[0]) <= 7:
        elm.count_mode = "sf"
        return elm.count_mode
    one = [m for h, m in elm.obd("0902", count=1).positive(0x09) if h == resp_hdr]
    if one and one[0] == ref[0]:
        elm.count_mode = "messages"
        return elm.count_mode
    n = frames_for(len(ref[0]))
    if n <= 15:
        many = [m for h, m in elm.obd("0902", count=n).positive(0x09) if h == resp_hdr]
        if many and many[0] == ref[0]:
            elm.count_mode = "frames"
            return elm.count_mode
    elm.count_mode = "sf"
    return elm.count_mode


def read_did(elm, ecu_req, did, frames=None):
    """-> ('ok', data) | ('nrc', code) | ('none', status text)."""
    elm.set_header(ecu_req if elm.can11 else None)
    resp = resp_for(ecu_req)
    count = elm.count_for(frames) if frames else (1 if elm.count_mode else None)
    nrcs, rep = [], None
    for attempt in range(2):
        rep = elm.obd(f"22{did:04X}", count=count, timeout=6.0)
        for hdr, m in rep.positive(0x22):
            if hdr == resp and len(m) >= 3 and ((m[1] << 8) | m[2]) == did:
                return "ok", bytes(m[3:])
        nrcs = [c for h, c in rep.negative(0x22) if h == resp]
        final = [c for c in nrcs if c != 0x78]
        if final:
            return "nrc", final[-1]
        if attempt == 0 and (nrcs or resp in rep.partial):
            # 0x78 "response pending", or a multi-frame answer cut short by the
            # response-count digit: ask once more without the digit.
            count = None
            continue
        break
    if nrcs:
        return "nrc", 0x78
    return "none", ", ".join(rep.status) or "no answer"


NRC_TEXT = {0x10: "generalReject", 0x11: "serviceNotSupported", 0x12: "subFunctionNotSupported",
            0x13: "incorrectMessageLength", 0x14: "responseTooLong", 0x22: "conditionsNotCorrect",
            0x31: "requestOutOfRange", 0x33: "securityAccessDenied", 0x78: "responsePending",
            0x7E: "notSupportedInActiveSession", 0x7F: "serviceNotSupportedInActiveSession"}


def parse_dtcs(msg):
    data = msg[1:]
    if len(data) % 2 == 1:      # CAN: leading DTC-count byte
        data = data[1:]
    return [dtc_str(data[i], data[i + 1]) for i in range(0, len(data) - 1, 2)
            if data[i] or data[i + 1]]


def read_dtcs(elm, service):
    elm.set_header(None)
    out = {}
    for hdr, m in elm.obd(f"{service:02X}", timeout=6.0).positive(service):
        out.setdefault(hdr, []).extend(parse_dtcs(m))
    return out


def decode_mode09(info, m):
    data = bytes(m[2:])
    if info in (0x02, 0x0A):
        return ascii_of(data[1:])
    if info == 0x04:
        n = data[0] if data else 0
        return [ascii_of(data[1 + 16 * i:17 + 16 * i]) for i in range(n)]
    if info == 0x06:
        n = data[0] if data else 0
        return [data[1 + 4 * i:5 + 4 * i].hex().upper() for i in range(n)]
    if info in (0x08, 0x0B):
        n = data[0] if data else 0
        return [_u16(data, 1 + 2 * i) for i in range(n) if 2 + 2 * i < len(data)]
    return data.hex().upper()


def read_vin(elm, resp_hdr):
    target(elm, resp_hdr)
    for hdr, m in elm.obd("0902", timeout=6.0).positive(0x09):
        if hdr == resp_hdr and len(m) > 3:
            return decode_mode09(0x02, m)
    return None


# Mode 06 (on-board monitor test results): unit-and-scaling IDs (SAE J1979 App. E).
UASID = {0x01: (1, "", False), 0x02: (0.1, "", False), 0x03: (0.01, "", False),
         0x04: (0.001, "", False), 0x05: (0.0000305, "", False), 0x06: (0.000305, "", False),
         0x07: (0.25, "rpm", False), 0x08: (0.01, "km/h", False), 0x09: (1, "km/h", False),
         0x0A: (0.000122, "V", False), 0x0B: (0.001, "V", False), 0x0C: (0.01, "V", False),
         0x0D: (0.00390625, "mA", False), 0x0E: (0.001, "A", False), 0x0F: (0.01, "A", False),
         0x10: (1, "ms", False), 0x11: (100, "ms", False), 0x12: (1, "s", False),
         0x13: (1, "mOhm", False), 0x14: (1, "Ohm", False), 0x15: (1, "kOhm", False),
         0x16: (0.1, "C", False), 0x17: (0.01, "kPa", False), 0x18: (0.0117, "kPa", False),
         0x19: (0.079, "kPa", False), 0x1A: (1, "kPa", False), 0x1B: (10, "kPa", False),
         0x1C: (0.01, "deg", False), 0x1D: (0.5, "deg", False), 0x1E: (0.0000305, "lambda", False),
         0x1F: (0.05, "AFR", False), 0x20: (0.0039062, "ratio", False), 0x21: (1, "mHz", False),
         0x22: (1, "Hz", False), 0x23: (1, "kHz", False), 0x24: (1, "counts", False),
         0x25: (1, "km", False), 0x26: (0.1, "mV/ms", False), 0x27: (0.01, "g/s", False),
         0x28: (1, "g/s", False), 0x29: (0.25, "Pa/s", False), 0x2A: (0.001, "kg/h", False),
         0x2B: (1, "switches", False), 0x2C: (0.01, "g/cyl", False), 0x2D: (0.01, "mg/stroke", False),
         0x2E: (1, "bool", False), 0x2F: (0.01, "%", False),
         0x81: (1, "", True), 0x82: (0.1, "", True), 0x83: (0.01, "", True),
         0x84: (0.001, "", True), 0x85: (0.0000305, "", True), 0x86: (0.000305, "", True),
         0x8A: (0.000122, "V", True), 0x8B: (0.001, "V", True), 0x8C: (0.01, "V", True),
         0x90: (1, "ms", True), 0x96: (0.1, "C", True), 0x9C: (0.01, "deg", True),
         0xAF: (0.01, "%", True), 0xFE: (0.25, "Pa", True)}
MID_NAMES = {0x21: "Catalyst bank 1", 0x22: "Catalyst bank 2", 0x31: "EGR / VVT",
             0x39: "EVAP 0.150 in leak", 0x3A: "EVAP 0.090 in leak", 0x3B: "EVAP 0.040 in leak",
             0x3C: "EVAP 0.020 in leak", 0x3D: "Purge flow", 0x71: "Secondary air 1",
             0x81: "Fuel system bank 1", 0x82: "Fuel system bank 2",
             0xA1: "Misfire general", 0x01: "O2 sensor B1S1", 0x02: "O2 sensor B1S2",
             0x03: "O2 sensor B1S3", 0x05: "O2 sensor B2S1", 0x06: "O2 sensor B2S2",
             0x07: "O2 sensor B2S3", 0x41: "O2 heater B1S1", 0x42: "O2 heater B1S2",
             0x45: "O2 heater B2S1", 0x46: "O2 heater B2S2"}
MID_NAMES.update({0xA2 + i: f"Misfire cylinder {i + 1}" for i in range(12)})
MISFIRE_TIDS = {0x0B: "EWMA misfire count (last 10 driving cycles)",
                0x0C: "misfire count (last/current driving cycle)"}


def parse_mode06(m):
    recs = []
    data = bytes(m[1:])
    for i in range(0, len(data) - 8, 9):
        mid, tid, uas = data[i], data[i + 1], data[i + 2]
        raw = [_u16(data, i + 3), _u16(data, i + 5), _u16(data, i + 7)]
        scale, unit, signed = UASID.get(uas, (None, "", False))
        if signed:
            raw = [v - 0x10000 if v & 0x8000 else v for v in raw]
        if scale is not None:
            vals = [round(v * scale + (-40 if uas == 0x16 else 0), 6) for v in raw]
        else:
            vals = raw
        recs.append({"mid": f"{mid:02X}", "mid_name": MID_NAMES.get(mid, ""),
                     "tid": f"{tid:02X}",
                     "tid_name": MISFIRE_TIDS.get(tid, "") if 0xA1 <= mid <= 0xAD else "",
                     "uasid": f"{uas:02X}", "unit": unit, "scaled": scale is not None,
                     "value": vals[0], "min": vals[1], "max": vals[2],
                     "pass": vals[1] <= vals[0] <= vals[2]})
    return recs


# =============================================================================
# Session: connect / reconnect handling around one adapter
# =============================================================================
class Session:
    def __init__(self, args):
        self.args = args
        self.elm = None
        self.count_mode = None
        self.trace = None
        if getattr(args, "trace", False):
            os.makedirs(LOG_DIR, exist_ok=True)
            p = os.path.join(LOG_DIR, f"elm_trace_{stamp()}.txt")
            self.trace = open(p, "w", buffering=1, encoding="utf-8")
            print(f"Tracing adapter traffic to {p}")

    def open(self):
        t = make_transport(self.args)
        t.open()
        elm = Elm327(t, self.trace)
        try:
            elm.init(self.args.protocol)
            elm.connect_car(self.args.protocol)
        except BaseException:
            t.close()
            raise
        elm.count_mode = self.count_mode
        self.elm = elm

    def close(self):
        if self.elm:
            self.elm.t.close()
            self.elm = None

    def connect(self, wait_for_car=False, attempts=5):
        tries = 0
        while True:
            try:
                self.open()
                return self.elm
            except CarSilent as e:
                self.close()
                if not wait_for_car:
                    sys.exit(f"Adapter OK, but the car did not answer ({e}).\n"
                             "Turn the ignition ON (engine running is best) and retry.")
                print(f"  adapter OK, car silent ({e}) - waiting for ignition ON...")
                time.sleep(3)
            except (LinkError, OSError) as e:
                self.close()
                tries += 1
                if tries >= attempts:
                    sys.exit(f"Could not talk to the adapter: {e}\n"
                             "Check: adapter plugged in (OBD port under the dash, left of the\n"
                             "steering column), paired, no other app (Torque, FORScan, ...) connected.")
                print(f"  connecting... ({tries}/{attempts}: {e})")
                time.sleep(2)

    def reconnect(self):
        self.close()
        delay = RECONNECT_DELAY
        while True:
            print(f"\n  link lost - reconnecting in {delay}s (Ctrl+C to stop)...")
            time.sleep(delay)
            try:
                self.open()
                print("  reconnected.")
                return self.elm
            except (LinkError, CarSilent, OSError) as e:
                self.close()
                print(f"  not yet: {e}")
                delay = min(delay * 2, 15)


def make_transport(args):
    if args.demo:
        factory = getattr(args, "sim_factory", None)   # test hook
        if factory:
            return factory()
        sys.path.insert(0, HERE)
        from elm_sim import SimTransport
        return SimTransport(latency=0.015)
    if args.tcp:
        host, _, port = args.tcp.rpartition(":")
        return SocketTransport("tcp", (host or "192.168.0.10", int(port or 35000)))
    if args.rfcomm:
        return SocketTransport("rfcomm", (args.rfcomm, args.channel))
    if args.ble is not None:
        return BleTransport(args.ble or None)
    return SerialTransport(args.port, args.baud)


def serial_candidates():
    """-> [(port, description, is_bluetooth)], outgoing Bluetooth ports first."""
    if list_ports is None:
        sys.exit("pyserial missing - run:  py -m pip install pyserial")
    ranked = []
    for p in list_ports.comports():
        hw = (p.hwid or "").upper()
        blob = f"{p.device} {hw} {p.description or ''}".upper()
        is_bt = any(k in blob for k in ("BTHENUM", "BLUETOOTH", "RFCOMM", "OBD", "ELM", "VLINK"))
        if is_bt:
            mac = re.search(r"&([0-9A-F]{12})_", hw)
            outgoing = not mac or mac.group(1) != "0" * 12   # incoming SPP ports carry 000000000000
            ranked.append((0 if outgoing else 1, p.device, p.description or "", True))
        elif any(k in blob for k in ("USB", "FTDI", "CH340", "PL2303", "CP210", "ACM")):
            ranked.append((2, p.device, p.description or "", False))
    return [(d, desc, bt) for _, d, desc, bt in sorted(ranked)]


def autodetect_port(args):
    cands = [c for c in serial_candidates() if c[2]] or serial_candidates()
    if not cands:
        sys.exit("No Bluetooth/USB serial port found.\n"
                 "Pair the OBD adapter first (Windows: Settings > Bluetooth > Add device; the\n"
                 "PIN is usually 1234 or 0000). Windows then creates 'Standard Serial over\n"
                 "Bluetooth link (COMx)' ports. Rerun, or pass --port COMx directly.\n"
                 "BLE-only adapters (no PIN, no COM port) need --ble instead.")
    for dev, desc, _ in cands:
        print(f"  trying {dev} ({desc}) ...")
        t = SerialTransport(dev, args.baud)
        try:
            t.open()
            lines = Elm327(t).at("ATI", 8.0)
            if any(re.search(r"ELM|STN|OBD|v\d", ln, re.I) for ln in lines):
                print(f"  -> OBD adapter on {dev}: {' '.join(lines)}")
                return dev
        except (LinkError, OSError) as e:
            print(f"     no ({e})")
        finally:
            t.close()
    sys.exit("No port answered like an ELM327 adapter. Pass --port COMx explicitly\n"
             "(see --list), and make sure no other app is connected to the adapter.")


# =============================================================================
# Modes
# =============================================================================
def stamp():
    return datetime.datetime.now().strftime("%Y%m%d_%H%M%S")


def describe_adapter(elm):
    return {"transport": elm.t.name, "adapter": elm.version, "protocol": elm.proto_name,
            "protocol_number": elm.proto_num, "count_mode": elm.count_mode}


def cmd_list(args):
    if list_ports is not None:
        ports = list(list_ports.comports())
        print("Serial ports:" if ports else "No serial ports.")
        cands = {d for d, _, bt in serial_candidates() if bt}
        for p in ports:
            tag = "  <- Bluetooth" if p.device in cands else ""
            print(f"  {p.device:<14} {p.description or ''}{tag}")
    else:
        print("(pyserial not installed - cannot list serial ports)")
    if args.ble is not None:
        import asyncio
        if importlib.util.find_spec("bleak") is None:
            sys.exit("BLE scan needs:  py -m pip install bleak")
        print("Scanning BLE (8 s)...")
        for addr, name in asyncio.run(BleTransport.scan()):
            hint = "  <- looks like OBD" if any(h in name.upper() for h in BleTransport.NAME_HINTS) else ""
            print(f"  {addr}  {name}{hint}")


def cmd_handshake(args):
    sess = Session(args)
    elm = sess.connect()
    print("Handshake:")
    print(f"  link:      {elm.t.name}")
    print(f"  adapter:   {elm.version}")
    v = elm.volts()
    print(f"  battery:   {v} V" + ("  (engine running)" if v and v > 13.2 else ""))
    print(f"  protocol:  {elm.proto_name}")
    sup = discover_bitmap(elm, 0x01)
    for hdr, pids in sup.items():
        print(f"  ECU {hdr}:   {len(pids)} live PIDs supported")
    primary = pick_primary(sup)
    if primary:
        print(f"  VIN:       {read_vin(elm, primary) or '?'}")
    sess.close()


def pick_primary(sup):
    if not sup:
        return None
    return max(sup, key=lambda h: (h == "7E8", len(sup[h])))


def cmd_snapshot(args, profile):
    sess = Session(args)
    elm = sess.connect()
    print(f"Connected: {elm.version} via {elm.t.name}, {elm.proto_name}")
    snap = {"created": datetime.datetime.now().isoformat(timespec="seconds"),
            "tool": f"mustang_obd.py {TOOL_VERSION}", "profile_vehicle": profile["vehicle"],
            "adapter": describe_adapter(elm), "battery_V": elm.volts(), "ecus": {}}
    print("  supported PIDs...")
    sup = discover_bitmap(elm, 0x01)
    primary = pick_primary(sup)
    snap["primary_ecu"] = primary
    for hdr, pids in sup.items():
        print(f"  ECU {hdr}: reading {len(pids)} live PIDs...")
        lengths, samples = probe_pids(elm, hdr, pids)
        live = {}
        for pid in pids:
            if pid in samples:
                live[f"{pid:02X}"] = {"desc": PIDS.get(pid, ("(no decoder - raw only)",))[0],
                                      "raw": samples[pid].hex().upper(),
                                      "values": dict(decode_pid(pid, samples[pid]))}
            else:
                live[f"{pid:02X}"] = {"desc": PIDS.get(pid, ("",))[0], "raw": None,
                                      "note": "advertised but no answer"}
        snap["ecus"][hdr] = {"mode01_supported": [f"{p:02X}" for p in pids], "live": live}

    print("  vehicle info (Mode 09)...")
    for hdr, infos in discover_bitmap(elm, 0x09).items():
        target(elm, hdr)
        info_out = {}
        for info in infos:
            for h, m in elm.obd(f"09{info:02X}", timeout=6.0).positive(0x09):
                if h == hdr and len(m) >= 2 and m[1] == info:
                    info_out[f"{info:02X}"] = decode_mode09(info, m)
        snap["ecus"].setdefault(hdr, {})["mode09"] = info_out
    names = {"02": "VIN", "04": "CALID", "06": "CVN", "08": "InUsePerf", "0A": "EcuName"}
    for hdr, e in snap["ecus"].items():
        for k, v in e.get("mode09", {}).items():
            if k in names:
                e[names[k]] = v

    print("  trouble codes (stored / pending / permanent)...")
    snap["dtcs"] = {"stored": read_dtcs(elm, 0x03), "pending": read_dtcs(elm, 0x07),
                    "permanent": read_dtcs(elm, 0x0A)}

    print("  freeze frame (Mode 02)...")
    for hdr, pids in discover_bitmap(elm, 0x02, extra="00").items():
        target(elm, hdr)
        ff = {}
        for pid in pids:
            for h, m in elm.obd(f"02{pid:02X}00").positive(0x02):
                if h == hdr and len(m) >= 3 and m[1] == pid:
                    d = bytes(m[3:])
                    ff[f"{pid:02X}"] = {"raw": d.hex().upper(), "values": dict(decode_pid(pid, d))}
        snap["ecus"].setdefault(hdr, {})["freeze_frame"] = ff

    print("  on-board monitor results (Mode 06, incl. per-cylinder misfire counts)...")
    for hdr, mids in discover_bitmap(elm, 0x06).items():
        target(elm, hdr)
        recs = []
        for mid in mids:
            for h, m in elm.obd(f"06{mid:02X}", timeout=6.0).positive(0x06):
                if h == hdr:
                    recs.extend(parse_mode06(m))
        snap["ecus"].setdefault(hdr, {})["mode06"] = recs

    print("  enhanced data (Mode 22, from profile)...")
    if elm.can11 and primary:
        calibrate_count(elm, primary)
    dids = {}
    for e in (profile["ident_dids"] + profile["dids"]) if elm.can11 else []:
        st, val = read_did(elm, e["ecu"], e["did"])
        key = f"{e['ecu']}:{e['did']:04X}"
        if st == "ok":
            dec = e["fn"](val) if e["fn"] else ascii_of(val)
            dids[key] = {"name": e["name"], "raw": val.hex().upper(), "value": dec,
                         "status": e["status"]}
        else:
            why = NRC_TEXT.get(val, f"0x{val:02X}") if st == "nrc" else val
            dids[key] = {"name": e["name"], "error": f"{st}: {why}"}
    snap["enhanced"] = dids
    sess.close()

    os.makedirs(LOG_DIR, exist_ok=True)
    base = os.path.join(LOG_DIR, f"mustang_snapshot_{stamp()}")
    with open(base + ".json", "w", encoding="utf-8") as f:
        json.dump(snap, f, indent=2)
    txt = render_snapshot(snap)
    with open(base + ".txt", "w", encoding="utf-8") as f:
        f.write(txt)
    print()
    print(txt)
    print(f"Saved: {base}.json\n       {base}.txt")


def render_snapshot(snap):
    L = [f"OBD snapshot {snap['created']}  ({snap['tool']})",
         f"Adapter: {snap['adapter']['adapter']} via {snap['adapter']['transport']}",
         f"Protocol: {snap['adapter']['protocol']}   Battery: {snap['battery_V']} V", ""]
    dt = snap["dtcs"]
    L.append("TROUBLE CODES")
    for kind in ("stored", "pending", "permanent"):
        codes = [f"{c} ({h})" for h, cs in dt[kind].items() for c in cs]
        L.append(f"  {kind:<10} {', '.join(codes) if codes else 'none'}")
    L.append("")
    for hdr, e in snap["ecus"].items():
        L.append(f"ECU {hdr}" + (f"  {e['EcuName']}" if e.get("EcuName") else "")
                 + ("  (primary)" if hdr == snap.get("primary_ecu") else ""))
        for k in ("VIN", "CALID", "CVN"):
            if e.get(k):
                L.append(f"  {k:<6} {e[k] if isinstance(e[k], str) else ', '.join(map(str, e[k]))}")
        if e.get("live"):
            L.append(f"  live data ({len(e['live'])} PIDs):")
            for pid, v in e["live"].items():
                vals = ", ".join(f"{k}={fmt(x)}" for k, x in v.get("values", {}).items())
                L.append(f"    {pid}  {v['desc'][:46]:<46} {vals or (v.get('raw') or v.get('note', ''))}")
        if e.get("freeze_frame"):
            L.append("  freeze frame:")
            for pid, v in e["freeze_frame"].items():
                vals = ", ".join(f"{k}={fmt(x)}" for k, x in v["values"].items())
                L.append(f"    {pid}  {vals or v['raw']}")
        mis = [r for r in e.get("mode06", []) if r["mid_name"].startswith("Misfire cyl")]
        if mis:
            L.append("  misfire counts (Mode 06):")
            for r in mis:
                L.append(f"    {r['mid_name']:<20} {r['tid_name'] or 'TID ' + r['tid']}: {fmt(r['value'])}")
        fails = [r for r in e.get("mode06", []) if not r["pass"]]
        if e.get("mode06"):
            L.append(f"  Mode 06 tests: {len(e['mode06'])} results, {len(fails)} outside limits"
                     + "".join(f"\n    FAIL {r['mid']}/{r['tid']} {r['mid_name']}: {fmt(r['value'])} "
                               f"not in [{fmt(r['min'])}, {fmt(r['max'])}] {r['unit']}" for r in fails))
        L.append("")
    if snap.get("enhanced"):
        L.append("ENHANCED (Mode 22)")
        for k, v in snap["enhanced"].items():
            L.append(f"  {k}  {v['name']:<24} " + (f"{v.get('value')}  raw {v['raw']}"
                                                   if "raw" in v else v["error"]))
        L.append("")
    return "\n".join(L)


# ----------------------------------------------------------------------------- logging
class Item:
    """One poll request in the logging loop."""

    def __init__(self, kind, ecu, pids=(), did=None, entry=None, frames=1):
        self.kind, self.ecu, self.pids, self.did, self.entry, self.frames = \
            kind, ecu, tuple(pids), did, entry, frames

    def __repr__(self):
        if self.kind == "pids":
            return f"{self.ecu}:01[{' '.join(f'{p:02X}' for p in self.pids)}]"
        if self.kind == "did":
            return f"{self.ecu}:22{self.did:04X}"
        return "ATRV"


def build_plan(elm, args, profile, extra_dids):
    print("Discovering supported data...")
    sup = discover_bitmap(elm, 0x01)
    if not sup:
        raise CarSilent("no ECU reports supported PIDs")
    primary = pick_primary(sup)
    order = [primary] + [h for h in sup if h != primary]
    wanted = None
    if args.pids:
        try:
            wanted = {int(p, 16) for p in re.split(r"[,\s]+", args.pids) if p}
        except ValueError:
            sys.exit(f"--pids: expected hex PIDs like 0C,0D,05 - got {args.pids!r}")
    lengths, statics, columns, fast, slow = {}, {}, [], [], []
    colmap = {}           # (ecu, pid) -> [(decoded column, csv name)]
    rawcol = {}           # (ecu, pid|"did") -> raw csv column
    used = set()
    covered = set()

    def add_col(name):
        n = name
        while n in used:
            n = n + "_"
        used.add(n)
        columns.append(n)
        return n

    for hdr in order:
        L, S = probe_pids(elm, hdr, sup[hdr])
        lengths[hdr] = L
        statics[hdr] = {f"{p:02X}": {"raw": S[p].hex().upper(), "values": dict(decode_pid(p, S[p]))}
                        for p in S if p in STATIC_PIDS}
        pids = [p for p in sup[hdr] if p in L and p not in STATIC_PIDS
                and (wanted is None or p in wanted)]
        if hdr != primary:
            pids = [p for p in pids if p not in covered]
        covered.update(pids)
        if not pids:
            continue
        chunk = 6 if (not args.no_multi and multi_ok(elm, hdr, pids, L)) else 1
        prefix = "" if hdr == primary else f"{hdr}_"
        for p in pids:
            colmap[(hdr, p)] = [(c, add_col(prefix + c)) for c, _ in PIDS.get(p, ("", ()))[1]]
        f_p = [p for p in pids if p in FAST_PIDS and hdr == primary]
        s_p = [p for p in pids if p not in f_p]
        for group, dest in ((f_p, fast), (s_p, slow)):
            for i in range(0, len(group), chunk):
                part = group[i:i + chunk]
                frames = frames_for(1 + sum(1 + L[p] for p in part))
                dest.append(Item("pids", hdr, part, frames=frames))

    if primary and elm.can11 and not args.no_count:
        mode = calibrate_count(elm, primary)
        print(f"  response-count shortcut: {mode or 'not supported by this adapter'}")

    dids = []
    if not args.pids:
        for e in profile["dids"] + extra_dids:
            st, val = read_did(elm, e["ecu"], e["did"])
            if st == "ok":
                dids.append(e)
                if e["fn"]:
                    colmap[(e["ecu"], e["did"])] = [(e["name"], add_col(e["name"]))]
                slow.append(Item("did", e["ecu"], did=e["did"], entry=e,
                                 frames=frames_for(3 + len(val))))
            else:
                why = NRC_TEXT.get(val, val) if st == "nrc" else val
                print(f"  enhanced {e['name']} ({e['ecu']}:22{e['did']:04X}) not available: {why}")
        slow.append(Item("atrv", None))
        colmap[("atrv", 0)] = [("AdapterVolts_V", add_col("AdapterVolts_V"))]

    # raw columns last (like the Jetronic logger): every polled PID / DID, hex
    for it in fast + slow:
        if it.kind == "pids":
            for p in it.pids:
                rawcol[(it.ecu, p)] = add_col(("" if it.ecu == primary else f"{it.ecu}_")
                                              + f"raw_01{p:02X}")
        elif it.kind == "did":
            rawcol[(it.ecu, it.did)] = add_col(f"raw_{it.ecu}_22{it.did:04X}")
    vin = read_vin(elm, primary) if primary else None
    return {"sup": sup, "primary": primary, "lengths": lengths, "statics": statics,
            "fast": fast, "slow": slow, "columns": columns, "colmap": colmap,
            "rawcol": rawcol, "dids": dids, "vin": vin}


def run_item(elm, it, plan, state):
    """Execute one poll item; update `state` (csv column -> text). -> True if answered."""
    if it.kind == "atrv":
        v = elm.volts()
        state[plan["colmap"][("atrv", 0)][0][1]] = fmt(v)
        return v is not None
    if it.kind == "did":
        st, val = read_did(elm, it.ecu, it.did, frames=it.frames)
        if st != "ok":
            return False
        state[plan["rawcol"][(it.ecu, it.did)]] = val.hex().upper()
        if it.entry["fn"]:
            state[plan["colmap"][(it.ecu, it.did)][0][1]] = fmt(it.entry["fn"](val))
        return True
    target(elm, it.ecu)
    req = "01" + "".join(f"{p:02X}" for p in it.pids)
    rep = elm.obd(req, count=elm.count_for(it.frames))
    got = {}
    for hdr, m in rep.positive(0x01):
        if hdr == it.ecu:
            got.update(split_mode01(m, plan["lengths"][it.ecu]))
    if rep.partial and elm.count_mode and not got:
        rep = elm.obd(req)  # truncated by the count digit: once more without it
        for hdr, m in rep.positive(0x01):
            if hdr == it.ecu:
                got.update(split_mode01(m, plan["lengths"][it.ecu]))
    for pid, data in got.items():
        if pid not in it.pids:
            continue
        state[plan["rawcol"][(it.ecu, pid)]] = data.hex().upper()
        dec = dict(decode_pid(pid, data))
        for col, csvname in plan["colmap"].get((it.ecu, pid), []):
            state[csvname] = fmt(dec.get(col))
    return bool(got)


STATUS_KEYS = [("Rpm", "{:.0f} rpm"), ("Speed_kmh", "{:.0f} km/h"), ("Coolant_C", "ECT {:.0f}C"),
               ("Load_pct", "load {:.0f}%"), ("STFT_B1_pct", "STFT {:+.1f}"),
               ("STFT_B2_pct", "/{:+.1f}"), ("LTFT_B1_pct", "LTFT {:+.1f}"),
               ("LTFT_B2_pct", "/{:+.1f}"), ("TransFluidTemp_C", "TFT {:.0f}C")]


def status_text(state):
    parts = []
    for key, pattern in STATUS_KEYS:
        try:
            parts.append(pattern.format(float(state[key])))
        except (KeyError, ValueError):
            continue
    return " ".join(parts).replace(" /", "/")


def cmd_log(args, profile):
    extra = load_extra_dids(args.extra_dids) if args.extra_dids else []
    sess = Session(args)
    elm = sess.connect(wait_for_car=True)
    print(f"Connected: {elm.version} via {elm.t.name}, {elm.proto_name}")
    plan = build_plan(elm, args, profile, extra)
    sess.count_mode = elm.count_mode
    fast, slow = plan["fast"], plan["slow"]
    if not fast and not slow:
        sys.exit("Nothing to log (check --pids).")

    os.makedirs(LOG_DIR, exist_ok=True)
    base = os.path.join(LOG_DIR, f"mustang_{stamp()}")
    meta = {"started": datetime.datetime.now().isoformat(timespec="seconds"),
            "tool": f"mustang_obd.py {TOOL_VERSION}", "adapter": describe_adapter(elm),
            "vin": plan["vin"], "primary_ecu": plan["primary"],
            "supported_pids": {h: [f"{p:02X}" for p in ps] for h, ps in plan["sup"].items()},
            "pid_lengths": {h: {f"{p:02X}": n for p, n in L.items()} for h, L in plan["lengths"].items()},
            "static_values": plan["statics"],
            "fast_tier": [repr(i) for i in fast], "slow_tier": [repr(i) for i in slow],
            "enhanced": [{"ecu": e["ecu"], "did": f"{e['did']:04X}", "name": e["name"],
                          "expr": e["expr"], "status": e["status"]} for e in plan["dids"]],
            "columns": plan["columns"],
            "pid_descriptions": {f"{p:02X}": PIDS[p][0] for h in plan["sup"]
                                 for p in plan["sup"][h] if p in PIDS}}
    with open(base + ".meta.json", "w", encoding="utf-8") as f:
        json.dump(meta, f, indent=2)

    print(f"VIN {plan['vin'] or '?'}   primary ECU {plan['primary']}")
    print(f"Fast tier ({sum(len(i.pids) for i in fast)} PIDs in {len(fast)} requests): "
          + " ".join(repr(i) for i in fast))
    print(f"Slow tier ({len(slow)} requests, {args.slow_per_cycle} per cycle): "
          + " ".join(repr(i) for i in slow))
    print(f"Logging -> {base}.csv\n  Ctrl+C to stop"
          + (f", or after {args.seconds}s" if args.seconds else "") + ".")

    header = ["t_s", "clock"] + plan["columns"]
    state = {}
    rows = errors = silent = 0
    slow_i = 0
    t0 = time.monotonic()
    last_print = 0.0
    min_period = 1.0 / args.hz if args.hz > 0 else 0.0
    with open(base + ".csv", "w", newline="", buffering=1, encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(header)
        try:
            while not args.seconds or time.monotonic() - t0 < args.seconds:
                cyc0 = time.monotonic()
                try:
                    answered = 0
                    for it in fast:
                        answered += run_item(sess.elm, it, plan, state)
                    for _ in range(min(args.slow_per_cycle, len(slow)) if slow else 0):
                        answered += run_item(sess.elm, slow[slow_i % len(slow)], plan, state)
                        slow_i += 1
                except (LinkError, OSError) as e:
                    errors += 1
                    print(f"\n  {e}")
                    sess.reconnect()
                    continue
                now = time.monotonic()
                if answered:
                    silent = 0
                    w.writerow([f"{now - t0:.3f}",
                                datetime.datetime.now().strftime("%H:%M:%S.%f")[:-3]]
                               + [state.get(c, "") for c in plan["columns"]])
                    rows += 1
                else:
                    silent += 1
                    if silent >= 3:
                        wait_for_car(sess)
                        silent = 0
                        continue
                if now - last_print >= 0.5:
                    last_print = now
                    print(f"\r{now - t0:6.0f}s {rows} rows {rows / max(now - t0, 1e-3):4.1f}/s | "
                          f"{status_text(state)}   ", end="", flush=True)
                sleep = min_period - (time.monotonic() - cyc0)
                if sleep > 0:
                    time.sleep(sleep)
        except KeyboardInterrupt:
            pass
    sess.close()
    dur = time.monotonic() - t0
    print(f"\nSaved {rows} rows in {dur:.0f}s ({rows / max(dur, 1e-3):.1f} rows/s), "
          f"{errors} reconnects.\n  {base}.csv\n  {base}.meta.json")


def wait_for_car(sess):
    print("\n  car not answering (ignition off?) - waiting. Ctrl+C to stop.")
    while True:
        try:
            elm = sess.elm
            elm.set_header(None)
            if elm.obd("0100", timeout=6.0).positive(0x01):
                print("  car is back.")
                return
        except (LinkError, OSError):
            sess.reconnect()
            return
        time.sleep(2)


# ----------------------------------------------------------------------------- DID scan
SCAN_PRESETS = {
    # Ranges where Ford PCM identifiers cluster in community PID lists. Not exhaustive -
    # 'full' covers everything (65536 ids, roughly an hour).
    "quick": "0100-01FF,0300-04FF,0900-09FF,1100-11FF,1600-16FF,1E00-1EFF,DD00-DDFF,F100-F1FF,F400-F4FF",
    "full": "0000-FFFF",
}


def parse_ranges(spec):
    spec = SCAN_PRESETS.get(spec.lower(), spec)
    out = []
    for part in re.split(r"[,\s]+", spec.strip()):
        if not part:
            continue
        lo, _, hi = part.partition("-")
        lo, hi = int(lo, 16), int(hi or lo, 16)
        if not (0 <= lo <= hi <= 0xFFFF):
            sys.exit(f"bad DID range {part!r}")
        out.append((lo, hi))
    return out


def cmd_scan(args):
    ranges = parse_ranges(args.scan_dids)
    total = sum(hi - lo + 1 for lo, hi in ranges)
    ecu = args.ecu.upper()
    sess = Session(args)
    elm = sess.connect()
    if not elm.can11:
        sys.exit("DID scan needs an 11-bit CAN car.")
    print(f"Connected: {elm.version} via {elm.t.name}, {elm.proto_name}")
    if not args.no_count:
        sup = discover_bitmap(elm, 0x01)
        if sup:
            print(f"  response-count shortcut: {calibrate_count(elm, pick_primary(sup)) or 'off'}")
            sess.count_mode = elm.count_mode
    os.makedirs(LOG_DIR, exist_ok=True)
    path = os.path.join(LOG_DIR, f"mustang_didscan_{ecu}_{stamp()}.csv")
    print(f"Scanning {total} DIDs on ECU {ecu} (read-only 0x22) -> {path}\n  Ctrl+C stops "
          "and keeps what was found so far.")
    found = n = silent = notsup = 0
    t0 = time.monotonic()
    with open(path, "w", newline="", buffering=1, encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["ecu", "did", "status", "len", "hex", "ascii"])
        try:
            for lo, hi in ranges:
                for did in range(lo, hi + 1):
                    while True:
                        try:
                            st, val = read_did(elm, ecu, did)
                            break
                        except LinkError:
                            elm = sess.reconnect()
                    n += 1
                    if st == "ok":
                        found += 1
                        w.writerow([ecu, f"{did:04X}", "ok", len(val), val.hex().upper(), ascii_of(val)])
                        print(f"\r  {did:04X}: {len(val)} bytes  {val.hex().upper()[:48]}" + " " * 20)
                    elif st == "nrc" and val != 0x31:
                        w.writerow([ecu, f"{did:04X}", NRC_TEXT.get(val, f"nrc_{val:02X}"), "", "", ""])
                    if st == "none":
                        silent += 1
                        if silent >= 20 and found == 0:
                            sys.exit(f"\nECU {ecu} does not answer at all - wrong --ecu? "
                                     "(PCM is 7E0)")
                    else:
                        silent = 0
                    if st == "nrc" and val in (0x11, 0x7F):
                        notsup += 1
                        if notsup >= 16 and found == 0:
                            sys.exit(f"\nECU {ecu} does not support Mode 22 in the default session.")
                    if n % 16 == 0:
                        rate = n / max(time.monotonic() - t0, 1e-3)
                        print(f"\r  {n}/{total} ({did:04X})  {found} found  {rate:.0f}/s  "
                              f"~{(total - n) / max(rate, 0.1) / 60:.0f} min left   ", end="", flush=True)
        except KeyboardInterrupt:
            print("\n  stopped.")
    sess.close()
    print(f"\nScanned {n} DIDs, {found} answered. Saved {path}\n"
          f"Record them all while driving:  --log --extra-dids \"{path}\"")


# =============================================================================
def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    mode = ap.add_mutually_exclusive_group(required=True)
    mode.add_argument("--list", action="store_true", help="list adapter ports")
    mode.add_argument("--handshake", action="store_true", help="connection smoke test")
    mode.add_argument("--snapshot", action="store_true", help="one-shot dump of everything")
    mode.add_argument("--log", action="store_true", help="continuous CSV logging")
    mode.add_argument("--scan-dids", metavar="RANGES",
                      help="Mode 22 DID discovery: 'quick', 'full' or e.g. 1E00-1EFF,DD00-DDFF")
    conn = ap.add_argument_group("connection")
    conn.add_argument("--bt", action="store_true", help="auto-detect Bluetooth SPP COM port (default)")
    conn.add_argument("--port", help="serial port, e.g. COM7 or /dev/rfcomm0")
    conn.add_argument("--baud", type=int, default=38400, help="USB adapters only (default 38400)")
    conn.add_argument("--rfcomm", metavar="MAC", help="Bluetooth RFCOMM socket to this adapter MAC")
    conn.add_argument("--channel", type=int, default=1, help="RFCOMM channel (default 1)")
    conn.add_argument("--ble", nargs="?", const="", metavar="ADDRESS",
                      help="Bluetooth Low Energy adapter (address optional: auto-scan)")
    conn.add_argument("--tcp", metavar="HOST:PORT", help="WiFi adapter, e.g. 192.168.0.10:35000")
    conn.add_argument("--demo", action="store_true", help="simulated Mustang, no hardware")
    conn.add_argument("--protocol", default="6",
                      help="ELM protocol: 6 = CAN 11-bit 500k (Mustang, default), 0 = auto")
    lg = ap.add_argument_group("logging")
    lg.add_argument("--seconds", type=float, default=0, help="stop after N seconds (0 = Ctrl+C)")
    lg.add_argument("--hz", type=float, default=0, help="max rows per second (0 = as fast as possible)")
    lg.add_argument("--pids", help="log only these Mode 01 PIDs, e.g. 0C,0D,05,0B")
    lg.add_argument("--slow-per-cycle", type=int, default=1,
                    help="slow-tier requests per row (default 1)")
    lg.add_argument("--profile", default=DEFAULT_PROFILE, help="enhanced-data profile JSON ('none' = off)")
    lg.add_argument("--extra-dids", metavar="SCAN_CSV", help="also log every DID found by --scan-dids")
    lg.add_argument("--ecu", default="7E0", help="--scan-dids: ECU request id (PCM = 7E0)")
    lg.add_argument("--no-multi", action="store_true", help="one PID per request (slow, max compatibility)")
    lg.add_argument("--no-count", action="store_true", help="disable the response-count shortcut")
    lg.add_argument("--trace", action="store_true", help="write raw adapter traffic to logs/elm_trace_*.txt")
    args = ap.parse_args()

    if args.list:
        cmd_list(args)
        return
    if not (args.port or args.rfcomm or args.ble is not None or args.tcp or args.demo):
        args.bt = True
    if args.bt and not args.port:
        args.port = autodetect_port(args)
    profile = load_profile(args.profile)
    try:
        if args.handshake:
            cmd_handshake(args)
        elif args.snapshot:
            cmd_snapshot(args, profile)
        elif args.log:
            cmd_log(args, profile)
        elif args.scan_dids:
            cmd_scan(args)
    except LinkError as e:
        sys.exit(f"\nAdapter link lost: {e}\nRerun the command (add --trace to record the adapter traffic).")
    except KeyboardInterrupt:
        sys.exit("\nStopped.")


if __name__ == "__main__":
    main()
