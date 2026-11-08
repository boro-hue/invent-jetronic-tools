package io.github.borohue.mustangobd;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Standard OBD-II PIDs (SAE J1979): names, descriptions and decoders. Port of mustang_obd.py. */
public final class Pids {
    private Pids() {
    }

    public static final class Def {
        public final String desc;
        public final String[] cols;

        Def(String desc, String... cols) {
            this.desc = desc;
            this.cols = cols;
        }
    }

    public static final Map<Integer, Def> DEFS = new HashMap<Integer, Def>();
    static final String[] O2 = {"B1S1", "B1S2", "B1S3", "B1S4", "B2S1", "B2S2", "B2S3", "B2S4"};
    static final String[] CAT = {"B1S1", "B2S1", "B1S2", "B2S2"};

    /** constant for the whole drive: snapshot / session info only */
    public static final Set<Integer> STATIC = set(0x13, 0x1C, 0x1D, 0x1E, 0x4F, 0x50, 0x51, 0x5F, 0x63, 0x64);
    /** polled every row; everything else rotates through the slow tier */
    public static final Set<Integer> FAST = set(0x04, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E,
            0x10, 0x11, 0x22, 0x23, 0x43, 0x44, 0x45, 0x47, 0x48, 0x49, 0x4A, 0x4B, 0x4C, 0x59, 0x5A, 0x5E,
            0x61, 0x62, 0x66, 0xA2, 0xA4);

    static {
        for (int i = 0x14; i < 0x3C; i++) if (i < 0x1C || (i >= 0x24 && i < 0x2C) || i >= 0x34) FAST.add(i);
        def(0x01, "Monitor status since DTCs cleared (MIL, DTC count)", "MIL", "DtcCount");
        def(0x02, "DTC that caused freeze frame", "FreezeDTC");
        def(0x03, "Fuel system status (1=OL cold, 2=closed loop, 4=OL load/decel, 8=OL fault, 16=CL fault)",
                "FuelSys1", "FuelSys2");
        def(0x04, "Calculated engine load", "Load_pct");
        def(0x05, "Engine coolant temperature", "Coolant_C");
        def(0x06, "Short term fuel trim bank 1", "STFT_B1_pct");
        def(0x07, "Long term fuel trim bank 1", "LTFT_B1_pct");
        def(0x08, "Short term fuel trim bank 2", "STFT_B2_pct");
        def(0x09, "Long term fuel trim bank 2", "LTFT_B2_pct");
        def(0x0A, "Fuel pressure (gauge)", "FuelPress_kPa");
        def(0x0B, "Intake manifold absolute pressure", "MAP_kPa");
        def(0x0C, "Engine speed", "Rpm");
        def(0x0D, "Vehicle speed", "Speed_kmh");
        def(0x0E, "Ignition timing advance (cyl 1)", "Timing_deg");
        def(0x0F, "Intake air temperature", "IAT_C");
        def(0x10, "Mass air flow", "MAF_gs");
        def(0x11, "Absolute throttle position", "Throttle_pct");
        def(0x12, "Commanded secondary air status", "SecAir");
        def(0x13, "O2 sensors present (2-bank layout)", "O2Present");
        for (int i = 0; i < 8; i++)
            def(0x14 + i, "O2 sensor " + O2[i] + " narrowband (voltage, trim)", "O2_" + O2[i] + "_V",
                    "O2_" + O2[i] + "_Trim_pct");
        def(0x1C, "OBD standard the vehicle conforms to", "ObdStandard");
        def(0x1D, "O2 sensors present (4-bank layout)", "O2Present4");
        def(0x1E, "Auxiliary input status (PTO)", "AuxInput");
        def(0x1F, "Run time since engine start", "RunTime_s");
        def(0x21, "Distance traveled with MIL on", "DistMIL_km");
        def(0x22, "Fuel rail pressure (relative to manifold)", "FuelRailRel_kPa");
        def(0x23, "Fuel rail gauge pressure", "FuelRailGauge_kPa");
        for (int i = 0; i < 8; i++)
            def(0x24 + i, "O2 sensor " + O2[i] + " wide range (lambda, voltage)", "Lambda_" + O2[i],
                    "O2_" + O2[i] + "_WR_V");
        def(0x2C, "Commanded EGR", "CmdEGR_pct");
        def(0x2D, "EGR error", "EGRError_pct");
        def(0x2E, "Commanded evaporative purge", "CmdPurge_pct");
        def(0x2F, "Fuel tank level input", "FuelLevel_pct");
        def(0x30, "Warm-ups since codes cleared", "WarmupsSinceClear");
        def(0x31, "Distance traveled since codes cleared", "DistSinceClear_km");
        def(0x32, "Evap system vapor pressure", "EvapPress_Pa");
        def(0x33, "Absolute barometric pressure", "Baro_kPa");
        for (int i = 0; i < 8; i++)
            def(0x34 + i, "O2 sensor " + O2[i] + " wide range (lambda, current)", "Lambda_" + O2[i],
                    "O2_" + O2[i] + "_mA");
        for (int i = 0; i < 4; i++) def(0x3C + i, "Catalyst temperature " + CAT[i], "CatTemp_" + CAT[i] + "_C");
        def(0x41, "Monitor status this drive cycle (bitfield, raw)");
        def(0x42, "Control module voltage", "ModuleVolts_V");
        def(0x43, "Absolute load value", "AbsLoad_pct");
        def(0x44, "Commanded equivalence ratio (lambda)", "CmdLambda");
        def(0x45, "Relative throttle position", "RelThrottle_pct");
        def(0x46, "Ambient air temperature", "Ambient_C");
        def(0x47, "Absolute throttle position B", "ThrottleB_pct");
        def(0x48, "Absolute throttle position C", "ThrottleC_pct");
        def(0x49, "Accelerator pedal position D", "PedalD_pct");
        def(0x4A, "Accelerator pedal position E", "PedalE_pct");
        def(0x4B, "Accelerator pedal position F", "PedalF_pct");
        def(0x4C, "Commanded throttle actuator", "CmdThrottle_pct");
        def(0x4D, "Time run with MIL on", "TimeMIL_min");
        def(0x4E, "Time since trouble codes cleared", "TimeSinceClear_min");
        def(0x4F, "Maximum values: lambda, O2 voltage, O2 current, MAP", "MaxLambda", "MaxO2_V", "MaxO2_mA",
                "MaxMAP_kPa");
        def(0x50, "Maximum MAF value", "MaxMAF_gs");
        def(0x51, "Fuel type", "FuelType");
        def(0x52, "Ethanol fuel %", "Ethanol_pct");
        def(0x53, "Absolute evap system vapor pressure", "EvapAbs_kPa");
        def(0x54, "Evap system vapor pressure (wide)", "EvapPress2_Pa");
        def(0x55, "Short term secondary O2 trim bank 1", "STFT2_B1_pct");
        def(0x56, "Long term secondary O2 trim bank 1", "LTFT2_B1_pct");
        def(0x57, "Short term secondary O2 trim bank 2", "STFT2_B2_pct");
        def(0x58, "Long term secondary O2 trim bank 2", "LTFT2_B2_pct");
        def(0x59, "Fuel rail absolute pressure", "FuelRailAbs_kPa");
        def(0x5A, "Relative accelerator pedal position", "RelPedal_pct");
        def(0x5B, "Hybrid battery pack remaining life", "HybridBatt_pct");
        def(0x5C, "Engine oil temperature", "OilTemp_C");
        def(0x5D, "Fuel injection timing", "InjTiming_deg");
        def(0x5E, "Engine fuel rate", "FuelRate_Lh");
        def(0x5F, "Emission requirements", "EmissionReq");
        def(0x61, "Driver's demand engine torque", "DemandTorque_pct");
        def(0x62, "Actual engine torque", "ActualTorque_pct");
        def(0x63, "Engine reference torque", "RefTorque_Nm");
        def(0x64, "Engine percent torque data", "TorqueIdle_pct", "TorquePt1_pct", "TorquePt2_pct",
                "TorquePt3_pct", "TorquePt4_pct");
        def(0x66, "Mass air flow sensors A/B", "MAF_A_gs", "MAF_B_gs");
        def(0x67, "Engine coolant temperature sensors 1/2", "ECT1_C", "ECT2_C");
        def(0x8E, "Engine friction percent torque", "FrictionTorque_pct");
        def(0xA2, "Cylinder fuel rate", "CylFuelRate_mg");
        def(0xA4, "Transmission actual gear", "Gear", "GearRatio");
        def(0xA6, "Odometer", "Odometer_km");
    }

