#!/usr/bin/env python3
"""
jetronic_logger.py - standalone logger for the Invent Jetronic ECU.

Works over BOTH transports (same framed protocol either way):
  * USB/FTDI  -> default COM3
  * Bluetooth -> HC-06 SPP virtual COM port (pair first, name 'Jetronic' PIN 0000; see docs/BLUETOOTH.md)

Modes:
  --log         Continuous CSV logging: polls the live-telemetry frame at --hz, writes
                confirmed channels + all 50 raw data bytes to logs/jlog_*.csv (next to this script).
  --handshake   Read firmware version + ECU name + one live poll (connection smoke test).
  --list-bt     List paired Bluetooth serial ports.
  --probe       Passive baud scan (USB; Commander closed, ignition on).
  --capture     Raw byte capture with timing (protocol RE aid).

Transport flags:
  --port COMx   explicit port (default COM3)
  --bt          auto-detect + use the Bluetooth SPP port (with connect-retry)

Usage examples:
  py jetronic_logger.py --handshake                 # USB (COM3)
  py jetronic_logger.py --bt --handshake            # Bluetooth
  py jetronic_logger.py --bt --log --hz 20 --seconds 600   # wireless drive log
"""

import argparse
import datetime
import os
import sys
import time

try:
    import serial  # pyserial
except ImportError:
    sys.exit("pyserial missing - run:  py -m pip install pyserial")

PORT_DEFAULT = "COM3"
BAUD_DEFAULT = 19200      # runtime data protocol baud (from FTDI SET_BAUD in capture; NOT the 115200 bootloader)
BAUDS = [19200, 115200, 57600, 38400, 9600, 230400]
CAPTURE_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "captures")
LOG_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "logs")

# --- Protocol (reverse-engineered from USBPcap captures, July 2026) ---
SYNC = bytes([0x55, 0x00, 0xAA, 0x00])
CMD_LIVE = 0x00      # -> 0x01 reply, 51-byte body: live telemetry (the log row)
CMD_VER = 0xF0       # -> 0xF1 reply: firmware version ASCII
CMD_NAME = 0x90      # -> 0x91 reply: ECU name ASCII
POLL_LIVE = bytes([0x55, 0x00, 0xAA, 0x00, 0x03, 0x00, 0x03, 0x00])  # captured verbatim

# Verified channel map (align_decode 2026-07-21, combined USBPcap+Commander CSV, R2 shown).
# Each entry: (name, kind, offset, mul, add).  kinds:
#   u8   value = mul*d[i] + add
#   s8   value = mul*signed(d[i]) + add
#   le16 value = mul*(d[i] | d[i+1]<<8) + add
#   be16 value = mul*(d[i]<<8 | d[i+1]) + add
#   inv  value = mul / (d[i] | d[i+1]<<8)          (RPM stored as period)
# Offsets are 0-based into the 50 data bytes (after the 0x01 id). All R2>=0.99 unless noted.
CHANNELS = [
    ("Rpm",            "inv",  5, 6.24616e6, 0.0),
    ("Map",            "u8",  24, 2.0,       0.0),   # kPa
    ("Clt",            "u8",  10, 1.0,       0.0),   # degC
    ("Iat",            "u8",  11, 1.0,       0.0),   # degC
    ("InjTime",        "le16", 8, 0.00642,   0.0),   # ms
    ("InjCount",       "u8",   7, 1.0,       0.0),
    ("InjEndAngle",    "u8",  33, 2.0,       0.0),   # deg
    ("Tps",            "u8",  13, 0.39291,  -0.45),  # %
    ("Afr",            "u8",  18, 0.11648,  -0.21),
    ("TargetAfr",      "u8",  27, 0.11497,   0.0),
    ("LambdaCorrFast", "s8",  21, 0.992,     0.0),   # %
    ("LambdaCorrSlow", "s8",  22, 0.999,     0.0),   # %
    ("IdlePos",        "u8",  23, 0.38813,   0.30),  # %
    ("Voltage",        "u8",  12, 0.09991,   0.0),   # V
    ("Load16",         "u8",   4, 0.0625,    0.0),
    ("ChargeTemp",     "u8",  35, 1.0,       0.0),   # degC
    ("Airflow",        "u8",  31, 1.0,       0.0),
    ("MafEmu",         "u8",   3, 0.01968,   0.0),
    ("FuelConsumed",   "le16",36, 1.0e-5,    0.0),   # accumulator (may wrap on long logs)
    ("MapVoltage",     "u8",  40, 0.01959,   0.0),   # V
    ("CltVoltage",     "u8",  15, 0.02,      0.0),   # V
    ("IatVoltage",     "u8",  16, 0.02,      0.0),   # V
    ("TpsVoltage",     "le16",13, 7.6e-5,    0.0),   # V (raw TPS ADC is [13,14])
    ("LambdaVoltage",  "be16",17, 7.6e-5,    0.0),   # V
    ("Runlevel",       "u8",   0, 1.0,       0.0),   # =3 running (constant this session)
]
# NOT locked (constant during the decode session or byte-collision) - available as raw dNN:
#   InjDuty, AccelCorr, AccelTotalCorr, AfrError, InjChargeTCorr, LambdaHeating, Angle(EZL=0),
#   Dwell, Debug1/2, and all *Flag bits. Re-run align_decode on a log where these MOVE to lock them.

