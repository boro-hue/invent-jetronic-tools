package io.github.borohue.mustangobd;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** OBD services on top of Elm: discovery, DTCs, freeze frame, Mode 06/09, Mode 22. */
public final class Obd {
    private Obd() {
    }

    /** Enhanced (Mode 22) identifier with an optional decoder. */
    public static final class Did {
        public final String ecu;      // request header, e.g. "7E0"
        public final int did;
        public final String name;
        public final String formula;  // human-readable, null = raw only
        public final String status;

        public Did(String ecu, int did, String name, String formula, String status) {
            this.ecu = ecu;
            this.did = did;
            this.name = name;
            this.formula = formula;
            this.status = status;
        }

        public Object decode(byte[] v) {
            int[] d = Pids.ints(v);
            try {
                if ("S16(A,B)/16".equals(formula)) return Pids.s16(d, 0) / 16.0;
                if ("U24(A,B,C)".equals(formula)) return (d[0] << 16) | (d[1] << 8) | d[2];
                if ("ASCII".equals(formula)) return ascii(v);
            } catch (ArrayIndexOutOfBoundsException e) {
                return null;
            }
            return null;
        }
    }

    /** Same candidates as profile_mustang_2016_37.json - UNVERIFIED on the car (raw is always logged too). */
    public static final List<Did> PROFILE = Arrays.asList(
            new Did("7E0", 0x1E1C, "TransFluidTemp_C", "S16(A,B)/16",
                    "CANDIDATE: community-cited Ford 6R80 transmission fluid temp (automatic only). "
                            + "Sanity check: warm fluid about 80-85 C."),
            new Did("7E0", 0xDD01, "Odometer_km", "U24(A,B,C)",
                    "CANDIDATE: Ford total-distance identifier; compare with the cluster."));
    public static final List<Did> IDENT = Arrays.asList(
            new Did("7E0", 0xF190, "VIN", "ASCII", "ISO 14229 identifier"),
            new Did("7E0", 0xF188, "ECU_SoftwareNumber", "ASCII", "ISO 14229 identifier"),
            new Did("7E0", 0xF111, "Ford_F111", "ASCII", "Ford identification range"),
            new Did("7E0", 0xF113, "Ford_F113", "ASCII", "Ford identification range"),
            new Did("7E0", 0xF124, "Ford_F124", "ASCII", "Ford identification range"));

    public static final Map<Integer, String> NRC = new HashMap<Integer, String>();

    static {
        NRC.put(0x10, "generalReject");
        NRC.put(0x11, "serviceNotSupported");
        NRC.put(0x12, "subFunctionNotSupported");
        NRC.put(0x13, "incorrectMessageLength");
        NRC.put(0x14, "responseTooLong");
        NRC.put(0x22, "conditionsNotCorrect");
        NRC.put(0x31, "requestOutOfRange");
        NRC.put(0x33, "securityAccessDenied");
        NRC.put(0x78, "responsePending");
        NRC.put(0x7E, "notSupportedInActiveSession");
        NRC.put(0x7F, "serviceNotSupportedInActiveSession");
    }

