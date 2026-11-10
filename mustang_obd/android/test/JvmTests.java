package io.github.borohue.mustangobd;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Tests for the Android app's OBD core, run on a plain JVM (no device needed):
 *   java JvmTests VECTORS_FILE [PY_SIM_PORT] [PY_SIM_FRAMES_PORT] [THIRD_PARTY_EMULATOR_PORT]
 * See run_tests.sh.
 */
public class JvmTests {
    static int pass, fail;

    static void check(boolean ok, String what) {
        if (ok) pass++;
        else {
            fail++;
            System.out.println("FAIL: " + what);
        }
    }

    static class DirSink implements Session.Sink {
        final File dir;

        DirSink(File dir) {
            this.dir = dir;
            dir.mkdirs();
        }

        public OutputStream create(String name, String mime) throws IOException {
            return new FileOutputStream(new File(dir, name));
        }

        public String where(String name) {
            return new File(dir, name).getPath();
        }
    }

    static class Collect implements Session.Listener {
        final List<String> msgs = new ArrayList<String>();

        public void status(String text) {
        }

        public synchronized void message(String text) {
            msgs.add(text);
        }

        public void values(Map<String, String> latest, long rows, double rate) {
        }

        synchronized boolean saw(String s) {
            for (String m : msgs) if (m.contains(s)) return true;
            return false;
        }
    }

    static List<String> lines(File f) throws IOException {
        List<String> out = new ArrayList<String>();
        BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
        try {
            for (String l; (l = r.readLine()) != null; ) out.add(l);
        } finally {
            r.close();
        }
        return out;
    }

    static String read(File f) throws IOException {
        return Elm.join(lines(f), "\n");
    }

    static File tmpDir(String tag) {
        File d = new File(System.getProperty("java.io.tmpdir"), "mobd_" + tag + "_" + System.nanoTime());
        d.mkdirs();
        return d;
    }

    // ------------------------------------------------------------------ unit tests

    static void guardTests() {
        for (String r : new String[]{"04", "14FFFFFF", "2EF19001", "2F123403", "3101FF00", "1003", "2701",
                "3B9001", "1101", "2803", "8502", "3E00", "", "0", "01G0"}) {
            boolean refused = false;
            try {
                Elm.guardObd(r);
            } catch (Elm.GuardException e) {
                refused = true;
            }
            check(refused, "guardObd refuses " + r);
        }
        for (String r : new String[]{"0100", "010C0D", "020200", "03", "07", "0A", "0600", "0902", "22F190"})
            check(Elm.guardObd(r).equals(r), "guardObd allows " + r);
        for (String c : new String[]{"ATPP 0C SV 23", "ATMA", "ATBRD 23", "ATWM 81 10 F1 3E", "ATSI", "AT FI",
                "ATSW 00", "ATCP 18", ""}) {
            boolean refused = false;
            try {
                Elm.guardAt(c);
            } catch (Elm.GuardException e) {
                refused = true;
            }
            check(refused, "guardAt refuses '" + c + "'");
        }
        boolean refused = false;
        try {
            new Elm(new SimTransport()).xfer("", 1000);
        } catch (Elm.GuardException e) {
            refused = true;
        } catch (IOException e) {
            refused = false;
        }
        check(refused, "bare CR refused");
    }