def _decode(kind, d, i, mul, add):
    if kind == "u8":
        raw = d[i]
    elif kind == "s8":
        raw = d[i] - 256 if d[i] > 127 else d[i]
    elif kind == "le16":
        raw = d[i] | (d[i+1] << 8)
    elif kind == "be16":
        raw = (d[i] << 8) | d[i+1]
    elif kind == "inv":
        pair = d[i] | (d[i+1] << 8)
        return round(mul / pair, 1) if pair else 0.0
    else:
        raw = d[i]
    return round(mul * raw + add, 3)


def open_port(port, baud, timeout=0.05):
    """Open serial port with RTS asserted and DTR low (the line state the ECU requires)."""
    ser = serial.Serial()
    ser.port = port
    ser.baudrate = baud
    ser.bytesize = serial.EIGHTBITS
    ser.parity = serial.PARITY_NONE
    ser.stopbits = serial.STOPBITS_ONE
    ser.timeout = timeout
    # ECU link needs RTS asserted, DTR low (matches InventCommander's line state).
    ser.dtr = False
    ser.rts = True
    try:
        ser.open()
    except serial.SerialException as e:
        if "PermissionError" in str(e) or "Access is denied" in str(e):
            sys.exit(f"ERROR: {port} is busy - close InventCommander first, then retry.")
        raise
    return ser


def find_bt_ports():
    """Return COM ports that are Bluetooth SPP links (HC-06 shows up as BTHENUM SPP)."""
    from serial.tools import list_ports
    hits = []
    for p in list_ports.comports():
        blob = f"{p.hwid or ''} {p.description or ''}".upper()
        if "BTHENUM" in blob or "BLUETOOTH" in blob:
            hits.append((p.device, p.description))
    return hits


def resolve_port(args):
    """Pick the serial port: explicit --port wins; --bt auto-finds the Bluetooth SPP port."""
    if getattr(args, "bt", False):
        hits = find_bt_ports()
        if not hits:
            sys.exit("No Bluetooth serial port found. Pair the ECU first (see BLUETOOTH.md):\n"
                     "  device name 'Jetronic', PIN 0000. Windows then creates a\n"
                     "  'Standard Serial over Bluetooth link (COMx)' - that's the port.\n"
                     "  Then rerun, or pass --port COMx directly.")
        if len(hits) > 1:
            print("Multiple Bluetooth serial ports found:")
            for d, desc in hits:
                print(f"  {d}  {desc}")
            print(f"Using {hits[0][0]} - override with --port if wrong.")
        print(f"Bluetooth port: {hits[0][0]} ({hits[0][1]})")
        return hits[0][0]
    return args.port


def bt_connect(port, baud, timeout=0.3, attempts=8):
    """Open a BT SPP port with retries: the RFCOMM link takes a moment to establish,
    and the ECU must be powered (ignition on). Confirms link with a version query."""
    last = None
    for k in range(attempts):
        try:
            ser = open_port(port, baud, timeout)
        except serial.SerialException as e:
            last = e
            time.sleep(1.5)
            continue
        # probe: ask firmware version; retry if no reply yet (link still coming up)
        v = query_ascii(ser, CMD_VER, 0xF1, "firmware") if k == 0 else None
        if v is None:
            ser.write(bytes([0x55, 0x00, 0xAA, 0x00, 0x03, CMD_VER, 0x03 ^ CMD_VER, 0x00]))
            r = read_frame(ser, 0xF1, timeout=1.5)
            if r:
                print(f"  Bluetooth link up on attempt {k+1}")
                return ser
            ser.close()
            print(f"  waiting for ECU over Bluetooth... ({k+1}/{attempts})")
            time.sleep(1.5)
            continue
        return ser
    sys.exit(f"Could not establish Bluetooth link on {port} after {attempts} tries.\n"
             f"Check: ignition ON, ECU paired, Commander closed. ({last})")


def hexdump(data, base_offset=0):
    lines = []
    for i in range(0, len(data), 16):
        chunk = data[i:i + 16]
        hx = " ".join(f"{b:02X}" for b in chunk)
        asc = "".join(chr(b) if 32 <= b < 127 else "." for b in chunk)
        lines.append(f"{base_offset + i:08X}  {hx:<47}  |{asc}|")
    return "\n".join(lines)


