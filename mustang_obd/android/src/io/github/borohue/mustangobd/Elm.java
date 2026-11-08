package io.github.borohue.mustangobd;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ELM327 driver + CAN reply parser. Port of mustang_obd.py (same behaviour).
 *
 * READ-ONLY BY DESIGN: every line sent passes guardAt() or guardObd(). Only the
 * diagnostic read services 01 02 03 06 07 09 0A 22 and a fixed set of adapter
 * commands can be sent; a bare carriage return (which makes an ELM327 repeat its
 * previous command) is refused too.
 */
public class Elm {
    public static final Charset ASCII = Charset.forName("US-ASCII");
    public static final Set<Integer> READ_ONLY_SERVICES =
            new HashSet<Integer>(Arrays.asList(0x01, 0x02, 0x03, 0x06, 0x07, 0x09, 0x0A, 0x22));
    private static final Pattern AT_ALLOWED = Pattern.compile(
            "^AT(Z|WS|D|I|@1|RV|DP|DPN|E[01]|L[01]|S[01]|H[01]|AL|CAF[01]|CFC1|AT[012]|ST[0-9A-F]{2}"
                    + "|SPA?[0-9A-C]|SH[0-9A-F]{3}|CRA|CRA[0-9A-F]{3})$");
    private static final Pattern HEX = Pattern.compile("^[0-9A-F]*$");

    public static class GuardException extends RuntimeException {
        public GuardException(String m) {
            super(m);
        }
    }

    /** Adapter link failed (Bluetooth dropped, no prompt). */
    public static class LinkException extends IOException {
        public LinkException(String m) {
            super(m);
        }
    }

    /** Adapter answers, no ECU does (ignition off?). */
    public static class CarSilentException extends Exception {
        public CarSilentException(String m) {
            super(m);
        }
    }

    /** Adapter cannot be used (e.g. ignores ATH1). */
    public static class UnsupportedAdapterException extends Exception {
        public UnsupportedAdapterException(String m) {
            super(m);
        }
    }

    public interface Trace {
        void trace(String direction, String text);
    }

    public static String guardAt(String cmd) {
        String c = cmd.replace(" ", "").toUpperCase(Locale.US);
        if (!AT_ALLOWED.matcher(c).matches())
            throw new GuardException("adapter command '" + cmd + "' is not on the read-only whitelist");
        return c;
    }

    public static String guardObd(String req) {
        String r = req.replace(" ", "").toUpperCase(Locale.US);
        if (r.length() < 2 || r.length() % 2 != 0 || !HEX.matcher(r).matches())
            throw new GuardException("malformed request '" + req + "'");
        int sid = Integer.parseInt(r.substring(0, 2), 16);
        if (!READ_ONLY_SERVICES.contains(sid))
            throw new GuardException(String.format(Locale.US,
                    "service 0x%02X is not a read service - refusing to send '%s'", sid, req));
        return r;
    }

    // ------------------------------------------------------------------ reply parsing

    public static class Reply {
        public final List<String> lines;
        /** response header -> complete messages */
        public final Map<String, List<byte[]>> msgs = new TreeMap<String, List<byte[]>>();
        public final Map<String, byte[]> partial = new TreeMap<String, byte[]>();
        public final List<String> status = new ArrayList<String>();

        Reply(List<String> lines) {
            this.lines = lines;
        }

        /** [header, message] pairs whose first byte is service + 0x40. */
        public List<Object[]> positive(int service) {
            List<Object[]> out = new ArrayList<Object[]>();
            for (Map.Entry<String, List<byte[]>> e : msgs.entrySet())
                for (byte[] m : e.getValue())
                    if (m.length > 0 && (m[0] & 0xFF) == service + 0x40) out.add(new Object[]{e.getKey(), m});
            return out;
        }

        /** first positive message from this header, or null */
        public byte[] positiveFrom(int service, String hdr) {
            for (Object[] p : positive(service)) if (p[0].equals(hdr)) return (byte[]) p[1];
            return null;
        }

        /** NRC codes from this header for this service */
        public List<Integer> negative(int service, String hdr) {
            List<Integer> out = new ArrayList<Integer>();
            List<byte[]> ms = msgs.get(hdr);
            if (ms != null)
                for (byte[] m : ms)
                    if (m.length >= 3 && (m[0] & 0xFF) == 0x7F && (m[1] & 0xFF) == service) out.add(m[2] & 0xFF);
            return out;
        }

        public String statusText() {
            return status.isEmpty() ? "no answer" : join(status, ", ");
        }
    }