    static void parseTests() {
        Elm.Reply r = Elm.parse(Arrays.asList("7E8 06 41 00 BE 3F B8 13 00"), 3);
        check(Arrays.equals(r.positiveFrom(1, "7E8"), Elm.hex("4100BE3FB813")), "single frame with padding");
        r = Elm.parse(Arrays.asList("SEARCHING...", "7E8 10 14 49 02 01 31 46 41", "7E9 03 41 0D 32 00 00 00 00",
                "7E8 21 36 50 38 41 4D 30 47", "7E8 22 35 31 32 33 34 35 36"), 3);
        check("1FA6P8AM0G5123456".equals(Obd.decodeMode09(2, r.positiveFrom(9, "7E8"))), "multi-frame VIN");
        check(Arrays.equals(r.positiveFrom(1, "7E9"), Elm.hex("410D32")), "interleaved second ECU");
        r = Elm.parse(Arrays.asList("7E8 10 14 49 02 01 31 46 41", "7E8 22 35 31 32 33 34 35 36"), 3);
        check(r.msgs.isEmpty() && r.partial.containsKey("7E8"), "lost frame -> partial");
        for (String s : new String[]{"NO DATA", "CAN ERROR", "?", "UNABLE TO CONNECT", "STOPPED"}) {
            r = Elm.parse(Arrays.asList(s), 3);
            check(r.msgs.isEmpty() && r.status.equals(Arrays.asList(s)), "status line " + s);
        }
        r = Elm.parse(Arrays.asList("18DAF110 03 41 0D 32"), 8);
        check(Arrays.equals(r.positiveFrom(1, "18DAF110"), Elm.hex("410D32")), "29-bit header");
        r = Elm.parse(Arrays.asList("7E8 03 7F 22 31"), 3);
        check(r.negative(0x22, "7E8").equals(Arrays.asList(0x31)), "negative response");
        // K-line: KWP2000 (length in format byte), ISO 9141-2, KWP2000 with a length byte
        r = Elm.parse(Arrays.asList("84 F1 10 41 0C 0B E0 BD", "BUS INIT: ...OK"), 0);
        check(Arrays.equals(r.positiveFrom(1, "10"), Elm.hex("410C0BE0")), "K-line KWP2000");
        r = Elm.parse(Arrays.asList("486B10410C0BE0FB"), 0);
        check(Arrays.equals(r.positiveFrom(1, "10"), Elm.hex("410C0BE0")), "K-line ISO 9141-2");
        r = Elm.parse(Arrays.asList("80 F1 10 03 41 0D 32 F9"), 0);
        check(Arrays.equals(r.positiveFrom(1, "10"), Elm.hex("410D32")), "K-line KWP2000 length byte");
        for (String s : new String[]{"NO DATA", "BUS INIT: ...ERROR", "?"})
            check(Elm.parse(Arrays.asList(s), 0).status.equals(Arrays.asList(s)), "K-line status " + s);
        r = Elm.parse(Arrays.asList("87 F1 10 49 02 03 41 54 45 44 F4", "87 F1 10 49 02 01 00 00 00 53 27",
                "87 F1 10 49 02 02 49 4D 55 4C 0C", "87 F1 10 49 02 04 30 57 32 31 C1",
                "87 F1 10 49 02 05 31 45 32 32 B2"), 0);
        check("SIMULATED0W211E22".equals(Obd.decodeMode09(2, Obd.mode09Message(r, "10", 2, false))),
                "K-line VIN from 5 messages");
        r = Elm.parse(Arrays.asList("87 F1 10 43 04 01 06 70 00 00 46"), 0);
        check(Obd.parseDtcs(r.positiveFrom(3, "10")).equals(Arrays.asList("P0401", "P0670")), "K-line DTCs");
        java.util.Map<Integer, Integer> L = new java.util.HashMap<Integer, Integer>();
        L.put(0x0C, 2);
        L.put(0x0D, 1);
        check(Pids.splitMode01(Elm.hex("410D32AA0102"), L).keySet().equals(new java.util.HashSet<Integer>(
                Arrays.asList(0x0D))), "multi-PID split stops at unknown PID");
        for (int n = 1; n <= 40; n++) {
            int expect = n <= 7 ? 1 : 1 + (int) Math.ceil((n - 6) / 7.0);
            check(Elm.framesFor(n) == expect, "framesFor " + n);
        }
    }

