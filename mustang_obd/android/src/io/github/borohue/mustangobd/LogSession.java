package io.github.borohue.mustangobd;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Continuous CSV logging of every supported PID + profile DIDs. Port of mustang_obd.py --log. */
public class LogSession {
    static final class Item {
        final String kind;            // "pids", "did", "atrv"
        final String ecu;             // response header for pids, request header for dids
        final List<Integer> pids;
        final Obd.Did did;
        final int frames;

        Item(String kind, String ecu, List<Integer> pids, Obd.Did did, int frames) {
            this.kind = kind;
            this.ecu = ecu;
            this.pids = pids;
            this.did = did;
            this.frames = frames;
        }

        @Override
        public String toString() {
            if ("pids".equals(kind)) {
                StringBuilder sb = new StringBuilder(ecu + ":01[");
                for (int i = 0; i < pids.size(); i++)
                    sb.append(i > 0 ? " " : "").append(String.format(Locale.US, "%02X", pids.get(i)));
                return sb.append("]").toString();
            }
            if ("did".equals(kind)) return String.format(Locale.US, "%s:22%04X", did.ecu, did.did);
            return "ATRV";
        }
    }

    final Session sess;
    final Session.Sink sink;
    final Session.Listener ui;
    public double seconds = 0;          // 0 = until stopped
    public boolean noMulti, noCount;
    public Set<Integer> onlyPids;       // null = everything

    // plan
    Map<String, List<Integer>> sup;
    String primary;
    final Map<String, Map<Integer, Integer>> lengths = new LinkedHashMap<String, Map<Integer, Integer>>();
    final Map<String, Object> statics = new LinkedHashMap<String, Object>();
    final List<Item> fast = new ArrayList<Item>(), slow = new ArrayList<Item>();
    final List<String> columns = new ArrayList<String>();
    final Set<String> used = new HashSet<String>();
    final Map<String, List<String[]>> colmap = new LinkedHashMap<String, List<String[]>>();
    final Map<String, String> rawcol = new LinkedHashMap<String, String>();
    final List<Obd.Did> dids = new ArrayList<Obd.Did>();
    String vin;

    public long rows, reconnects;
    public String csvName;

    public LogSession(Session sess, Session.Sink sink) {
        this.sess = sess;
        this.sink = sink;
        this.ui = sess.ui;
    }

    String addCol(String name) {
        String n = name;
        while (used.contains(n)) n = n + "_";
        used.add(n);
        columns.add(n);
        return n;
    }

    static String key(String ecu, int pid) {
        return ecu + ":" + pid;
    }

    void buildPlan(Elm elm) throws IOException, Elm.CarSilentException {
        ui.status("Discovering supported data...");
        sup = Obd.discover(elm, 0x01, "");
        if (sup.isEmpty()) throw new Elm.CarSilentException("no ECU reports supported PIDs");
        primary = Obd.pickPrimary(sup);
        List<String> order = new ArrayList<String>();
        order.add(primary);
        for (String h : sup.keySet()) if (!h.equals(primary)) order.add(h);
        Set<Integer> covered = new HashSet<Integer>();
        for (String hdr : order) {
            Map<Integer, Integer> L = new LinkedHashMap<Integer, Integer>();
            Map<Integer, byte[]> S = new LinkedHashMap<Integer, byte[]>();
            Obd.probe(elm, hdr, sup.get(hdr), L, S);
            lengths.put(hdr, L);
            Map<String, Object> st = new LinkedHashMap<String, Object>();
            for (Map.Entry<Integer, byte[]> e : S.entrySet())
                if (Pids.STATIC.contains(e.getKey())) st.put(String.format(Locale.US, "%02X", e.getKey()),
                        Snapshot.pidEntry(e.getKey(), e.getValue()));
            statics.put(hdr, st);
            List<Integer> pids = new ArrayList<Integer>();
            for (int p : sup.get(hdr))
                if (L.containsKey(p) && !Pids.STATIC.contains(p) && (onlyPids == null || onlyPids.contains(p))
                        && (hdr.equals(primary) || !covered.contains(p))) pids.add(p);
            covered.addAll(pids);
            if (pids.isEmpty()) continue;
            int chunk = !noMulti && Obd.multiOk(elm, hdr, pids, L) ? 6 : 1;
            String prefix = hdr.equals(primary) ? "" : hdr + "_";
            for (int p : pids) {
                List<String[]> cm = new ArrayList<String[]>();
                for (String c : Pids.cols(p)) cm.add(new String[]{c, addCol(prefix + c)});
                colmap.put(key(hdr, p), cm);
            }
            List<Integer> f = new ArrayList<Integer>(), s = new ArrayList<Integer>();
            for (int p : pids) (Pids.FAST.contains(p) && hdr.equals(primary) ? f : s).add(p);
            for (int g = 0; g < 2; g++) {
                List<Integer> group = g == 0 ? f : s;
                for (int i = 0; i < group.size(); i += chunk) {
                    List<Integer> part = new ArrayList<Integer>(group.subList(i, Math.min(group.size(), i + chunk)));
                    int payload = 1;
                    for (int p : part) payload += 1 + L.get(p);
                    (g == 0 ? fast : slow).add(new Item("pids", hdr, part, null, Elm.framesFor(payload)));
                }
            }
        }
        if (primary != null && elm.can11 && !noCount) {
            String mode = Obd.calibrateCount(elm, primary);
            ui.message("response-count shortcut: " + (mode == null ? "not supported by this adapter" : mode));
        }
        if (onlyPids == null) {
            for (Obd.Did d : Obd.PROFILE) {
                Object[] r = Obd.readDid(elm, d.ecu, d.did, 0);
                if ("ok".equals(r[0])) {
                    dids.add(d);
                    colmap.put("did:" + d.ecu + ":" + d.did, java.util.Collections.singletonList(
                            new String[]{d.name, addCol(d.name)}));
                    slow.add(new Item("did", d.ecu, null, d, Elm.framesFor(3 + ((byte[]) r[1]).length)));
                } else {
                    ui.message(String.format(Locale.US, "enhanced %s (%s:22%04X) not available: %s",
                            d.name, d.ecu, d.did, Obd.whyNot(r)));
                }
            }
            slow.add(new Item("atrv", null, null, null, 1));
            colmap.put("atrv", java.util.Collections.singletonList(new String[]{"AdapterVolts_V",
                    addCol("AdapterVolts_V")}));
        }
        for (Item it : concat(fast, slow)) {
            if ("pids".equals(it.kind))
                for (int p : it.pids)
                    rawcol.put(key(it.ecu, p), addCol((it.ecu.equals(primary) ? "" : it.ecu + "_")
                            + String.format(Locale.US, "raw_01%02X", p)));
            else if ("did".equals(it.kind))
                rawcol.put("did:" + it.did.ecu + ":" + it.did.did,
                        addCol(String.format(Locale.US, "raw_%s_22%04X", it.did.ecu, it.did.did)));
        }
        vin = primary == null ? null : Obd.readVin(elm, primary);
    }

