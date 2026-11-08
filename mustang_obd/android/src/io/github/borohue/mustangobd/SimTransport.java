package io.github.borohue.mustangobd;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * Demo mode: a simulated ELM327 adapter on a simulated 2016 Mustang (PCM 7E0/7E8 and a TCM
 * 7E1/7E9). Port of elm_sim.py. ALL VALUES ARE SYNTHETIC (VIN "SIMULATED0MUSTANG").
 */
public class SimTransport extends Transport {
    private final RxBuffer rx = new RxBuffer();
    private final StringBuilder line = new StringBuilder();
    private final long t0 = System.currentTimeMillis();
    private boolean echo, spaces, headers, connected;
    private String protocol, header;
    public int latencyMs = 15;
    public final List<String> sent = new ArrayList<String>();
    private volatile boolean closed;

    static final String VIN = "SIMULATED0MUSTANG";
    static final int[] PCM_PIDS = {0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F,
            0x10, 0x11, 0x13, 0x15, 0x19, 0x1C, 0x1F, 0x21, 0x2E, 0x2F, 0x30, 0x31, 0x32, 0x33, 0x34, 0x38, 0x3C,
            0x3D, 0x41, 0x42, 0x43, 0x44, 0x45, 0x46, 0x47, 0x49, 0x4A, 0x4C, 0x4D, 0x4E, 0x51};
    static final int[] TCM_PIDS = {0x01, 0x0C, 0x0D, 0xA4};
    static final int[] FREEZE = {0x02, 0x04, 0x05, 0x0C, 0x0D};
    static final int[] MISFIRES = {0, 1, 37, 0, 2, 0};

    public SimTransport() {
        reset();
    }

    private void reset() {
        echo = true;
        spaces = true;
        headers = false;
        protocol = "0";
        connected = false;
        header = "7DF";
    }

    @Override
    public String name() {
        return "Demo (simulated Mustang - synthetic data)";
    }

    @Override
    public void open() {
        closed = false;
    }

    @Override
    public void close() {
        closed = true;
    }

    @Override
    public void drain() {
        rx.clear();
    }

    @Override
    public byte[] readSome(int timeoutMs) throws IOException {
        if (closed) throw new IOException("closed");
        return rx.take(timeoutMs);
    }