    static void vectorTests(File f) throws IOException {
        int n = 0;
        for (String l : lines(f)) {
            String[] p = l.split(" ", 4);
            if (p[0].equals("P")) {
                int pid = Integer.parseInt(p[1], 16);
                Object[] dec = Pids.decode(pid, Elm.hex(p[2]));
                List<String> got = new ArrayList<String>();
                for (Object o : dec) got.add(Pids.fmt(o));
                String want = p.length > 3 ? p[3] : "";
                check(Elm.join(got, "|").equals(want), "PID " + p[1] + " data " + p[2] + ": java "
                        + Elm.join(got, "|") + " python " + want);
            } else if (p[0].equals("D")) {
                check(Pids.dtc(Integer.parseInt(p[1]), Integer.parseInt(p[2])).equals(p[3]), "DTC " + l);
            } else if (p[0].equals("M")) {
                Map<String, Object> r = Obd.parseMode06(Elm.hex("46" + p[1])).get(0);
                String[] w = p[2].split("\\|", -1);
                boolean ok = Math.abs(Double.parseDouble(w[0]) - ((Number) r.get("value")).doubleValue()) < 1e-6
                        && Math.abs(Double.parseDouble(w[1]) - ((Number) r.get("min")).doubleValue()) < 1e-6
                        && Math.abs(Double.parseDouble(w[2]) - ((Number) r.get("max")).doubleValue()) < 1e-6
                        && w[3].equals(r.get("unit")) && Boolean.valueOf(w[4].toLowerCase()).equals(r.get("pass"));
                check(ok, "Mode 06 " + l + " -> " + r);
            }
            n++;
        }
        check(n > 20000, "vector count " + n);
    }

    // ------------------------------------------------------------------ end to end

    interface Factory extends Session.TransportFactory {
    }

    static List<String> e2eLog(String tag, Session.TransportFactory f, double seconds, boolean expectSim)
            throws Exception {
        File dir = tmpDir(tag);
        Collect ui = new Collect();
        Session s = new Session(f, "6", ui);
        LogSession log = new LogSession(s, new DirSink(dir));
        log.seconds = seconds;
        log.run();
        List<String> csv = lines(new File(dir, log.csvName));
        String[] head = csv.get(0).split(",", -1);
        String[] last = csv.get(csv.size() - 1).split(",", -1);
        check(csv.size() > 5, tag + ": rows " + (csv.size() - 1));
        List<String> h = Arrays.asList(head);
        String[] must = expectSim
                ? new String[]{"Rpm", "Speed_kmh", "Coolant_C", "STFT_B1_pct", "LTFT_B2_pct", "Lambda_B1S1",
                "7E9_Gear", "TransFluidTemp_C", "Odometer_km", "AdapterVolts_V", "raw_010C", "raw_7E0_221E1C"}
                : new String[]{"Rpm", "Speed_kmh", "raw_010C"};
        for (String c : must) {
            int i = h.indexOf(c);
            check(i >= 0 && i < last.length && !last[i].isEmpty(), tag + ": column " + c + " filled");
        }
        int ir = h.indexOf("Rpm"), iraw = h.indexOf("raw_010C");
        if (ir >= 0 && iraw >= 0 && !last[iraw].isEmpty())
            check(Double.parseDouble(last[ir]) == Integer.parseInt(last[iraw], 16) / 4.0, tag + ": Rpm == raw/4");
        if (expectSim) {
            int io = h.indexOf("Odometer_km");
            check(io >= 0 && "123456".equals(last[io]), tag + ": odometer decode");
            String meta = read(new File(dir, log.csvName.replace(".csv", ".meta.json")));
            check(meta.contains("SIMULATED0MUSTANG"), tag + ": VIN in meta");
        }
        return Arrays.asList(head);
    }

    static List<String> e2eW211(String tag, Session.TransportFactory f, double seconds) throws Exception {
        File dir = tmpDir(tag);
        Session s = new Session(f, Car.W211.protocol, new Collect());
        LogSession log = new LogSession(s, new DirSink(dir));
        log.profile = Car.W211.profile;
        log.seconds = seconds;
        log.run();
        List<String> csv = lines(new File(dir, log.csvName));
        List<String> h = Arrays.asList(csv.get(0).split(",", -1));
        String[] last = csv.get(csv.size() - 1).split(",", -1);
        check(csv.size() > 5, tag + ": rows " + (csv.size() - 1));
        for (String c : new String[]{"Rpm", "Speed_kmh", "Coolant_C", "MAP_kPa", "MAF_gs", "FuelRailGauge_kPa",
                "CmdEGR_pct", "AdapterVolts_V", "raw_0123"}) {
            int i = h.indexOf(c);
            check(i >= 0 && i < last.length && !last[i].isEmpty(), tag + ": column " + c + " filled");
        }
        int ir = h.indexOf("FuelRailGauge_kPa"), iraw = h.indexOf("raw_0123");
        if (ir >= 0 && iraw >= 0 && !last[iraw].isEmpty())
            check(Integer.parseInt(last[iraw], 16) * 10 == Integer.parseInt(last[ir]), tag + ": rail == raw*10");
        for (String c : h) check(!c.startsWith("7E") && !c.contains("TransFluid"), tag + ": no CAN-only column " + c);
        String meta = read(new File(dir, log.csvName.replace(".csv", ".meta.json")));
        check(meta.contains("SIMULATED0W211E22"), tag + ": VIN (5 K-line messages) in meta");
        return h;
    }