def probe(port, listen_s):
    print(f"Passive probe on {port} - InventCommander must be CLOSED, ignition ON.")
    print(f"Listening {listen_s}s per baud, sending NOTHING.\n")
    results = []
    for baud in BAUDS:
        try:
            ser = open_port(port, baud)
        except serial.SerialException as e:
            print(f"  {baud:>7} baud: open failed ({e})")
            continue
        ser.reset_input_buffer()
        t0 = time.time()
        buf = bytearray()
        while time.time() - t0 < listen_s:
            chunk = ser.read(4096)
            if chunk:
                buf.extend(chunk)
        ser.close()
        results.append((baud, bytes(buf)))
        print(f"  {baud:>7} baud: {len(buf)} bytes received")
        if buf:
            print(hexdump(buf[:64]))
    best = max(results, key=lambda r: len(r[1]), default=None)
    print()
    if best and len(best[1]) > 0:
        print(f"RESULT: ECU streams unpolled. Best baud candidate: {best[0]}")
        print("Next: run  --capture --baud", best[0])
    else:
        print("RESULT: silence on all bauds - ECU is polled (master/slave protocol)")
        print("or ignition was off. If ignition WAS on: we need a USBPcap capture of")
        print("an InventCommander session to learn the poll commands (see SNIFFING.md).")


def capture(port, baud, seconds):
    os.makedirs(CAPTURE_DIR, exist_ok=True)
    stamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    bin_path = os.path.join(CAPTURE_DIR, f"raw_{stamp}_{baud}.bin")
    txt_path = os.path.join(CAPTURE_DIR, f"raw_{stamp}_{baud}.txt")
    ser = open_port(port, baud, timeout=0.02)
    ser.reset_input_buffer()
    print(f"Capturing {port} @ {baud} for {seconds}s (Ctrl+C to stop early)...")
    total = 0
    t0 = time.time()
    last_rx = None
    with open(bin_path, "wb") as fb, open(txt_path, "w") as ft:
        ft.write(f"# capture {stamp} port={port} baud={baud}\n")
        ft.write("# format: [+gap_ms] hexbytes   (gap = pause before this chunk; gaps >5ms usually mark frame boundaries)\n")
        try:
            while time.time() - t0 < seconds:
                chunk = ser.read(4096)
                if not chunk:
                    continue
                now = time.time()
                gap_ms = 0.0 if last_rx is None else (now - last_rx) * 1000.0
                last_rx = now
                fb.write(chunk)
                ft.write(f"[+{gap_ms:8.2f}ms] {' '.join(f'{b:02X}' for b in chunk)}\n")
                total += len(chunk)
                print(f"\r{total} bytes  ({total / max(now - t0, 0.001):.0f} B/s)", end="")
        except KeyboardInterrupt:
            pass
    ser.close()
    print(f"\nSaved: {bin_path}\n       {txt_path}")
    if total == 0:
        print("No data received - ECU likely polled, or ignition off. See SNIFFING.md.")


def xor_crc(frame_without_trailer):
    """CRC seen in capture = XOR of LEN..last-data byte. frame = SYNC+LEN+body+CRC+00."""
    c = 0
    for b in frame_without_trailer[4:]:  # from LEN onward
        c ^= b
    return c


def read_frame(ser, want_cmd, timeout=1.0):
    """Read one SYNC-framed reply, return (cmd, body_data) or None on timeout/bad frame."""
    t0 = time.time()
    buf = bytearray()
    while time.time() - t0 < timeout:
        chunk = ser.read(1)                       # block briefly for first byte
        if chunk:
            chunk += ser.read(ser.in_waiting)     # then drain whatever else arrived
            buf.extend(chunk)
        idx = buf.find(SYNC)
        if idx < 0 or len(buf) < idx + 6:
            continue
        length = buf[idx + 4]
        end = idx + 5 + length      # frame = SYNC(4)+LEN(1)+body(LEN); no trailer byte
        if len(buf) < end:
            continue
        body = bytes(buf[idx + 5: end])
        del buf[:end]
        # Note: the 3-byte control frames satisfy XOR(LEN,*body)==0, but the 51-byte
        # data frame uses a different (CRC16-CCITT-ish) checksum. The 32-bit sync +
        # length already give strong framing, so we accept on sync+cmd and let the
        # caller sanity-check values. (Verified: data decodes correctly without XOR gate.)
        if body and body[0] == want_cmd:
            return body[0], body[1:]
    return None


def query_ascii(ser, poll_cmd, reply_cmd, label):
    frame = bytes([0x55, 0x00, 0xAA, 0x00, 0x03, poll_cmd, 0x03 ^ poll_cmd, 0x00])
    ser.reset_input_buffer()
    ser.write(frame)
    r = read_frame(ser, reply_cmd)
    if r:
        raw = r[1]
        nul = raw.find(0)                      # ASCII payload is null-terminated
        raw = raw[:nul] if nul >= 0 else raw
        txt = bytes(b for b in raw if 32 <= b < 127).decode("ascii", "replace").strip()
        print(f"  {label}: {txt}")
        return txt
    print(f"  {label}: <no reply>")
    return None