    public static Reply parse(List<String> lines, int hdrLen) {
        Reply rep = new Reply(lines);
        Map<String, Object[]> pending = new LinkedHashMap<String, Object[]>(); // hdr -> {total, buf, nextSn}
        for (String ln : lines) {
            String s = ln.replace(" ", "").toUpperCase(Locale.US);
            if (s.isEmpty() || s.startsWith("SEARCHING") || s.startsWith("BUSINIT")) continue;
            if (!HEX.matcher(s).matches() || s.length() < hdrLen + 2 || (s.length() - hdrLen) % 2 != 0) {
                rep.status.add(ln.trim());
                continue;
            }
            String hdr = s.substring(0, hdrLen);
            byte[] body = hex(s.substring(hdrLen));
            int kind = (body[0] & 0xFF) >> 4;
            if (kind == 0) {
                int n = body[0] & 0x0F;
                if (n == 0 || n > body.length - 1) {
                    rep.status.add(ln.trim());
                    continue;
                }
                add(rep.msgs, hdr, Arrays.copyOfRange(body, 1, 1 + n));
            } else if (kind == 1 && body.length >= 2) {
                int total = ((body[0] & 0x0F) << 8) | (body[1] & 0xFF);
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                b.write(body, 2, body.length - 2);
                pending.put(hdr, new Object[]{total, b, 1});
            } else if (kind == 2) {
                Object[] p = pending.get(hdr);
                if (p == null) continue;
                ByteArrayOutputStream b = (ByteArrayOutputStream) p[1];
                if ((body[0] & 0x0F) != (Integer) p[2]) {      // lost / out-of-order frame
                    rep.partial.put(hdr, b.toByteArray());
                    pending.remove(hdr);
                    continue;
                }
                b.write(body, 1, body.length - 1);
                p[2] = ((Integer) p[2] + 1) & 0x0F;
                int total = (Integer) p[0];
                if (b.size() >= total) {
                    add(rep.msgs, hdr, Arrays.copyOf(b.toByteArray(), total));
                    pending.remove(hdr);
                }
            }
        }
        for (Map.Entry<String, Object[]> e : pending.entrySet())
            rep.partial.put(e.getKey(), ((ByteArrayOutputStream) e.getValue()[1]).toByteArray());
        return rep;
    }

    private static void add(Map<String, List<byte[]>> m, String k, byte[] v) {
        List<byte[]> l = m.get(k);
        if (l == null) m.put(k, l = new ArrayList<byte[]>());
        l.add(v);
    }

