#!/usr/bin/env python3
"""Tests for mustang_obd.py against the built-in simulator (no hardware needed).

    py -m unittest -v test_mustang_obd        (run from this folder)
"""

import argparse
import contextlib
import csv
import glob
import io
import json
import os
import shutil
import tempfile
import time
import unittest

import elm_sim
import mustang_obd as M


def make_args(**kw):
    a = dict(list=False, handshake=False, snapshot=False, log=False, scan_dids=None, bt=False,
             port=None, baud=38400, rfcomm=None, channel=1, ble=None, tcp=None, demo=True,
             protocol="6", seconds=3.0, hz=0, pids=None, slow_per_cycle=1,
             profile=M.DEFAULT_PROFILE, extra_dids=None, ecu="7E0", no_multi=False,
             no_count=False, trace=False, sim_factory=None)
    a.update(kw)
    return argparse.Namespace(**a)


def read(path):
    with open(path, newline="", encoding="utf-8") as f:
        return f.read()


def rows_of(path):
    return list(csv.DictReader(io.StringIO(read(path))))


def quiet(fn, *a, **kw):
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        fn(*a, **kw)
    return buf.getvalue()


class TempLogDir(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self._old = M.LOG_DIR
        M.LOG_DIR = self.tmp

    def tearDown(self):
        M.LOG_DIR = self._old
        shutil.rmtree(self.tmp, ignore_errors=True)

    def newest(self, pattern):
        files = sorted(glob.glob(os.path.join(self.tmp, pattern)), key=os.path.getmtime)
        self.assertTrue(files, f"no {pattern} written")
        return files[-1]


# --------------------------------------------------------------------------- safety
class TestReadOnlyGuard(unittest.TestCase):
    def test_write_and_clear_services_refused(self):
        for req in ("04", "14FFFFFF", "2EF19001", "2F123403", "3101FF00", "1003", "2701",
                    "3B9001", "1101", "2803", "8502", "3E00"):
            with self.assertRaises(M.GuardError, msg=req):
                M.guard_obd(req)

    def test_read_services_allowed(self):
        for req in ("0100", "010C0D", "020200", "03", "07", "0A", "0600", "0902", "22F190"):
            self.assertEqual(M.guard_obd(req), req)

    def test_dangerous_adapter_commands_refused(self):
        for cmd in ("ATPP 0C SV 23", "ATMA", "ATBRD 23", "ATWM 81 10 F1 3E", "ATSI", "AT FI",
                    "ATSW 00", "ATCP 18", ""):
            with self.assertRaises(M.GuardError, msg=cmd):
                M.guard_at(cmd)

    def test_bare_carriage_return_never_sent(self):
        elm = M.Elm327(elm_sim.SimTransport())
        with self.assertRaises(M.GuardError):
            elm._xfer("", 1.0)

    def test_full_session_only_sends_whitelisted_commands(self):
        transports = []

        def factory():
            t = elm_sim.SimTransport()
            transports.append(t)
            return t
        tmp = tempfile.mkdtemp()
        old, M.LOG_DIR = M.LOG_DIR, tmp
        try:
            args = make_args(sim_factory=factory, seconds=1.0)
            profile = M.load_profile(M.DEFAULT_PROFILE)
            quiet(M.cmd_snapshot, args, profile)
            quiet(M.cmd_log, args, profile)
            quiet(M.cmd_scan, make_args(sim_factory=factory, scan_dids="4200-4300"))

            def w211():
                t = elm_sim.SimTransport(vehicle="w211")
                transports.append(t)
                return t
            args = make_args(sim_factory=w211, seconds=1.0)
            quiet(M.cmd_snapshot, args, profile)
            quiet(M.cmd_log, args, profile)
        finally:
            M.LOG_DIR = old
            shutil.rmtree(tmp, ignore_errors=True)
        sent = [ln for t in transports for ln in t.sent]
        self.assertGreater(len(sent), 200)
        for ln in sent:
            c = ln.replace(" ", "").upper()
            self.assertTrue(c, "empty line sent")
            if c.startswith("AT"):
                M.guard_at(c)
            else:
                body = c[:-1] if len(c) % 2 else c     # optional response-count digit
                M.guard_obd(body)


class TestPortDetection(unittest.TestCase):
    def test_outgoing_bluetooth_port_ranked_first(self):
        class P:
            def __init__(self, device, hwid, description):
                self.device, self.hwid, self.description = device, hwid, description
        fake = [
            P("COM3", "USB VID:PID=0403:6001 SER=A12345", "USB Serial Port (COM3)"),
            P("COM5", r"BTHENUM\{00001101-0000-1000-8000-00805F9B34FB}_LOCALMFG&0000\7&1&0&"
                      r"000000000000_00000000", "Standard Serial over Bluetooth link (COM5)"),
            P("COM6", r"BTHENUM\{00001101-0000-1000-8000-00805F9B34FB}_LOCALMFG&0002\7&2&0&"
                      r"001DA5689A0B_C00000000", "Standard Serial over Bluetooth link (COM6)"),
            P("COM1", "ACPI\\PNP0501\\0", "Communications Port (COM1)"),
        ]
        old = M.list_ports.comports
        M.list_ports.comports = lambda: fake
        try:
            got = M.serial_candidates()
        finally:
            M.list_ports.comports = old
        self.assertEqual([d for d, _, _ in got], ["COM6", "COM5", "COM3"])
        self.assertEqual([bt for _, _, bt in got], [True, True, False])


# --------------------------------------------------------------------------- parsing
class TestParse(unittest.TestCase):
    def test_single_frame_with_padding(self):
        rep = M.parse_reply(["7E8 06 41 00 BE 3F B8 13 00"])
        self.assertEqual(rep.positive(1), [("7E8", bytes.fromhex("4100BE3FB813"))])

    def test_spaces_off(self):
        rep = M.parse_reply(["7E804410C1AF8"])
        self.assertEqual(rep.msgs["7E8"], [bytes.fromhex("410C1AF8")])

    def test_multi_frame_vin_two_ecus_interleaved(self):
        lines = ["SEARCHING...",
                 "7E8 10 14 49 02 01 31 46 41",
                 "7E9 03 41 0D 32 00 00 00 00",
                 "7E8 21 36 50 38 41 4D 30 47",
                 "7E8 22 35 31 32 33 34 35 36"]
        rep = M.parse_reply(lines)
        vin = [m for h, m in rep.positive(9)][0]
        self.assertEqual(M.decode_mode09(0x02, vin), "1FA6P8AM0G5123456")
        self.assertEqual(rep.msgs["7E9"], [bytes.fromhex("410D32")])
        self.assertFalse(rep.partial)

    def test_lost_consecutive_frame_is_partial_not_garbage(self):
        rep = M.parse_reply(["7E8 10 14 49 02 01 31 46 41", "7E8 22 35 31 32 33 34 35 36"])
        self.assertNotIn("7E8", rep.msgs)
        self.assertIn("7E8", rep.partial)

    def test_status_lines(self):
        for line in ("NO DATA", "CAN ERROR", "?", "UNABLE TO CONNECT", "BUFFER FULL", "STOPPED"):
            rep = M.parse_reply([line])
            self.assertFalse(rep.msgs)
            self.assertEqual(rep.status, [line])

    def test_29bit_headers(self):
        rep = M.parse_reply(["18DAF110 03 41 0D 32"], hdr_len=8)
        self.assertEqual(rep.positive(1), [("18DAF110", bytes.fromhex("410D32"))])

    def test_negative_response(self):
        rep = M.parse_reply(["7E8 03 7F 22 31"])
        self.assertEqual(rep.negative(0x22), [("7E8", 0x31)])

    def test_kline_kwp2000_and_iso9141(self):
        # KWP2000: format byte 0x80|len, target F1, source 10, data, checksum
        rep = M.parse_reply(["84 F1 10 41 0C 0B E0 BD", "BUS INIT: ...OK"], hdr_len=0)
        self.assertEqual(rep.positive(1), [("10", bytes.fromhex("410C0BE0"))])
        # ISO 9141-2: 48 6B source, data, checksum (no length in the header)
        rep = M.parse_reply(["486B10410C0BE0FB"], hdr_len=0)
        self.assertEqual(rep.positive(1), [("10", bytes.fromhex("410C0BE0"))])
        # KWP2000 with a separate length byte (4-byte header)
        rep = M.parse_reply(["80 F1 10 03 41 0D 32 F9"], hdr_len=0)
        self.assertEqual(rep.positive(1), [("10", bytes.fromhex("410D32"))])
        for line in ("NO DATA", "BUS INIT: ...ERROR", "?"):
            self.assertEqual(M.parse_reply([line], hdr_len=0).status, [line])

    def test_kline_vin_and_dtcs_in_several_messages(self):
        lines = ["87 F1 10 49 02 03 41 54 45 44 F4", "87 F1 10 49 02 01 00 00 00 53 27",
                 "87 F1 10 49 02 02 49 4D 55 4C 0C", "87 F1 10 49 02 04 30 57 32 31 C1",
                 "87 F1 10 49 02 05 31 45 32 32 B2"]
        m = M.mode09_message(M.parse_reply(lines, hdr_len=0), "10", 0x02, can=False)
        self.assertEqual(M.decode_mode09(0x02, m), "SIMULATED0W211E22")
        rep = M.parse_reply(["87 F1 10 43 04 01 06 70 00 00 46"], hdr_len=0)
        self.assertEqual(M.parse_dtcs(rep.positive(3)[0][1]), ["P0401", "P0670"])

    def test_multi_pid_split_ignores_unrequested_bytes(self):
        lengths = {0x0C: 2, 0x0D: 1, 0x05: 1}
        got = M.split_mode01(bytes.fromhex("410C1AF80D3205 7B".replace(" ", "")), lengths)
        self.assertEqual(got, {0x0C: b"\x1a\xf8", 0x0D: b"\x32", 0x05: b"\x7b"})
        # unknown PID in the middle: stop rather than mis-assign bytes
        self.assertEqual(M.split_mode01(bytes.fromhex("410D32AA0102"), lengths), {0x0D: b"\x32"})


# --------------------------------------------------------------------------- decoding
class TestDecode(unittest.TestCase):
    def val(self, pid, hexdata, col=0):
        return M.decode_pid(pid, bytes.fromhex(hexdata))[col][1]

    def test_core_pids(self):
        self.assertEqual(self.val(0x0C, "1AF8"), 1726.0)
        self.assertEqual(self.val(0x05, "7B"), 83)
        self.assertEqual(self.val(0x0D, "64"), 100)
        self.assertEqual(self.val(0x06, "80"), 0.0)
        self.assertEqual(self.val(0x07, "90"), 12.5)
        self.assertEqual(self.val(0x0E, "9C"), 14.0)
        self.assertEqual(self.val(0x10, "01F4"), 5.0)
        self.assertEqual(self.val(0x32, "FFFC"), -1.0)          # signed 16-bit, 0.25 Pa
        self.assertEqual(self.val(0x34, "80008010", 0), 1.0)   # lambda
        self.assertAlmostEqual(self.val(0x34, "80008010", 1), 0.0625)
        self.assertIsNone(self.val(0x15, "82FF", 1))           # trim not used

    def test_dtc(self):
        self.assertEqual(M.dtc_str(0x01, 0x71), "P0171")
        self.assertEqual(M.dtc_str(0xC1, 0x23), "U0123")
        self.assertEqual(M.parse_dtcs(bytes.fromhex("43020303 0171".replace(" ", ""))),
                         ["P0303", "P0171"])
        self.assertEqual(M.parse_dtcs(bytes.fromhex("4300")), [])

    def test_mode06_misfire(self):
        m = bytes.fromhex("46A40B240025 0000FFFF".replace(" ", ""))
        r = M.parse_mode06(m)[0]
        self.assertEqual((r["mid_name"], r["value"], r["pass"]), ("Misfire cylinder 3", 37, True))

    def test_expr_language(self):
        f = M.compile_expr("S16(A,B)/16")
        self.assertEqual(f(b"\x05\x50"), 85.0)
        self.assertEqual(f(b"\xff\xf0"), -1.0)
        self.assertIsNone(f(b"\x05"))                           # missing byte -> None
        for bad in ("__import__('os')", "A.real", "'x'", "(lambda: 1)()", "open('f')",
                    "[A for A in B]", "True"):
            with self.assertRaises((ValueError, SyntaxError), msg=bad):
                M.compile_expr(bad)

    def test_profile_loads(self):
        p = M.load_profile(M.DEFAULT_PROFILE)
        self.assertTrue(p["dids"])
        self.assertEqual(p["dids"][0]["fn"](b"\x05\x50"), 85.0)


# --------------------------------------------------------------------------- end to end
class TestEndToEnd(TempLogDir):
    def run_log(self, seconds=2.5, **sim):
        args = make_args(seconds=seconds, sim_factory=lambda: elm_sim.SimTransport(**sim))
        out = quiet(M.cmd_log, args, M.load_profile(M.DEFAULT_PROFILE))
        return rows_of(self.newest("mustang_*.csv")), out

    def test_log_all_columns(self):
        rows, _ = self.run_log()
        self.assertGreater(len(rows), 20)
        last = rows[-1]
        for col in ("Rpm", "Speed_kmh", "Coolant_C", "STFT_B1_pct", "LTFT_B2_pct", "MAF_gs",
                    "Lambda_B1S1", "CatTemp_B1S1_C", "FuelLevel_pct", "7E9_Gear",
                    "TransFluidTemp_C", "Odometer_km", "AdapterVolts_V", "raw_010C",
                    "raw_7E0_221E1C"):
            self.assertNotEqual(last.get(col, ""), "", col)
        self.assertEqual(last["Odometer_km"], "123456")
        self.assertEqual(int(last["raw_010C"], 16) / 4, float(last["Rpm"]))
        meta = self.newest("mustang_*.meta.json")
        self.assertIn("SIMULATED0MUSTANG", read(meta))

    def test_adapter_variants(self):
        for sim in ({"count_semantics": "frames"}, {"count_semantics": "ignore"},
                    {"interleave": False}, {"chunk": 3}, {"quirks": {"no_s0"}}):
            with self.subTest(**{k: str(v) for k, v in sim.items()}):
                rows, _ = self.run_log(seconds=1.5, **sim)
                self.assertGreater(len(rows), 5)
                self.assertNotEqual(rows[-1]["Rpm"], "")
                self.assertNotEqual(rows[-1]["TransFluidTemp_C"], "")

    def test_w211_kline_log(self):
        for quirks in ((), {"iso9141"}):
            with self.subTest(quirks=str(quirks)):
                rows, out = self.run_log(seconds=2.0, vehicle="w211", quirks=quirks)
                self.assertGreater(len(rows), 10)
                last = rows[-1]
                for col in ("Rpm", "Speed_kmh", "Coolant_C", "MAP_kPa", "MAF_gs", "FuelRailGauge_kPa",
                            "CmdEGR_pct", "AdapterVolts_V", "raw_0123"):
                    self.assertNotEqual(last.get(col, ""), "", col)
                self.assertFalse([c for c in last if c.startswith("7E") or "TransFluid" in c])
                self.assertEqual(int(last["raw_0123"], 16) * 10, int(float(last["FuelRailGauge_kPa"])))
                meta = read(self.newest("mustang_*.meta.json"))
                self.assertIn("SIMULATED0W211E22", meta)
                self.assertIn("ISO 9141-2" if quirks else "KWP", meta)

    def test_w211_snapshot(self):
        args = make_args(sim_factory=lambda: elm_sim.SimTransport(vehicle="w211"))
        quiet(M.cmd_snapshot, args, M.load_profile(os.path.join(M.HERE, "profile_w211_e220cdi_2005.json")))
        snap = json.loads(read(self.newest("mustang_snapshot_*.json")))
        ecu = snap["ecus"]["10"]
        self.assertEqual(ecu["VIN"], "SIMULATED0W211E22")
        self.assertEqual(ecu["CALID"], ["SIMCAL-CDI-0001"])
        self.assertEqual(snap["dtcs"]["stored"]["10"], ["P0401", "P0670"])
        self.assertEqual(ecu["freeze_frame"]["02"]["values"]["FreezeDTC"], "P0401")
        self.assertIn("mode06_note", snap)
        self.assertEqual(ecu["live"]["23"]["values"]["FuelRailGauge_kPa"], 28000)

    def test_count_calibration(self):
        for sem, want in (("messages", "messages"), ("frames", "frames"), ("ignore", "messages")):
            elm = M.Elm327(elm_sim.SimTransport(count_semantics=sem))
            elm.init()
            elm.connect_car()
            self.assertEqual(M.calibrate_count(elm, "7E8"), want, sem)

    def test_reconnect_after_bluetooth_drop(self):
        made = []

        def factory():
            made.append(elm_sim.SimTransport(fail_after_writes=150 if not made else None))
            return made[-1]
        old, M.RECONNECT_DELAY = M.RECONNECT_DELAY, 0.1
        try:
            args = make_args(seconds=2.0, sim_factory=factory)
            out = quiet(M.cmd_log, args, M.load_profile(M.DEFAULT_PROFILE))
        finally:
            M.RECONNECT_DELAY = old
        self.assertGreaterEqual(len(made), 2)
        self.assertIn("reconnected", out)
        self.assertIn("1 reconnects", out)

    def test_ignition_off_mid_log(self):
        def factory():
            t = elm_sim.SimTransport(latency=0.002)
            t.elm.car.offline_windows = [(1.0, 2.0)]
            return t
        args = make_args(seconds=5.0, sim_factory=factory)
        out = quiet(M.cmd_log, args, M.load_profile(M.DEFAULT_PROFILE))
        self.assertIn("car not answering", out)
        self.assertIn("car is back", out)

    def test_car_silent_and_headerless_clone(self):
        t = elm_sim.SimTransport()
        t.elm.car.forced_offline = True
        elm = M.Elm327(t)
        elm.init()
        with self.assertRaises(M.CarSilent):
            elm.connect_car()
        elm = M.Elm327(elm_sim.SimTransport(quirks={"no_h1"}))
        elm.init()
        with self.assertRaises(SystemExit):
            elm.connect_car()

    def test_snapshot(self):
        args = make_args(sim_factory=lambda: elm_sim.SimTransport())
        quiet(M.cmd_snapshot, args, M.load_profile(M.DEFAULT_PROFILE))
        snap = json.loads(read(self.newest("mustang_snapshot_*.json")))
        pcm = snap["ecus"]["7E8"]
        self.assertEqual(pcm["VIN"], "SIMULATED0MUSTANG")
        self.assertEqual(snap["dtcs"]["stored"]["7E8"], ["P0303"])
        self.assertEqual(snap["dtcs"]["pending"]["7E8"], ["P0171"])
        self.assertEqual(pcm["freeze_frame"]["0C"]["values"]["Rpm"], 2050.0)
        cyl3 = [r for r in pcm["mode06"] if r["mid"] == "A4" and r["tid"] == "0C"][0]
        self.assertEqual(cyl3["value"], 37)
        self.assertEqual(snap["enhanced"]["7E0:F190"]["value"], "SIMULATED0MUSTANG")
        self.assertIn("error", snap["enhanced"]["7E0:F111"])     # NRC 0x31 -> reported, not fatal
        self.assertIn("Misfire cylinder", read(self.newest("mustang_snapshot_*.txt")))

    def test_did_scan_and_extra_dids(self):
        args = make_args(scan_dids="1E00-1E20,4240-4250",
                         sim_factory=lambda: elm_sim.SimTransport())
        quiet(M.cmd_scan, args)
        scan = self.newest("mustang_didscan_*.csv")
        found = [r["did"] for r in rows_of(scan) if r["status"] == "ok"]
        self.assertEqual(found, ["1E1C", "4242"])
        args = make_args(seconds=1.5, extra_dids=scan, profile="none",
                         sim_factory=lambda: elm_sim.SimTransport())
        quiet(M.cmd_log, args, M.load_profile("none"))
        rows = rows_of(self.newest("mustang_2*.csv"))
        self.assertEqual(rows[-1]["raw_7E0_224242"], "1234")

    def test_pids_filter_and_hz_cap(self):
        t0 = time.monotonic()
        args = make_args(seconds=2.0, pids="0C,0D", hz=5,
                         sim_factory=lambda: elm_sim.SimTransport())
        quiet(M.cmd_log, args, M.load_profile(M.DEFAULT_PROFILE))
        rows = rows_of(self.newest("mustang_2*.csv"))
        self.assertLessEqual(len(rows), 12)
        self.assertEqual(set(rows[0]) - {"t_s", "clock"},
                         {"Rpm", "Speed_kmh", "raw_010C", "raw_010D"})
        self.assertLess(time.monotonic() - t0, 10)


if __name__ == "__main__":
    unittest.main()