    public static String ascii(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) if ((x & 0xFF) >= 32 && (x & 0xFF) < 127) sb.append((char) x);
        return sb.toString().trim();
    }

    /** Address one ECU physically when possible. */
    public static void target(Elm elm, String respHdr) throws IOException {
        elm.setHeader(elm.can11 ? Elm.reqFor(respHdr) : null);
    }

    /** Walk the supported-id bitmaps of service 01/02/06/09 (broadcast). header -> ids (bitmap ids excluded) */
    public static Map<String, List<Integer>> discover(Elm elm, int service, String extra) throws IOException {
        elm.setHeader(null);
        Map<String, TreeSet<Integer>> sup = new TreeMap<String, TreeSet<Integer>>();
        int base = 0;
        int off = extra.isEmpty() ? 2 : 3;
        while (true) {
            Elm.Reply rep = elm.obd(String.format(Locale.US, "%02X%02X%s", service, base, extra));
            for (Object[] p : rep.positive(service)) {
                byte[] m = (byte[]) p[1];
                if (m.length >= off + 4 && (m[1] & 0xFF) == base) {
                    long bits = 0;
                    for (int i = 0; i < 4; i++) bits = (bits << 8) | (m[off + i] & 0xFF);
                    TreeSet<Integer> s = sup.get((String) p[0]);
                    if (s == null) sup.put((String) p[0], s = new TreeSet<Integer>());
                    for (int i = 0; i < 32; i++) if (((bits >> (31 - i)) & 1) != 0) s.add(base + i + 1);
                }
            }
            int nxt = base + 0x20;
            boolean more = false;
            for (TreeSet<Integer> s : sup.values()) if (s.contains(nxt)) more = true;
            if (nxt > 0xE0 || !more) break;
            base = nxt;
        }
        Map<String, List<Integer>> out = new TreeMap<String, List<Integer>>();
        for (Map.Entry<String, TreeSet<Integer>> e : sup.entrySet()) {
            List<Integer> l = new ArrayList<Integer>();
            for (int p : e.getValue()) if (p % 0x20 != 0) l.add(p);
            out.put(e.getKey(), l);
        }
        return out;
    }

    public static String pickPrimary(Map<String, List<Integer>> sup) {
        String best = null;
        for (String h : sup.keySet()) {
            if (best == null) best = h;
            else if (h.equals("7E8") && !best.equals("7E8")) best = h;
            else if (!best.equals("7E8") && sup.get(h).size() > sup.get(best).size()) best = h;
        }
        return best;
    }

    /** Query each PID alone: lengths and sample data. */
    public static void probe(Elm elm, String hdr, List<Integer> pids, Map<Integer, Integer> lengths,
                             Map<Integer, byte[]> samples) throws IOException {
        target(elm, hdr);
        for (int pid : pids) {
            byte[] m = elm.obd(String.format(Locale.US, "01%02X", pid)).positiveFrom(0x01, hdr);
            if (m != null && m.length >= 2 && (m[1] & 0xFF) == pid) {
                lengths.put(pid, m.length - 2);
                samples.put(pid, Arrays.copyOfRange(m, 2, m.length));
            }
        }
    }

    public static String pidReq(List<Integer> pids) {
        StringBuilder sb = new StringBuilder("01");
        for (int p : pids) sb.append(String.format(Locale.US, "%02X", p));
        return sb.toString();
    }

    public static boolean multiOk(Elm elm, String hdr, List<Integer> pids, Map<Integer, Integer> lengths)
            throws IOException {
        List<Integer> sample = new ArrayList<Integer>();
        for (int p : pids) if (lengths.containsKey(p) && sample.size() < 6) sample.add(p);
        if (sample.size() < 2) return false;
        target(elm, hdr);
        byte[] m = elm.obd(pidReq(sample)).positiveFrom(0x01, hdr);
        return m != null && Pids.splitMode01(m, lengths).keySet().containsAll(sample);
    }

    /** How (if at all) the adapter honours the trailing response-count digit. */
    public static String calibrateCount(Elm elm, String hdr) throws IOException {
        elm.countMode = null;
        if (!elm.can11) return null;
        target(elm, hdr);
        Elm.Reply r = elm.obd("0100", 4000, 1);
        if (r.positiveFrom(0x01, hdr) == null || !r.partial.isEmpty()) return null;
        byte[] ref = elm.obd("0902").positiveFrom(0x09, hdr);
        if (ref == null || ref.length <= 7) return elm.countMode = "sf";
        byte[] one = elm.obd("0902", 4000, 1).positiveFrom(0x09, hdr);
        if (one != null && Arrays.equals(one, ref)) return elm.countMode = "messages";
        int n = Elm.framesFor(ref.length);
        if (n <= 15) {
            byte[] many = elm.obd("0902", 4000, n).positiveFrom(0x09, hdr);
            if (many != null && Arrays.equals(many, ref)) return elm.countMode = "frames";
        }
        return elm.countMode = "sf";
    }

    /** -> {"ok", byte[]} | {"nrc", Integer} | {"none", String} */
    public static Object[] readDid(Elm elm, String ecuReq, int did, int frames) throws IOException {
        elm.setHeader(elm.can11 ? ecuReq : null);
        String resp = Elm.respFor(ecuReq);
        int count = frames > 0 ? elm.countFor(frames) : (elm.countMode != null ? 1 : 0);
        List<Integer> nrcs = new ArrayList<Integer>();
        Elm.Reply rep = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            rep = elm.obd(String.format(Locale.US, "22%04X", did), 6000, count);
            byte[] m = rep.positiveFrom(0x22, resp);
            if (m != null && m.length >= 3 && (((m[1] & 0xFF) << 8) | (m[2] & 0xFF)) == did)
                return new Object[]{"ok", Arrays.copyOfRange(m, 3, m.length)};
            nrcs = rep.negative(0x22, resp);
            for (int c : nrcs) if (c != 0x78) return new Object[]{"nrc", c};
            if (attempt == 0 && (!nrcs.isEmpty() || rep.partial.containsKey(resp))) {
                count = 0;   // pending, or cut short by the count digit: ask once more without it
                continue;
            }
            break;
        }
        if (!nrcs.isEmpty()) return new Object[]{"nrc", 0x78};
        return new Object[]{"none", rep.statusText()};
    }

    public static String whyNot(Object[] r) {
        if ("nrc".equals(r[0])) {
            String t = NRC.get((Integer) r[1]);
            return t != null ? t : String.format(Locale.US, "NRC 0x%02X", (Integer) r[1]);
        }
        return String.valueOf(r[1]);
    }

    public static List<String> parseDtcs(byte[] msg) {
        int[] d = Pids.ints(Arrays.copyOfRange(msg, 1, msg.length));
        int start = d.length % 2 == 1 ? 1 : 0;      // CAN: leading count byte
        List<String> out = new ArrayList<String>();
        for (int i = start; i + 1 < d.length; i += 2)
            if (d[i] != 0 || d[i + 1] != 0) out.add(Pids.dtc(d[i], d[i + 1]));
        return out;
    }

    public static Map<String, List<String>> readDtcs(Elm elm, int service) throws IOException {
        elm.setHeader(null);
        Map<String, List<String>> out = new TreeMap<String, List<String>>();
        for (Object[] p : elm.obd(String.format(Locale.US, "%02X", service), 6000, 0).positive(service)) {
            List<String> l = out.get((String) p[0]);
            if (l == null) out.put((String) p[0], l = new ArrayList<String>());
            l.addAll(parseDtcs((byte[]) p[1]));
        }
        return out;
    }

    public static Object decodeMode09(int info, byte[] m) {
        byte[] data = Arrays.copyOfRange(m, 2, m.length);
        int n = data.length > 0 ? data[0] & 0xFF : 0;
        if (info == 0x02 || info == 0x0A) return ascii(Arrays.copyOfRange(data, Math.min(1, data.length), data.length));
        if (info == 0x04) {
            List<String> l = new ArrayList<String>();
            for (int i = 0; i < n && 17 + 16 * i <= data.length; i++)
                l.add(ascii(Arrays.copyOfRange(data, 1 + 16 * i, 17 + 16 * i)));
            return l;
        }
        if (info == 0x06) {
            List<String> l = new ArrayList<String>();
            for (int i = 0; i < n && 5 + 4 * i <= data.length; i++)
                l.add(Elm.toHex(Arrays.copyOfRange(data, 1 + 4 * i, 5 + 4 * i)));
            return l;
        }
        if (info == 0x08 || info == 0x0B) {
            List<Integer> l = new ArrayList<Integer>();
            int[] d = Pids.ints(data);
            for (int i = 0; i < n && 2 + 2 * i < d.length; i++) l.add(Pids.u16(d, 1 + 2 * i));
            return l;
        }
        return Elm.toHex(data);
    }

    public static String readVin(Elm elm, String hdr) throws IOException {
        target(elm, hdr);
        byte[] m = elm.obd("0902", 6000, 0).positiveFrom(0x09, hdr);
        return m != null && m.length > 3 ? (String) decodeMode09(0x02, m) : null;
    }

    // ------------------------------------------------------------------ Mode 06
    /** Unit-and-scaling ids (SAE J1979 appendix E): {scale, unit, signed(1/0)} */
    static final Map<Integer, Object[]> UASID = new HashMap<Integer, Object[]>();

    static {
        Object[][] u = {{0x01, 1.0, ""}, {0x02, 0.1, ""}, {0x03, 0.01, ""}, {0x04, 0.001, ""},
                {0x05, 0.0000305, ""}, {0x06, 0.000305, ""}, {0x07, 0.25, "rpm"}, {0x08, 0.01, "km/h"},
                {0x09, 1.0, "km/h"}, {0x0A, 0.000122, "V"}, {0x0B, 0.001, "V"}, {0x0C, 0.01, "V"},
                {0x0D, 0.00390625, "mA"}, {0x0E, 0.001, "A"}, {0x0F, 0.01, "A"}, {0x10, 1.0, "ms"},
                {0x11, 100.0, "ms"}, {0x12, 1.0, "s"}, {0x13, 1.0, "mOhm"}, {0x14, 1.0, "Ohm"},
                {0x15, 1.0, "kOhm"}, {0x16, 0.1, "C"}, {0x17, 0.01, "kPa"}, {0x18, 0.0117, "kPa"},
                {0x19, 0.079, "kPa"}, {0x1A, 1.0, "kPa"}, {0x1B, 10.0, "kPa"}, {0x1C, 0.01, "deg"},
                {0x1D, 0.5, "deg"}, {0x1E, 0.0000305, "lambda"}, {0x1F, 0.05, "AFR"}, {0x20, 0.0039062, "ratio"},
                {0x21, 1.0, "mHz"}, {0x22, 1.0, "Hz"}, {0x23, 1.0, "kHz"}, {0x24, 1.0, "counts"},
                {0x25, 1.0, "km"}, {0x26, 0.1, "mV/ms"}, {0x27, 0.01, "g/s"}, {0x28, 1.0, "g/s"},
                {0x29, 0.25, "Pa/s"}, {0x2A, 0.001, "kg/h"}, {0x2B, 1.0, "switches"}, {0x2C, 0.01, "g/cyl"},
                {0x2D, 0.01, "mg/stroke"}, {0x2E, 1.0, "bool"}, {0x2F, 0.01, "%"},
                {0x81, 1.0, ""}, {0x82, 0.1, ""}, {0x83, 0.01, ""}, {0x84, 0.001, ""}, {0x85, 0.0000305, ""},
                {0x86, 0.000305, ""}, {0x8A, 0.000122, "V"}, {0x8B, 0.001, "V"}, {0x8C, 0.01, "V"},
                {0x90, 1.0, "ms"}, {0x96, 0.1, "C"}, {0x9C, 0.01, "deg"}, {0xAF, 0.01, "%"}, {0xFE, 0.25, "Pa"}};
        for (Object[] x : u) UASID.put((Integer) x[0], new Object[]{x[1], x[2], (Integer) x[0] >= 0x80});
    }

    public static String midName(int mid) {
        if (mid >= 0xA2 && mid <= 0xAD) return "Misfire cylinder " + (mid - 0xA1);
        switch (mid) {
            case 0xA1: return "Misfire general";
            case 0x01: return "O2 sensor B1S1";
            case 0x02: return "O2 sensor B1S2";
            case 0x05: return "O2 sensor B2S1";
            case 0x06: return "O2 sensor B2S2";
            case 0x21: return "Catalyst bank 1";
            case 0x22: return "Catalyst bank 2";
            case 0x31: return "EGR / VVT";
            case 0x39: return "EVAP 0.150 in leak";
            case 0x3A: return "EVAP 0.090 in leak";
            case 0x3B: return "EVAP 0.040 in leak";
            case 0x3C: return "EVAP 0.020 in leak";
            case 0x3D: return "Purge flow";
            case 0x41: return "O2 heater B1S1";
            case 0x42: return "O2 heater B1S2";
            case 0x45: return "O2 heater B2S1";
            case 0x46: return "O2 heater B2S2";
            case 0x81: return "Fuel system bank 1";
            case 0x82: return "Fuel system bank 2";
            default: return "";
        }
    }

    /** Mode 06 records: list of ordered maps (mid, mid_name, tid, tid_name, uasid, unit, value, min, max, pass). */
    public static List<Map<String, Object>> parseMode06(byte[] m) {
        List<Map<String, Object>> recs = new ArrayList<Map<String, Object>>();
        int[] d = Pids.ints(Arrays.copyOfRange(m, 1, m.length));
        for (int i = 0; i + 8 < d.length; i += 9) {
            int mid = d[i], tid = d[i + 1], uas = d[i + 2];
            double[] v = {Pids.u16(d, i + 3), Pids.u16(d, i + 5), Pids.u16(d, i + 7)};
            Object[] sc = UASID.get(uas);
            String unit = "";
            if (sc != null) {
                for (int k = 0; k < 3; k++) {
                    if ((Boolean) sc[2] && v[k] >= 0x8000) v[k] -= 0x10000;
                    v[k] = v[k] * (Double) sc[0] + (uas == 0x16 ? -40 : 0);
                }
                unit = (String) sc[1];
            }
            Map<String, Object> r = new LinkedHashMap<String, Object>();
            r.put("mid", String.format(Locale.US, "%02X", mid));
            r.put("mid_name", midName(mid));
            r.put("tid", String.format(Locale.US, "%02X", tid));
            r.put("tid_name", mid >= 0xA1 && mid <= 0xAD
                    ? (tid == 0x0B ? "EWMA misfire count (last 10 driving cycles)"
                    : tid == 0x0C ? "misfire count (last/current driving cycle)" : "") : "");
            r.put("uasid", String.format(Locale.US, "%02X", uas));
            r.put("unit", unit);
            r.put("value", num(v[0]));
            r.put("min", num(v[1]));
            r.put("max", num(v[2]));
            r.put("pass", v[1] <= v[0] && v[0] <= v[2]);
            recs.add(r);
        }
        return recs;
    }

    static Object num(double v) {
        // round(v, 6) exactly like Python: half-even on the exact binary value
        double r = new java.math.BigDecimal(v).setScale(6, java.math.RoundingMode.HALF_EVEN).doubleValue();
        return r == Math.rint(r) && Math.abs(r) < 1e15 ? (Object) (long) r : (Object) r;
    }
}