    public static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    public static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.US, "%02X", x & 0xFF));
        return sb.toString();
    }

    public static String join(List<String> parts, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    /** CAN frames an ISO-TP message of payloadLen bytes occupies. */
    public static int framesFor(int payloadLen) {
        return payloadLen <= 7 ? 1 : 1 + (payloadLen - 6 + 6) / 7;
    }

    // ------------------------------------------------------------------ driver

    public final Transport t;
    public Trace trace;
    public String version = "?", protoNum = "?", protoName = "?";
    public int hdrLen = 3;
    public boolean can11 = true;
    String header, cra;
    /** response-count shortcut: null, "sf", "messages" or "frames" */
    public String countMode;

    public Elm(Transport t) {
        this.t = t;
    }

    List<String> xfer(String cmd, int timeoutMs) throws IOException {
        if (cmd.isEmpty())
            throw new GuardException("refusing to send an empty line (ELM327 would repeat the last command)");
        t.drain();
        if (trace != null) trace.trace(">>", cmd);
        t.write((cmd + "\r").getBytes(ASCII));
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        long deadline = System.currentTimeMillis() + timeoutMs;
        int prompt;
        while ((prompt = indexOf(buf.toByteArray(), (byte) '>')) < 0) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) {
                if (trace != null) trace.trace("<<", buf.toString("US-ASCII") + " [TIMEOUT]");
                throw new LinkException("adapter did not answer '" + cmd + "' within " + timeoutMs / 1000.0 + " s");
            }
            byte[] b = t.readSome((int) Math.min(left, 50));
            buf.write(b, 0, b.length);
        }
        String text = new String(buf.toByteArray(), 0, prompt, ASCII);
        if (trace != null) trace.trace("<<", text);
        String echo = cmd.replace(" ", "").toUpperCase(Locale.US);
        List<String> out = new ArrayList<String>();
        for (String ln : text.split("[\\r\\n]+")) {
            StringBuilder clean = new StringBuilder();
            for (char ch : ln.toCharArray()) if (ch >= 32 && ch < 127) clean.append(ch);
            String c = clean.toString().trim();
            if (!c.isEmpty() && !c.replace(" ", "").toUpperCase(Locale.US).equals(echo)) out.add(c);
        }
        return out;
    }

    private static int indexOf(byte[] b, byte x) {
        for (int i = 0; i < b.length; i++) if (b[i] == x) return i;
        return -1;
    }

    public List<String> at(String cmd) throws IOException {
        return at(cmd, 2000);
    }

    public List<String> at(String cmd, int timeoutMs) throws IOException {
        return xfer(guardAt(cmd), timeoutMs);
    }

    public Reply obd(String req) throws IOException {
        return obd(req, 4000, 0);
    }

    public Reply obd(String req, int timeoutMs, int count) throws IOException {
        String r = guardObd(req);
        if (count >= 1 && count <= 15) r += Integer.toHexString(count).toUpperCase(Locale.US);
        return parse(xfer(r, timeoutMs), hdrLen);
    }

    public void init(String protocol) throws IOException {
        List<String> lines = null;
        for (int attempt = 0; attempt < 2 && lines == null; attempt++) {
            try {
                lines = at("ATZ", 5000);
            } catch (LinkException e) {
                if (attempt == 1) throw e;
            }
        }
        Pattern ver = Pattern.compile("ELM|STN|OBD|v\\d", Pattern.CASE_INSENSITIVE);
        version = lines.isEmpty() ? "?" : join(lines, " ");
        for (String ln : lines)
            if (ver.matcher(ln).find()) {
                version = ln;
                break;
            }
        for (String c : new String[]{"ATE0", "ATL0", "ATS0", "ATH1", "ATAT1", "ATCAF1"}) at(c);
        at("ATSP" + protocol);
        header = null;
        cra = null;
    }

    private void readProtocol() throws IOException {
        List<String> dpn = at("ATDPN");
        String code = dpn.isEmpty() || dpn.get(0).isEmpty() ? "?"
                : dpn.get(0).substring(dpn.get(0).length() - 1).toUpperCase(Locale.US);
        protoNum = code;
        hdrLen = "79".contains(code) ? 8 : 3;
        can11 = "68".contains(code);
        List<String> dp = at("ATDP");
        protoName = dp.isEmpty() ? "?" : dp.get(0);
    }

    /** First request to the car. */
    public Reply connectCar(String protocol) throws IOException, CarSilentException, UnsupportedAdapterException {
        String[] attempts = "0".equals(protocol) ? new String[]{"0"} : new String[]{protocol, "0"};
        Reply rep = null;
        for (int i = 0; i < attempts.length; i++) {
            if (i > 0) at("ATSP" + attempts[i]);
            rep = obd("0100", 20000, 0);
            readProtocol();
            rep = parse(rep.lines, hdrLen);
            if (!rep.positive(0x01).isEmpty()) break;
        }
        if (rep.positive(0x01).isEmpty()) {
            for (String s : rep.status)
                if (HEX.matcher(s.replace(" ", "").toUpperCase(Locale.US)).matches())
                    throw new UnsupportedAdapterException("The adapter answers without CAN headers (it ignores "
                            + "ATH1). This clone cannot tell ECUs apart - use an ELM327 v1.4+/STN adapter.");
            throw new CarSilentException(rep.statusText());
        }
        if (!"6789".contains(protoNum))
            throw new UnsupportedAdapterException("Protocol " + protoName + " is not CAN.");
        return rep;
    }

    public Double volts() throws IOException {
        Matcher m = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*V", Pattern.CASE_INSENSITIVE).matcher(join(at("ATRV"), " "));
        return m.find() ? Double.valueOf(m.group(1)) : null;
    }

    /** reqHdr = physical address (11-bit, e.g. "7E0"); null = functional broadcast 7DF. */
    public void setHeader(String reqHdr) throws IOException {
        if (!can11) return;
        String target = reqHdr == null ? "7DF" : reqHdr;
        if (!target.equals(header)) {
            at("ATSH" + target);
            header = target;
        }
        String resp = reqHdr == null ? null : respFor(reqHdr);
        String wantCra = resp != null && !(resp.compareTo("7E8") >= 0 && resp.compareTo("7EF") <= 0) ? resp : null;
        if (wantCra == null ? cra != null : !wantCra.equals(cra)) {
            at("ATCRA" + (wantCra == null ? "" : wantCra));
            cra = wantCra;
        }
    }

    public int countFor(int frames) {
        if ("messages".equals(countMode)) return 1;
        if ("frames".equals(countMode)) return frames <= 15 ? frames : 0;
        if ("sf".equals(countMode)) return frames == 1 ? 1 : 0;
        return 0;
    }

    public static String respFor(String reqHdr) {
        return String.format(Locale.US, "%03X", Integer.parseInt(reqHdr, 16) + 8);
    }

    public static String reqFor(String respHdr) {
        if (respHdr.length() != 3) return null;
        return String.format(Locale.US, "%03X", Integer.parseInt(respHdr, 16) - 8);
    }
}