    static void w211Tests() throws Exception {
        final List<SimTransport> made = new ArrayList<SimTransport>();
        final boolean[] iso = {false};
        Session.TransportFactory f = new Session.TransportFactory() {
            public Transport create() {
                SimTransport t = new SimTransport("w211");
                t.iso9141 = iso[0];
                t.latencyMs = 1;
                made.add(t);
                return t;
            }
        };
        e2eW211("w211-kwp", f, 2.0);
        iso[0] = true;
        e2eW211("w211-iso9141", f, 2.0);
        iso[0] = false;
        File dir = tmpDir("w211snap");
        Snapshot snap = new Snapshot(new Session(f, Car.W211.protocol, new Collect()), new DirSink(dir));
        snap.profile = Car.W211.profile;
        snap.run();
        String json = read(new File(dir, snap.jsonName));
        check(json.contains("\"P0401\"") && json.contains("\"P0670\""), "w211 snapshot DTCs");
        check(json.contains("\"VIN\": \"SIMULATED0W211E22\""), "w211 snapshot VIN");
        check(json.contains("\"FreezeDTC\": \"P0401\""), "w211 freeze frame");
        check(json.contains("mode06_note") && snap.text.contains("Mode 06:"), "w211 Mode 06 note");
        check(json.contains("\"FuelRailGauge_kPa\": 28000"), "w211 rail pressure");
        for (SimTransport t : made)
            for (String c : t.sent) {
                String u = c.replace(" ", "").toUpperCase();
                boolean ok;
                try {
                    if (u.startsWith("AT")) Elm.guardAt(u);
                    else {
                        String body = u.length() % 2 == 1 ? u.substring(0, u.length() - 1) : u;
                        Elm.guardObd(body);
                        check(!(body.startsWith("01") && body.length() > 4), "no multi-PID on K-line: " + c);
                        check(!body.startsWith("22") && !body.startsWith("06"), "no Mode 22/06 on K-line: " + c);
                    }
                    ok = !u.isEmpty();
                } catch (Elm.GuardException e) {
                    ok = false;
                }
                check(ok, "w211 sent line passes guard: '" + c + "'");
            }
    }