def do_log(port, baud, seconds, hz, bt=False):
    os.makedirs(LOG_DIR, exist_ok=True)
    stamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    tag = "bt" if bt else "usb"
    csv_path = os.path.join(LOG_DIR, f"jlog_{tag}_{stamp}.csv")
    if bt:
        ser = bt_connect(port, baud, timeout=0.4)
    else:
        ser = open_port(port, baud, timeout=0.3)

    print("Handshake:")
    query_ascii(ser, CMD_VER, 0xF1, "firmware")
    query_ascii(ser, CMD_NAME, 0x91, "ecu name")

    # header: elapsed + decoded named channels + all 50 raw data bytes (d00..d49) so nothing is lost
    chan_names = [c[0] for c in CHANNELS]
    raw_names = [f"d{i:02d}" for i in range(50)]
    header = ["t_s"] + chan_names + raw_names

    period = 1.0 / hz if hz > 0 else 0.0
    print(f"Logging {port}@{baud} -> {csv_path}\n  {hz} Hz, {seconds}s, Ctrl+C to stop.")
    t_start = time.time()
    n = 0
    bad = 0
    with open(csv_path, "w", newline="") as f:
        f.write(",".join(header) + "\n")
        next_t = t_start
        try:
            while time.time() - t_start < seconds:
                ser.reset_input_buffer()
                ser.write(POLL_LIVE)
                r = read_frame(ser, 0x01)
                now = time.time()
                if r:
                    data = r[1]
                    if len(data) >= 50:
                        d = data[:50]
                        chan_vals = [f"{_decode(k, d, i, mul, add):g}"
                                     for (_n, k, i, mul, add) in CHANNELS]
                        row = [f"{now - t_start:.3f}"] + chan_vals + [str(b) for b in d]
                        f.write(",".join(row) + "\n")
                        n += 1
                    else:
                        bad += 1
                else:
                    bad += 1
                if n % 20 == 0 and n:
                    print(f"\r{n} rows ({n/max(now-t_start,0.001):.1f} Hz), {bad} bad", end="")
                next_t += period
                sleep = next_t - time.time()
                if sleep > 0:
                    time.sleep(sleep)
        except KeyboardInterrupt:
            pass
    ser.close()
    dur = time.time() - t_start
    print(f"\nSaved {n} rows in {dur:.1f}s (~{n/max(dur,0.001):.1f} Hz), {bad} bad frames.\n{csv_path}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--port", default=PORT_DEFAULT)
    ap.add_argument("--bt", action="store_true",
                    help="use the paired Bluetooth SPP port (auto-detected); see BLUETOOTH.md")
    ap.add_argument("--baud", type=int, default=BAUD_DEFAULT)
    ap.add_argument("--seconds", type=int, default=120)
    ap.add_argument("--hz", type=float, default=20.0, help="log: target sample rate")
    ap.add_argument("--listen", type=int, default=4, help="probe: seconds per baud")
    mode = ap.add_mutually_exclusive_group(required=True)
    mode.add_argument("--probe", action="store_true")
    mode.add_argument("--capture", action="store_true")
    mode.add_argument("--log", action="store_true")
    mode.add_argument("--handshake", action="store_true", help="just read fw version + ecu name")
    mode.add_argument("--list-bt", action="store_true", help="list paired Bluetooth serial ports")
    args = ap.parse_args()

    if args.list_bt:
        hits = find_bt_ports()
        if hits:
            print("Bluetooth serial ports:")
            for d, desc in hits:
                print(f"  {d}  {desc}")
        else:
            print("No Bluetooth serial port. Pair the ECU (name 'Jetronic', PIN 0000) - see BLUETOOTH.md.")
        return

    port = resolve_port(args)

    if args.probe:
        probe(port, args.listen)
    elif args.capture:
        capture(port, args.baud, args.seconds)
    elif args.handshake:
        ser = bt_connect(port, args.baud, timeout=0.6) if args.bt else open_port(port, args.baud, timeout=0.5)
        print("Handshake:")
        query_ascii(ser, CMD_VER, 0xF1, "firmware")
        query_ascii(ser, CMD_NAME, 0x91, "ecu name")
        ser.write(POLL_LIVE)
        r = read_frame(ser, 0x01, timeout=1.0)
        print("  live poll:", "OK" if r else "no reply")
        ser.close()
    elif args.log:
        do_log(port, args.baud, args.seconds, args.hz, bt=args.bt)


if __name__ == "__main__":
    main()