    static List<Item> concat(List<Item> a, List<Item> b) {
        List<Item> l = new ArrayList<Item>(a);
        l.addAll(b);
        return l;
    }

    boolean runItem(Elm elm, Item it, Map<String, String> state) throws IOException {
        if ("atrv".equals(it.kind)) {
            Double v = elm.volts();
            state.put(colmap.get("atrv").get(0)[1], Pids.fmt(v));
            return v != null;
        }
        if ("did".equals(it.kind)) {
            Object[] r = Obd.readDid(elm, it.did.ecu, it.did.did, it.frames);
            if (!"ok".equals(r[0])) return false;
            byte[] v = (byte[]) r[1];
            String k = "did:" + it.did.ecu + ":" + it.did.did;
            state.put(rawcol.get(k), Elm.toHex(v));
            state.put(colmap.get(k).get(0)[1], Pids.fmt(it.did.decode(v)));
            return true;
        }
        Obd.target(elm, it.ecu);
        String req = Obd.pidReq(it.pids);
        Elm.Reply rep = elm.obd(req, 4000, elm.countFor(it.frames));
        Map<Integer, byte[]> got = new LinkedHashMap<Integer, byte[]>();
        byte[] m = rep.positiveFrom(0x01, it.ecu);
        if (m != null) got.putAll(Pids.splitMode01(m, lengths.get(it.ecu)));
        if (got.isEmpty() && !rep.partial.isEmpty() && elm.countMode != null) {
            m = elm.obd(req).positiveFrom(0x01, it.ecu);       // cut short by the count digit
            if (m != null) got.putAll(Pids.splitMode01(m, lengths.get(it.ecu)));
        }
        for (Map.Entry<Integer, byte[]> e : got.entrySet()) {
            int pid = e.getKey();
            if (!it.pids.contains(pid)) continue;
            state.put(rawcol.get(key(it.ecu, pid)), Elm.toHex(e.getValue()));
            Object[] dec = Pids.decode(pid, e.getValue());
            List<String[]> cm = colmap.get(key(it.ecu, pid));
            for (int i = 0; i < cm.size() && i < dec.length; i++) state.put(cm.get(i)[1], Pids.fmt(dec[i]));
        }
        return !got.isEmpty();
    }