    static void demoTests() throws Exception {
        final List<SimTransport> made = new ArrayList<SimTransport>();
        Session.TransportFactory f = new Session.TransportFactory() {
            public Transport create() {
                SimTransport t = new SimTransport();
                t.latencyMs = 1;
                made.add(t);
                return t;
            }
        };
        e2eLog("demo", f, 2.5, true);

        // snapshot
        File dir = tmpDir("snap");
        Session s = new Session(f, "6", new Collect());
        Snapshot snap = new Snapshot(s, new DirSink(dir));
        snap.run();
        String json = read(new File(dir, snap.jsonName));
        check(json.contains("\"P0303\"") && json.contains("\"P0171\""), "snapshot DTCs");
        check(json.contains("\"VIN\": \"SIMULATED0MUSTANG\""), "snapshot VIN");
        check(snap.text.contains("Misfire cylinder 3") && snap.text.contains(": 37"), "snapshot misfire counts");
        check(json.contains("\"Rpm\": 2050"), "snapshot freeze frame");
        check(json.contains("requestOutOfRange"), "snapshot reports rejected DID");

        // every line any session sent must pass the read-only guard
        int lines = 0;
        for (SimTransport t : made)
            for (String c : t.sent) {
                lines++;
                String u = c.replace(" ", "").toUpperCase();
                boolean ok;
                try {
                    if (u.startsWith("AT")) Elm.guardAt(u);
                    else Elm.guardObd(u.length() % 2 == 1 ? u.substring(0, u.length() - 1) : u);
                    ok = !u.isEmpty();
                } catch (Elm.GuardException e) {
                    ok = false;
                }
                check(ok, "sent line passes guard: '" + c + "'");
            }
        check(lines > 200, "guard audit saw " + lines + " lines");

        // Bluetooth drop mid-log -> reconnect
        final int[] n = {0};
        Session.TransportFactory dropping = new Session.TransportFactory() {
            public Transport create() {
                final boolean first = n[0]++ == 0;
                SimTransport t = new SimTransport() {
                    int writes;

                    @Override
                    public void write(byte[] d) throws IOException {
                        if (first && ++writes > 150) throw new IOException("simulated Bluetooth drop");
                        super.write(d);
                    }
                };
                t.latencyMs = 1;
                return t;
            }
        };
        Session.reconnectDelayMs = 100;
        Collect ui = new Collect();
        LogSession log = new LogSession(new Session(dropping, "6", ui), new DirSink(tmpDir("drop")));
        log.seconds = 2.0;
        log.run();
        check(log.reconnects == 1 && ui.saw("reconnected") && log.rows > 5, "reconnect after drop (reconnects="
                + log.reconnects + ", rows=" + log.rows + ")");

        // Stop button: run in a thread, stop it
        final Session s2 = new Session(f, "6", new Collect());
        final LogSession l2 = new LogSession(s2, new DirSink(tmpDir("stop")));
        Thread th = new Thread(new Runnable() {
            public void run() {
                try {
                    l2.run();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
        th.start();
        Thread.sleep(1500);
        s2.stop = true;
        th.join(5000);
        check(!th.isAlive() && l2.rows > 0, "stop flag ends logging");
    }

    public static void main(String[] a) throws Exception {
        guardTests();
        parseTests();
        vectorTests(new File(a[0]));
        demoTests();
        w211Tests();
        List<String> pyHead = null;
        if (a.length > 1) {
            final int port = Integer.parseInt(a[1]);
            pyHead = e2eLog("python-sim", new Session.TransportFactory() {
                public Transport create() {
                    return new Transport.Tcp("127.0.0.1", port);
                }
            }, 2.5, true);
        }
        if (a.length > 2) {
            final int port = Integer.parseInt(a[2]);
            e2eLog("python-sim-frames-chunked", new Session.TransportFactory() {
                public Transport create() {
                    return new Transport.Tcp("127.0.0.1", port);
                }
            }, 2.5, true);
        }
        if (a.length > 3 && !a[3].equals("-")) {
            final int port = Integer.parseInt(a[3]);
            e2eLog("third-party-emulator", new Session.TransportFactory() {
                public Transport create() {
                    return new Transport.Tcp("127.0.0.1", port);
                }
            }, 6, false);
        }
        List<String> w211Head = null;
        if (a.length > 5) {
            final int port = Integer.parseInt(a[5]);
            w211Head = e2eW211("python-sim-w211", new Session.TransportFactory() {
                public Transport create() {
                    return new Transport.Tcp("127.0.0.1", port);
                }
            }, 2.5);
        }
        if (a.length > 6) {
            final int port = Integer.parseInt(a[6]);
            e2eW211("python-sim-w211-iso9141", new Session.TransportFactory() {
                public Transport create() {
                    return new Transport.Tcp("127.0.0.1", port);
                }
            }, 6);           // chunked output is slow: give the slow tier time to cycle once
        }
        if (w211Head != null && a.length > 7) {
            List<String> py = Arrays.asList(lines(new File(a[7])).get(0).split(",", -1));
            check(py.equals(w211Head), "W211 CSV header identical to Python tool\n  py:   " + py + "\n  java: " + w211Head);
        }
        if (pyHead != null && a.length > 4) {
            // same columns as the Python tool on the same simulator
            List<String> py = Arrays.asList(lines(new File(a[4])).get(0).split(",", -1));
            check(py.equals(pyHead), "CSV header identical to Python tool\n  py:   " + py + "\n  java: " + pyHead);
        }
        System.out.println("JVM tests: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }
}