    private static Set<Integer> set(Integer... v) {
        return new HashSet<Integer>(Arrays.asList(v));
    }

    private static void def(int pid, String desc, String... cols) {
        DEFS.put(pid, new Def(desc, cols));
    }

    public static String desc(int pid) {
        Def d = DEFS.get(pid);
        return d == null ? "(no decoder - raw only)" : d.desc;
    }

    public static String[] cols(int pid) {
        Def d = DEFS.get(pid);
        return d == null ? new String[0] : d.cols;
    }

    static double pct(int a) {
        return a * 100.0 / 255.0;
    }

    static double trim(int a) {
        return (a - 128) * 100.0 / 128.0;
    }

    static int u16(int[] d, int i) {
        return (d[i] << 8) | d[i + 1];
    }

    static int s16(int[] d, int i) {
        int v = u16(d, i);
        return (v & 0x8000) != 0 ? v - 0x10000 : v;
    }

    public static String dtc(int a, int b) {
        return String.format("%c%d%X%X%X", "PCBU".charAt(a >> 6), (a >> 4) & 3, a & 0xF, b >> 4, b & 0xF);
    }

    public static int[] ints(byte[] b) {
        int[] d = new int[b.length];
        for (int i = 0; i < b.length; i++) d[i] = b[i] & 0xFF;
        return d;
    }

