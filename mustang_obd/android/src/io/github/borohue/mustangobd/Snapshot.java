package io.github.borohue.mustangobd;

import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** One-shot dump of everything the car reports. Port of mustang_obd.py --snapshot. */
public class Snapshot {
    final Session sess;
    final Session.Sink sink;
    public Map<String, Object> snap;
    public String text;
    public String jsonName, txtName;
    public List<Obd.Did> profile = Obd.PROFILE;   // enhanced (Mode 22) identifiers for this car
    public String toolName = "Mustang OBD (Android)";
    /** hands-free: wait for the ignition instead of failing when the car is still off */
    public boolean waitForCar;

    public Snapshot(Session sess, Session.Sink sink) {
        this.sess = sess;
        this.sink = sink;
    }

    static Map<String, Object> pidEntry(int pid, byte[] data) {
        Map<String, Object> e = new LinkedHashMap<String, Object>();
        e.put("desc", Pids.desc(pid));
        e.put("raw", Elm.toHex(data));
        Map<String, Object> vals = new LinkedHashMap<String, Object>();
        Object[] dec = Pids.decode(pid, data);
        String[] cols = Pids.cols(pid);
        for (int i = 0; i < cols.length; i++) vals.put(cols[i], dec[i]);
        e.put("values", vals);
        return e;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> ecu(Map<String, Object> ecus, String hdr) {
        Map<String, Object> e = (Map<String, Object>) ecus.get(hdr);
        if (e == null) ecus.put(hdr, e = new LinkedHashMap<String, Object>());
        return e;
    }

    public void run() throws IOException, Elm.CarSilentException, Elm.UnsupportedAdapterException {
        Session.Listener ui = sess.ui;
        Elm elm = sess.connect(waitForCar);
        try {
            ui.message("Connected: " + elm.version + " via " + elm.t.name() + ", " + elm.protoName);
            snap = new LinkedHashMap<String, Object>();
            snap.put("created", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(new Date()));
            snap.put("tool", toolName + " " + BuildInfo.VERSION);
            Map<String, Object> ad = new LinkedHashMap<String, Object>();
            ad.put("transport", elm.t.name());
            ad.put("adapter", elm.version);
            ad.put("protocol", elm.protoName);
            snap.put("adapter", ad);
            snap.put("battery_V", elm.volts());
            Map<String, Object> ecus = new LinkedHashMap<String, Object>();
            snap.put("ecus", ecus);

            ui.status("Snapshot: supported PIDs...");
            Map<String, List<Integer>> sup = Obd.discover(elm, 0x01, "");
            String primary = Obd.pickPrimary(sup);
            snap.put("primary_ecu", primary);
            for (Map.Entry<String, List<Integer>> e : sup.entrySet()) {
                sess.checkStop();
                String hdr = e.getKey();
                ui.status("Snapshot: ECU " + hdr + ", " + e.getValue().size() + " live PIDs...");
                Map<Integer, Integer> L = new LinkedHashMap<Integer, Integer>();
                Map<Integer, byte[]> S = new LinkedHashMap<Integer, byte[]>();
                Obd.probe(elm, hdr, e.getValue(), L, S);
                Map<String, Object> live = new LinkedHashMap<String, Object>();
                for (int pid : e.getValue()) {
                    String k = String.format(Locale.US, "%02X", pid);
                    if (S.containsKey(pid)) live.put(k, pidEntry(pid, S.get(pid)));
                    else {
                        Map<String, Object> x = new LinkedHashMap<String, Object>();
                        x.put("desc", Pids.desc(pid));
                        x.put("raw", null);
                        x.put("note", "advertised but no answer");
                        live.put(k, x);
                    }
                }
                Map<String, Object> en = ecu(ecus, hdr);
                en.put("mode01_supported", LogSession.hexList(e.getValue()));
                en.put("live", live);
            }

            ui.status("Snapshot: vehicle info (Mode 09)...");
            for (Map.Entry<String, List<Integer>> e : Obd.discover(elm, 0x09, "").entrySet()) {
                String hdr = e.getKey();
                Obd.target(elm, hdr);
                Map<String, Object> info = new LinkedHashMap<String, Object>();
                for (int it : e.getValue()) {
                    byte[] m = Obd.mode09Message(elm.obd(String.format(Locale.US, "09%02X", it), 6000, 0), hdr,
                            it, elm.can);
                    if (m != null) info.put(String.format(Locale.US, "%02X", it), Obd.decodeMode09(it, m));
                }
                Map<String, Object> en = ecu(ecus, hdr);
                en.put("mode09", info);
                String[][] names = {{"02", "VIN"}, {"04", "CALID"}, {"06", "CVN"}, {"08", "InUsePerf"},
                        {"0A", "EcuName"}};
                for (String[] n : names) if (info.containsKey(n[0])) en.put(n[1], info.get(n[0]));
            }

            ui.status("Snapshot: trouble codes...");
            Map<String, Object> dtcs = new LinkedHashMap<String, Object>();
            dtcs.put("stored", Obd.readDtcs(elm, 0x03));
            dtcs.put("pending", Obd.readDtcs(elm, 0x07));
            dtcs.put("permanent", Obd.readDtcs(elm, 0x0A));
            snap.put("dtcs", dtcs);

            ui.status("Snapshot: freeze frame...");
            for (Map.Entry<String, List<Integer>> e : Obd.discover(elm, 0x02, "00").entrySet()) {
                String hdr = e.getKey();
                Obd.target(elm, hdr);
                Map<String, Object> ff = new LinkedHashMap<String, Object>();
                for (int pid : e.getValue()) {
                    byte[] m = elm.obd(String.format(Locale.US, "02%02X00", pid)).positiveFrom(0x02, hdr);
                    if (m != null && m.length >= 3 && (m[1] & 0xFF) == pid)
                        ff.put(String.format(Locale.US, "%02X", pid),
                                pidEntry(pid, java.util.Arrays.copyOfRange(m, 3, m.length)));
                }
                ecu(ecus, hdr).put("freeze_frame", ff);
            }

            ui.status("Snapshot: monitor results / misfire counts (Mode 06)...");
            if (!elm.can)
                snap.put("mode06_note", "Mode 06 is only decoded on CAN (K-line uses an older, different layout)");
            Map<String, List<Integer>> mids = elm.can ? Obd.discover(elm, 0x06, "")
                    : new java.util.TreeMap<String, List<Integer>>();
            for (Map.Entry<String, List<Integer>> e : mids.entrySet()) {
                String hdr = e.getKey();
                Obd.target(elm, hdr);
                List<Map<String, Object>> recs = new ArrayList<Map<String, Object>>();
                for (int mid : e.getValue()) {
                    byte[] m = elm.obd(String.format(Locale.US, "06%02X", mid), 6000, 0).positiveFrom(0x06, hdr);
                    if (m != null) recs.addAll(Obd.parseMode06(m));
                }
                ecu(ecus, hdr).put("mode06", recs);
            }

            ui.status("Snapshot: enhanced data (Mode 22)...");
            if (elm.can11 && primary != null) Obd.calibrateCount(elm, primary);
            Map<String, Object> enh = new LinkedHashMap<String, Object>();
            List<Obd.Did> all = new ArrayList<Obd.Did>(Obd.IDENT);
            all.addAll(profile);
            for (Obd.Did d : elm.can11 ? all : new ArrayList<Obd.Did>()) {
                Object[] r = Obd.readDid(elm, d.ecu, d.did, 0);
                Map<String, Object> x = new LinkedHashMap<String, Object>();
                x.put("name", d.name);
                if ("ok".equals(r[0])) {
                    x.put("raw", Elm.toHex((byte[]) r[1]));
                    x.put("value", d.decode((byte[]) r[1]));
                    x.put("status", d.status);
                } else {
                    x.put("error", r[0] + ": " + Obd.whyNot(r));
                }
                enh.put(String.format(Locale.US, "%s:%04X", d.ecu, d.did), x);
            }
            snap.put("enhanced", enh);
        } finally {
            sess.close();
        }
        text = render(snap);
        String base = "mustang_snapshot_" + Session.stamp();
        jsonName = base + ".json";
        txtName = base + ".txt";
        OutputStream o = sink.create(jsonName, "application/json");
        try {
            o.write(Json.write(snap).getBytes("UTF-8"));
        } finally {
            o.close();
        }
        o = sink.create(txtName, "text/plain");
        try {
            o.write(text.getBytes("UTF-8"));
        } finally {
            o.close();
        }
        ui.message("Saved " + sink.where(jsonName) + " and .txt");
    }

    @SuppressWarnings("unchecked")
    public static String render(Map<String, Object> snap) {
        StringBuilder L = new StringBuilder();
        Map<String, Object> ad = (Map<String, Object>) snap.get("adapter");
        L.append("OBD snapshot ").append(snap.get("created")).append("\n");
        L.append("Adapter: ").append(ad.get("adapter")).append(" via ").append(ad.get("transport")).append("\n");
        L.append("Protocol: ").append(ad.get("protocol")).append("   Battery: ").append(snap.get("battery_V"))
                .append(" V\n\nTROUBLE CODES\n");
        Map<String, Object> dt = (Map<String, Object>) snap.get("dtcs");
        for (String kind : new String[]{"stored", "pending", "permanent"}) {
            List<String> codes = new ArrayList<String>();
            for (Map.Entry<String, List<String>> e : ((Map<String, List<String>>) dt.get(kind)).entrySet())
                for (String c : e.getValue()) codes.add(c + " (" + e.getKey() + ")");
            L.append(String.format(Locale.US, "  %-10s %s%n", kind, codes.isEmpty() ? "none" : Elm.join(codes, ", ")));
        }
        L.append("\n");
        for (Map.Entry<String, Object> ee : ((Map<String, Object>) snap.get("ecus")).entrySet()) {
            Map<String, Object> e = (Map<String, Object>) ee.getValue();
            L.append("ECU ").append(ee.getKey());
            if (e.get("EcuName") != null) L.append("  ").append(e.get("EcuName"));
            if (ee.getKey().equals(snap.get("primary_ecu"))) L.append("  (primary)");
            L.append("\n");
            for (String k : new String[]{"VIN", "CALID", "CVN"})
                if (e.get(k) != null) {
                    Object v = e.get(k);
                    L.append(String.format(Locale.US, "  %-6s %s%n", k,
                            v instanceof List ? Elm.join(strs((List<Object>) v), ", ") : v));
                }
            Map<String, Object> live = (Map<String, Object>) e.get("live");
            if (live != null) {
                L.append("  live data (").append(live.size()).append(" PIDs):\n");
                for (Map.Entry<String, Object> p : live.entrySet()) {
                    Map<String, Object> v = (Map<String, Object>) p.getValue();
                    L.append("    ").append(p.getKey()).append("  ").append(vals(v)).append("\n");
                }
            }
            Map<String, Object> ff = (Map<String, Object>) e.get("freeze_frame");
            if (ff != null && !ff.isEmpty()) {
                L.append("  freeze frame:\n");
                for (Map.Entry<String, Object> p : ff.entrySet())
                    L.append("    ").append(p.getKey()).append("  ").append(vals((Map<String, Object>) p.getValue()))
                            .append("\n");
            }
            List<Map<String, Object>> m6 = (List<Map<String, Object>>) e.get("mode06");
            if (m6 != null && !m6.isEmpty()) {
                int fails = 0;
                L.append("  misfire counts (Mode 06):\n");
                for (Map<String, Object> r : m6) {
                    if (!(Boolean) r.get("pass")) fails++;
                    if (String.valueOf(r.get("mid_name")).startsWith("Misfire cyl"))
                        L.append(String.format(Locale.US, "    %-20s %s: %s%n", r.get("mid_name"),
                                "".equals(r.get("tid_name")) ? "TID " + r.get("tid") : r.get("tid_name"), r.get("value")));
                }
                L.append("  Mode 06 tests: ").append(m6.size()).append(" results, ").append(fails)
                        .append(" outside limits\n");
                for (Map<String, Object> r : m6)
                    if (!(Boolean) r.get("pass"))
                        L.append(String.format(Locale.US, "    FAIL %s/%s %s: %s not in [%s, %s] %s%n", r.get("mid"),
                                r.get("tid"), r.get("mid_name"), r.get("value"), r.get("min"), r.get("max"), r.get("unit")));
            }
            L.append("\n");
        }
        if (snap.get("mode06_note") != null) L.append("Mode 06: ").append(snap.get("mode06_note")).append("\n\n");
        Map<String, Object> enh = (Map<String, Object>) snap.get("enhanced");
        if (enh != null && !enh.isEmpty()) {
            L.append("ENHANCED (Mode 22)\n");
            for (Map.Entry<String, Object> p : enh.entrySet()) {
                Map<String, Object> v = (Map<String, Object>) p.getValue();
                L.append(String.format(Locale.US, "  %s  %-24s %s%n", p.getKey(), v.get("name"),
                        v.containsKey("raw") ? Pids.fmt(v.get("value")) + "  raw " + v.get("raw") : v.get("error")));
            }
        }
        return L.toString();
    }

    static List<String> strs(List<Object> l) {
        List<String> o = new ArrayList<String>();
        for (Object x : l) o.add(String.valueOf(x));
        return o;
    }

    @SuppressWarnings("unchecked")
    static String vals(Map<String, Object> v) {
        Map<String, Object> values = (Map<String, Object>) v.get("values");
        List<String> parts = new ArrayList<String>();
        if (values != null) for (Map.Entry<String, Object> x : values.entrySet())
            parts.add(x.getKey() + "=" + Pids.fmt(x.getValue()));
        String d = String.valueOf(v.get("desc"));
        if (d.length() > 40) d = d.substring(0, 40);
        String tail = !parts.isEmpty() ? Elm.join(parts, ", ")
                : v.get("raw") != null ? String.valueOf(v.get("raw")) : String.valueOf(v.get("note"));
        return String.format(Locale.US, "%-40s %s", d, tail);
    }
}