    Map<String, Object> meta(Elm elm, String started) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("started", started);
        m.put("tool", "Mustang OBD (Android) " + BuildInfo.VERSION);
        Map<String, Object> ad = new LinkedHashMap<String, Object>();
        ad.put("transport", elm.t.name());
        ad.put("adapter", elm.version);
        ad.put("protocol", elm.protoName);
        ad.put("count_mode", elm.countMode);
        m.put("adapter", ad);
        m.put("vin", vin);
        m.put("primary_ecu", primary);
        Map<String, Object> s = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, List<Integer>> e : sup.entrySet()) s.put(e.getKey(), hexList(e.getValue()));
        m.put("supported_pids", s);
        Map<String, Object> ls = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Map<Integer, Integer>> e : lengths.entrySet()) {
            Map<String, Object> x = new LinkedHashMap<String, Object>();
            for (Map.Entry<Integer, Integer> y : e.getValue().entrySet())
                x.put(String.format(Locale.US, "%02X", y.getKey()), y.getValue());
            ls.put(e.getKey(), x);
        }
        m.put("pid_lengths", ls);
        m.put("static_values", statics);
        List<String> ft = new ArrayList<String>(), st = new ArrayList<String>();
        for (Item i : fast) ft.add(i.toString());
        for (Item i : slow) st.add(i.toString());
        m.put("fast_tier", ft);
        m.put("slow_tier", st);
        List<Object> en = new ArrayList<Object>();
        for (Obd.Did d : dids) {
            Map<String, Object> x = new LinkedHashMap<String, Object>();
            x.put("ecu", d.ecu);
            x.put("did", String.format(Locale.US, "%04X", d.did));
            x.put("name", d.name);
            x.put("expr", d.formula);
            x.put("status", d.status);
            en.add(x);
        }
        m.put("enhanced", en);
        m.put("columns", columns);
        Map<String, Object> desc = new LinkedHashMap<String, Object>();
        for (List<Integer> ps : sup.values())
            for (int p : ps) if (Pids.DEFS.containsKey(p)) desc.put(String.format(Locale.US, "%02X", p), Pids.desc(p));
        m.put("pid_descriptions", desc);
        return m;
    }

    static List<String> hexList(List<Integer> l) {
        List<String> o = new ArrayList<String>();
        for (int p : l) o.add(String.format(Locale.US, "%02X", p));
        return o;
    }

    static String csvLine(List<String> cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            String c = cells.get(i) == null ? "" : cells.get(i);
            if (i > 0) sb.append(',');
            if (c.indexOf(',') >= 0 || c.indexOf('"') >= 0) c = '"' + c.replace("\"", "\"\"") + '"';
            sb.append(c);
        }
        return sb.append("\r\n").toString();
    }

    /** Runs until stopped (or `seconds`). */
    public void run() throws IOException, Elm.CarSilentException, Elm.UnsupportedAdapterException {
        Elm elm = sess.connect(true);
        ui.message("Connected: " + elm.version + " via " + elm.t.name() + ", " + elm.protoName);
        buildPlan(elm);
        sess.countMode = elm.countMode;
        if (fast.isEmpty() && slow.isEmpty()) throw new Elm.CarSilentException("nothing to log");
        String base = "mustang_" + Session.stamp();
        csvName = base + ".csv";
        String started = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(new Date());
        OutputStream mo = sink.create(base + ".meta.json", "application/json");
        try {
            mo.write(Json.write(meta(elm, started)).getBytes("UTF-8"));
        } finally {
            mo.close();
        }
        ui.message("VIN " + (vin == null ? "?" : vin) + ", primary ECU " + primary);
        ui.message("fast tier: " + fast);
        ui.message("slow tier: " + slow);
        ui.message("logging to " + sink.where(csvName));

        Writer w = new OutputStreamWriter(sink.create(csvName, "text/csv"), "UTF-8");
        List<String> head = new ArrayList<String>();
        head.add("t_s");
        head.add("clock");
        head.addAll(columns);
        Map<String, String> state = new LinkedHashMap<String, String>();
        SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
        long t0 = System.currentTimeMillis(), lastUi = 0;
        int silent = 0, slowI = 0;
        try {
            w.write(csvLine(head));
            w.flush();
            while (!sess.stop && (seconds <= 0 || System.currentTimeMillis() - t0 < seconds * 1000)) {
                int answered = 0;
                try {
                    for (Item it : fast) if (runItem(sess.elm, it, state)) answered++;
                    if (!slow.isEmpty()) {
                        if (runItem(sess.elm, slow.get(slowI % slow.size()), state)) answered++;
                        slowI++;
                    }
                } catch (IOException e) {
                    reconnects++;
                    ui.message(e.getMessage());
                    sess.reconnect();
                    continue;
                }
                long now = System.currentTimeMillis();
                if (answered > 0) {
                    silent = 0;
                    List<String> row = new ArrayList<String>(columns.size() + 2);
                    row.add(Pids.fmt((now - t0) / 1000.0));
                    row.add(clock.format(new Date(now)));
                    for (String c : columns) row.add(state.containsKey(c) ? state.get(c) : "");
                    w.write(csvLine(row));
                    w.flush();
                    rows++;
                } else if (++silent >= 3) {
                    sess.waitForCar();
                    silent = 0;
                    continue;
                }
                if (now - lastUi >= 500) {
                    lastUi = now;
                    double rate = rows / Math.max((now - t0) / 1000.0, 0.001);
                    ui.status(String.format(Locale.US, "Logging: %d rows, %.1f rows/s", rows, rate));
                    ui.values(new LinkedHashMap<String, String>(state), rows, rate);
                }
            }
        } catch (Session.Stopped ignored) {
            // user pressed Stop
        } finally {
            w.close();
            sess.close();
        }
        double dur = (System.currentTimeMillis() - t0) / 1000.0;
        ui.message(String.format(Locale.US, "Saved %d rows in %.0f s (%.1f rows/s), %d reconnects: %s",
                rows, dur, rows / Math.max(dur, 0.001), reconnects, sink.where(csvName)));
    }
}