    /** Decoded values aligned with cols(pid); an entry is null when not applicable/short data. */
    public static Object[] decode(int pid, byte[] data) {
        int[] d = ints(data);
        String[] cols = cols(pid);
        Object[] out = new Object[cols.length];
        for (int k = 0; k < cols.length; k++) {
            try {
                out[k] = one(pid, k, d);
            } catch (ArrayIndexOutOfBoundsException e) {
                out[k] = null;
            }
        }
        return out;
    }

    private static Object one(int pid, int k, int[] d) {
        if (pid >= 0x14 && pid <= 0x1B) return k == 0 ? (Object) (d[0] / 200.0) : (d[1] == 0xFF ? null : trim(d[1]));
        if (pid >= 0x24 && pid <= 0x2B) return k == 0 ? u16(d, 0) * 2 / 65536.0 : u16(d, 2) * 8 / 65536.0;
        if (pid >= 0x34 && pid <= 0x3B) return k == 0 ? u16(d, 0) * 2 / 65536.0 : u16(d, 2) / 256.0 - 128;
        if (pid >= 0x3C && pid <= 0x3F) return u16(d, 0) / 10.0 - 40;
        switch (pid) {
            case 0x01: return k == 0 ? d[0] >> 7 : d[0] & 0x7F;
            case 0x02: return (d[0] | d[1]) != 0 ? dtc(d[0], d[1]) : "none";
            case 0x03: return d[k];
            case 0x04: case 0x11: case 0x2C: case 0x2E: case 0x2F: case 0x45: case 0x47: case 0x48:
            case 0x49: case 0x4A: case 0x4B: case 0x4C: case 0x52: case 0x5A: case 0x5B:
                return pct(d[0]);
            case 0x05: case 0x0F: case 0x46: case 0x5C: return d[0] - 40;
            case 0x06: case 0x07: case 0x08: case 0x09: case 0x2D: case 0x55: case 0x56: case 0x57: case 0x58:
                return trim(d[0]);
            case 0x0A: return d[0] * 3;
            case 0x0B: case 0x0D: case 0x12: case 0x13: case 0x1C: case 0x1D: case 0x30: case 0x33:
            case 0x51: case 0x5F:
                return d[0];
            case 0x0C: return u16(d, 0) / 4.0;
            case 0x0E: return d[0] / 2.0 - 64;
            case 0x10: return u16(d, 0) / 100.0;
            case 0x1E: return d[0] & 1;
            case 0x1F: case 0x21: case 0x31: case 0x4D: case 0x4E: case 0x63: return u16(d, 0);
            case 0x22: return u16(d, 0) * 0.079;
            case 0x23: case 0x59: return u16(d, 0) * 10;
            case 0x32: return s16(d, 0) / 4.0;
            case 0x42: return u16(d, 0) / 1000.0;
            case 0x43: return u16(d, 0) * 100 / 255.0;
            case 0x44: return u16(d, 0) * 2 / 65536.0;
            case 0x4F: return k == 3 ? d[3] * 10 : d[k];
            case 0x50: return d[0] * 10;
            case 0x53: return u16(d, 0) / 200.0;
            case 0x54: return u16(d, 0) - 32767;
            case 0x5D: return u16(d, 0) / 128.0 - 210;
            case 0x5E: return u16(d, 0) / 20.0;
            case 0x61: case 0x62: case 0x8E: return d[0] - 125;
            case 0x64: return d[k] - 125;
            case 0x66: return (d[0] & (1 << k)) != 0 ? (Object) (u16(d, 1 + 2 * k) / 32.0) : null;
            case 0x67: return (d[0] & (1 << k)) != 0 ? (Object) (d[1 + k] - 40) : null;
            case 0xA2: return u16(d, 0) / 32.0;
            case 0xA4: return k == 0 ? d[1] >> 4 : u16(d, 2) / 1000.0;
            case 0xA6: return ((long) d[0] << 24 | d[1] << 16 | d[2] << 8 | d[3]) / 10.0;
            default: return null;
        }
    }

    /** Same text format as the Python tool: 4 decimals (round-half-even), trailing zeros stripped. */
    public static String fmt(Object v) {
        if (v == null) return "";
        if (v instanceof Double || v instanceof Float) {
            double x = ((Number) v).doubleValue();
            if (Double.isNaN(x) || Double.isInfinite(x)) return "";
            String s = new BigDecimal(x).setScale(4, RoundingMode.HALF_EVEN).toPlainString();
            if (s.contains(".")) s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
            return s.equals("-0") || s.isEmpty() ? "0" : s;
        }
        return String.valueOf(v);
    }

    /** Split a (multi-PID) 0x41 response into pid -> data using known lengths. */
    public static Map<Integer, byte[]> splitMode01(byte[] msg, Map<Integer, Integer> lengths) {
        Map<Integer, byte[]> out = new HashMap<Integer, byte[]>();
        if (msg == null || msg.length == 0 || (msg[0] & 0xFF) != 0x41) return out;
        int i = 1;
        while (i < msg.length) {
            int pid = msg[i] & 0xFF;
            Integer n = lengths.get(pid);
            if (n == null || i + 1 + n > msg.length) break;
            out.put(pid, Arrays.copyOfRange(msg, i + 1, i + 1 + n));
            i += 1 + n;
        }
        return out;
    }
}