    @Override
    public void write(byte[] data) throws IOException {
        if (closed) throw new IOException("closed");
        for (byte b : data) {
            if (b == '\r') {
                String cmd = line.toString();
                line.setLength(0);
                sent.add(cmd);
                if (latencyMs > 0) {
                    try {
                        Thread.sleep(latencyMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                byte[] out = handle(cmd).getBytes(Elm.ASCII);
                rx.put(out, 0, out.length);
            } else {
                line.append((char) b);
            }
        }
    }

    // ------------------------------------------------------------------ adapter

    String handle(String raw) {
        String cmd = raw.trim().toUpperCase(Locale.US).replace(" ", "");
        List<String> out = new ArrayList<String>();
        if (echo) out.add(raw);
        if (cmd.startsWith("AT")) out.addAll(at(cmd.substring(2)));
        else out.addAll(obd(cmd));
        StringBuilder sb = new StringBuilder();
        for (String s : out) sb.append(s).append('\r');
        return sb.append("\r>").toString();
    }

    private List<String> at(String c) {
        if (c.equals("Z") || c.equals("WS")) {
            reset();
            return Arrays.asList("", "ELM327 v1.5");
        }
        if (c.equals("I")) return Arrays.asList("ELM327 v1.5");
        if (c.equals("RV")) return Arrays.asList("14.2V");
        boolean auto = protocol.equals("0") || protocol.startsWith("A");
        if (c.equals("DPN")) return Arrays.asList((auto ? "A" : "") + "6");
        if (c.equals("DP")) return Arrays.asList((auto ? "AUTO, " : "") + "ISO 15765-4 (CAN 11/500)");
        if (c.equals("E0")) echo = false;
        else if (c.equals("E1")) echo = true;
        else if (c.equals("S0")) spaces = false;
        else if (c.equals("S1")) spaces = true;
        else if (c.equals("H0")) headers = false;
        else if (c.equals("H1")) headers = true;
        else if (c.startsWith("SP")) {
            protocol = c.substring(2);
            connected = false;
        } else if (c.startsWith("SH") && c.length() == 5) header = c.substring(2);
        else if (!(c.matches("L[01]|AT[012]|CAF[01]|AL|CFC1|D") || c.startsWith("ST") || c.startsWith("CRA")))
            return Arrays.asList("?");
        return Arrays.asList("OK");
    }

    private List<String> obd(String cmd) {
        if (cmd.isEmpty() || !cmd.matches("[0-9A-F]+")) return Arrays.asList("?");
        int count = 0;
        if (cmd.length() % 2 == 1) {
            count = Integer.parseInt(cmd.substring(cmd.length() - 1), 16);
            cmd = cmd.substring(0, cmd.length() - 1);
        }
        byte[] req = Elm.hex(cmd);
        List<String> lines = new ArrayList<String>();
        if (!connected && (protocol.equals("0") || protocol.startsWith("A"))) lines.add("SEARCHING...");
        connected = true;
        List<String[]> ecus = new ArrayList<String[]>();
        if (header.equals("7DF") || header.equals("7E0")) ecus.add(new String[]{"7E8", "pcm"});
        if (header.equals("7DF") || header.equals("7E1")) ecus.add(new String[]{"7E9", "tcm"});
        List<List<String>> per = new ArrayList<List<String>>();
        for (String[] e : ecus) {
            byte[] r = respond(e[1].equals("pcm"), req);
            if (r != null) per.add(frames(e[0], r));
            if (count > 0 && per.size() >= count) break;
        }
        if (per.isEmpty()) {
            lines.add("NO DATA");
            return lines;
        }
        for (int i = 0; ; i++) {                      // interleave ECUs frame by frame
            boolean any = false;
            for (List<String> f : per)
                if (i < f.size()) {
                    lines.add(f.get(i));
                    any = true;
                }
            if (!any) break;
        }
        return lines;
    }

    private List<String> frames(String hdr, byte[] p) {
        List<byte[]> fr = new ArrayList<byte[]>();
        if (p.length <= 7) {
            byte[] f = new byte[8];
            f[0] = (byte) p.length;
            System.arraycopy(p, 0, f, 1, p.length);
            fr.add(f);
        } else {
            byte[] f = new byte[8];
            f[0] = (byte) (0x10 | (p.length >> 8));
            f[1] = (byte) p.length;
            System.arraycopy(p, 0, f, 2, 6);
            fr.add(f);
            int sn = 1;
            for (int i = 6; i < p.length; i += 7) {
                byte[] c = new byte[8];
                c[0] = (byte) (0x20 | sn);
                System.arraycopy(p, i, c, 1, Math.min(7, p.length - i));
                fr.add(c);
                sn = (sn + 1) & 0x0F;
            }
        }
        List<String> out = new ArrayList<String>();
        String sep = spaces ? " " : "";
        for (byte[] f : fr) {
            StringBuilder sb = new StringBuilder(headers ? hdr : "");
            int start = headers ? 0 : 1;
            int end = headers ? f.length : 1 + Math.min(7, p.length);
            for (int i = start; i < end; i++) {
                if (sb.length() > 0) sb.append(sep);
                sb.append(String.format(Locale.US, "%02X", f[i] & 0xFF));
            }
            out.add(sb.toString());
        }
        return out;
    }

    // ------------------------------------------------------------------ car

    private static byte[] bitmap(int[] ids, int base) {
        TreeSet<Integer> s = new TreeSet<Integer>();
        for (int i : ids) s.add(i);
        if (s.isEmpty() || base > s.last()) return null;
        long bits = 0;
        for (int i = 0; i < 32; i++) {
            int id = base + i + 1;
            if (s.contains(id) || (id == base + 0x20 && id <= s.last())) bits |= 1L << (31 - i);
        }
        return new byte[]{(byte) (bits >> 24), (byte) (bits >> 16), (byte) (bits >> 8), (byte) bits};
    }

    private static boolean has(int[] a, int v) {
        for (int x : a) if (x == v) return true;
        return false;
    }

    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.write(p, 0, p.length);
        return o.toByteArray();
    }

    private static byte[] b(int... v) {
        byte[] r = new byte[v.length];
        for (int i = 0; i < v.length; i++) r[i] = (byte) v[i];
        return r;
    }

    private static byte[] pad(String s, int n) {
        byte[] r = new byte[n];
        byte[] a = s.getBytes(Elm.ASCII);
        System.arraycopy(a, 0, r, 0, Math.min(n, a.length));
        return r;
    }

    double t() {
        return (System.currentTimeMillis() - t0) / 1000.0;
    }

    private byte[] respond(boolean pcm, byte[] req) {
        int sid = req[0] & 0xFF;
        int[] pids = pcm ? PCM_PIDS : TCM_PIDS;
        if (sid == 0x01) {
            if (req.length < 2 || req.length > 7) return null;
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            o.write(0x41);
            for (int i = 1; i < req.length; i++) {
                int p = req[i] & 0xFF;
                byte[] v = p % 0x20 == 0 ? bitmap(pids, p) : has(pids, p) ? pid(p, false) : null;
                if (v != null) {
                    o.write(p);
                    o.write(v, 0, v.length);
                }
            }
            return o.size() > 1 ? o.toByteArray() : null;
        }
        if (sid == 0x02 && pcm && req.length >= 3 && req[2] == 0) {
            int p = req[1] & 0xFF;
            byte[] v = p % 0x20 == 0 ? bitmap(FREEZE, p) : !has(FREEZE, p) ? null
                    : p == 0x02 ? b(0x03, 0x03) : pid(p, true);
            return v == null ? null : cat(b(0x42, p, 0), v);
        }
        if (sid == 0x03) return pcm ? b(0x43, 1, 0x03, 0x03) : b(0x43, 0);
        if (sid == 0x07) return pcm ? b(0x47, 1, 0x01, 0x71) : b(0x47, 0);
        if (sid == 0x0A) return b(0x4A, 0);
        if (sid == 0x06 && pcm && req.length == 2) {
            int mid = req[1] & 0xFF;
            int[] mids = {0x01, 0x21, 0xA1, 0xA2, 0xA3, 0xA4, 0xA5, 0xA6, 0xA7};
            if (mid % 0x20 == 0) {
                byte[] bm = bitmap(mids, mid);
                return bm == null ? null : cat(b(0x46, mid), bm);
            }
            if (mid == 0x01) return b(0x46, 0x01, 0x01, 0x0A, 0x1B, 0x58, 0x0F, 0xA0, 0xFF, 0xFF);
            if (mid == 0x21) return b(0x46, 0x21, 0x80, 0x20, 0, 48, 0, 0, 0, 192);
            if (mid == 0xA1) return b(0x46, 0xA1, 0x0C, 0x24, 0, 40, 0, 0, 0xFF, 0xFF);
            if (mid >= 0xA2 && mid <= 0xA7) {
                int n = MISFIRES[mid - 0xA2];
                return b(0x46, mid, 0x0B, 0x24, 0, n, 0, 0, 0xFF, 0xFF, mid, 0x0C, 0x24, 0, n, 0, 0, 0xFF, 0xFF);
            }
            return null;
        }
        if (sid == 0x09 && req.length == 2) {
            int info = req[1] & 0xFF;
            int[] infos = pcm ? new int[]{0x02, 0x04, 0x06, 0x0A} : new int[]{0x0A};
            if (info % 0x20 == 0) {
                byte[] bm = bitmap(infos, info);
                return bm == null ? null : cat(b(0x49, info), bm);
            }
            if (!has(infos, info)) return null;
            if (info == 0x02) return cat(b(0x49, 2, 1), VIN.getBytes(Elm.ASCII));
            if (info == 0x04) return cat(b(0x49, 4, 1), pad("SIMCAL-PCM-0001", 16));
            if (info == 0x06) return b(0x49, 6, 1, 0x1A, 0x2B, 0x3C, 0x4D);
            return cat(b(0x49, 0x0A, 1), pad(pcm ? "ECM\0-EngineControl" : "TCM\0-TransmissionCtrl", 20));
        }
        if (sid == 0x22 && req.length == 3) {
            int did = ((req[1] & 0xFF) << 8) | (req[2] & 0xFF);
            byte[] v = null;
            if (pcm && did == 0x1E1C) {
                int raw = (int) Math.round(Math.min(85.0, 40.0 + t() * 0.4) * 16);
                v = b(raw >> 8, raw);
            } else if (pcm && did == 0xDD01) v = b(0x01, 0xE2, 0x40);
            else if (pcm && did == 0xF190) v = VIN.getBytes(Elm.ASCII);
            else if (pcm && did == 0xF188) v = "SIM-STRATEGY-01".getBytes(Elm.ASCII);
            return v == null ? b(0x7F, 0x22, 0x31) : cat(b(0x62, req[1] & 0xFF, req[2] & 0xFF), v);
        }
        return b(0x7F, sid, 0x11);
    }

    private byte[] pid(int p, boolean frozen) {
        double t = t();
        double drive = t > 5 ? 0.5 - 0.5 * Math.cos(2 * Math.PI * t / 20) : 0;
        double rpm = frozen ? 2050 : 750 + 2600 * drive;
        double speed = frozen ? 64 : Math.max(0, (rpm - 750) / 26);
        double ect = frozen ? 88 : Math.min(90, 45 + t * 0.6);
        double load = frozen ? 47 : 18 + 55 * drive;
        double tps = 12 + 40 * drive;
        double lambda = 1.0 + 0.02 * Math.sin(t * 2.1);
        int gear = 1 + (int) (speed / 25);
        switch (p) {
            case 0x01: return b(0x81, 0x07, 0x65, 0x00);
            case 0x03: return b(0x02, 0x00);
            case 0x04: return b(pct(load));
            case 0x05: return b((int) ect + 40);
            case 0x06: return b(trim(2.5 * Math.sin(t * 1.7)));
            case 0x07: return b(trim(3.1));
            case 0x08: return b(trim(-1.8 * Math.sin(t * 1.3 + 1)));
            case 0x09: return b(trim(2.3));
            case 0x0B: return b((int) (30 + 60 * drive));
            case 0x0C: return u16(rpm * 4);
            case 0x0D: return b((int) speed);
            case 0x0E: return b((int) ((14 + 18 * (1 - drive) + 64) * 2));
            case 0x0F: return b(65);
            case 0x10: return u16((3.2 + 75 * drive) * 100);
            case 0x11: case 0x4C: return b(pct(tps));
            case 0x13: return b(0x33);
            case 0x15: return b(130, 0xFF);
            case 0x19: return b(128, 0xFF);
            case 0x1C: return b(0x01);
            case 0x1F: return u16(t);
            case 0x21: case 0x4D: return b(0, 0);
            case 0x2E: return b(pct(12));
            case 0x2F: return b(pct(62));
            case 0x30: return b(17);
            case 0x31: return u16(412);
            case 0x32: return u16(-36 & 0xFFFF);
            case 0x33: return b(99);
            case 0x34: return cat(u16(lambda * 32768), b(0x80, 0x10));
            case 0x38: return cat(u16(lambda * 32768), b(0x7F, 0xF0));
            case 0x3C: return u16((420 + 300 * drive + 40) * 10);
            case 0x3D: return u16((410 + 290 * drive + 40) * 10);
            case 0x41: return b(0x00, 0x07, 0xE1, 0x00);
            case 0x42: return u16(14210);
            case 0x43: return u16(load * 255 / 100);
            case 0x44: return u16(32768);
            case 0x45: return b(pct(tps - 10));
            case 0x46: return b(58);
            case 0x47: return b(pct(tps + 2));
            case 0x49: return b(pct(tps + 5));
            case 0x4A: return b(pct(tps / 2 + 3));
            case 0x4E: return u16(5130);
            case 0x51: return b(0x01);
            case 0xA4: return cat(b(0x01, gear << 4), u16(4171 / gear));
            default: return null;
        }
    }

    private static int pct(double v) {
        return (int) Math.round(v * 255 / 100) & 0xFF;
    }

    private static int trim(double v) {
        return (int) Math.round(v * 128 / 100 + 128) & 0xFF;
    }

    private static byte[] u16(double v) {
        int x = (int) v;
        return b((x >> 8) & 0xFF, x & 0xFF);
    }
}
