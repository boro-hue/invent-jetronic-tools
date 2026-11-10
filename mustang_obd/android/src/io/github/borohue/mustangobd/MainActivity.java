package io.github.borohue.mustangobd;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity {
    static final int REQ_PERMS = 1;
    static final String P_BT_CONNECT = "android.permission.BLUETOOTH_CONNECT";
    static final String P_BT_SCAN = "android.permission.BLUETOOTH_SCAN";
    static final String P_NOTIFY = "android.permission.POST_NOTIFICATIONS";

    private SharedPreferences prefs;
    private TextView adapterView, statusView, gaugesView, valuesView, messagesView;
    private Button chooseBtn, logBtn, snapBtn, shareBtn;
    private CheckBox traceBox;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable pending;
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 500);
        }
    };

    // ------------------------------------------------------------------ UI

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView text(float sp, boolean mono, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTypeface(mono ? Typeface.MONOSPACE : Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        t.setPadding(0, dp(4), 0, dp(4));
        t.setTextIsSelectable(true);
        return t;
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        return b;
    }

    private LinearLayout row(View... views) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (View v : views) r.addView(v);
        return r;
    }

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(8), dp(12), dp(8));

        TextView title = text(20, false, true);
        title.setText(AppState.car().appName + "  " + BuildInfo.VERSION);
        setTitle(AppState.car().appName);
        title.setTextIsSelectable(false);
        root.addView(title);
        adapterView = text(14, false, false);
        root.addView(adapterView);

        chooseBtn = button("Adapter...", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseAdapter();
            }
        });
        logBtn = button("Start log", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (AppState.running()) AppState.stop();
                else startJob("log");
            }
        });
        snapBtn = button("Snapshot", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startJob("snapshot");
            }
        });
        root.addView(row(chooseBtn, logBtn, snapBtn));

        shareBtn = button("Share files", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                share();
            }
        });
        traceBox = new CheckBox(this);
        traceBox.setText("Adapter trace");
        traceBox.setChecked(prefs.getBoolean("trace", false));
        traceBox.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.edit().putBoolean("trace", traceBox.isChecked()).apply();
            }
        });
        traceBox.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        root.addView(row(shareBtn, traceBox));

        statusView = text(15, false, true);
        root.addView(statusView);

        ScrollView scroll = new ScrollView(this);
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        gaugesView = text(18, true, true);
        valuesView = text(12, true, false);
        messagesView = text(11, true, false);
        inner.addView(gaugesView);
        inner.addView(valuesView);
        inner.addView(messagesView);
        scroll.addView(inner);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(ticker);
    }

    private static String v(Map<String, String> m, String k) {
        String s = m.get(k);
        return s == null || s.isEmpty() ? "-" : s;
    }

    private static String num(Map<String, String> m, String k, String fmt) {
        try {
            return String.format(Locale.US, fmt, Double.parseDouble(m.get(k)));
        } catch (RuntimeException e) {
            return "-";
        }
    }

    void refresh() {
        boolean running = AppState.running();
        String kind = prefs.getString("kind", null);
        adapterView.setText("Adapter: " + (kind == null ? "(none chosen)" : prefs.getString("label", kind)));
        chooseBtn.setEnabled(!running);
        snapBtn.setEnabled(!running);
        logBtn.setEnabled(!running || "log".equals(AppState.job));
        logBtn.setText(running && "log".equals(AppState.job) ? "Stop" : "Start log");
        if (!running && kind == null)
            statusView.setText("Choose your OBD adapter (pair it in Android Bluetooth settings first), or try Demo.");
        else statusView.setText(AppState.status);
        if (running) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        Map<String, String> m = AppState.values;
        if (!m.isEmpty()) {
            String volts = m.containsKey("AdapterVolts_V") ? v(m, "AdapterVolts_V") : v(m, "ModuleVolts_V");
            gaugesView.setText(String.format(Locale.US,
                    "RPM %s   %s km/h\nECT %s C   TFT %s C\nLoad %s %%   Batt %s V\nSTFT %s / %s   LTFT %s / %s",
                    num(m, "Rpm", "%.0f"), num(m, "Speed_kmh", "%.0f"), num(m, "Coolant_C", "%.0f"),
                    num(m, "TransFluidTemp_C", "%.0f"), num(m, "Load_pct", "%.0f"), volts,
                    num(m, "STFT_B1_pct", "%+.1f"), num(m, "STFT_B2_pct", "%+.1f"),
                    num(m, "LTFT_B1_pct", "%+.1f"), num(m, "LTFT_B2_pct", "%+.1f")));
            StringBuilder sb = new StringBuilder();
            int raw = 0;
            for (Map.Entry<String, String> e : m.entrySet()) {
                if (e.getKey().contains("raw_")) {
                    raw++;
                    continue;
                }
                sb.append(String.format(Locale.US, "%-22s %s%n", e.getKey(), e.getValue()));
            }
            sb.append("(+ ").append(raw).append(" raw-hex columns in the CSV)\n");
            valuesView.setText(sb.toString());
        } else {
            gaugesView.setText("");
            valuesView.setText(AppState.snapshotText != null ? AppState.snapshotText : "");
        }
        List<String> msgs = AppState.messages();
        StringBuilder sb = new StringBuilder();
        for (int i = msgs.size() - 1; i >= 0 && i >= msgs.size() - 15; i--) sb.append(msgs.get(i)).append('\n');
        messagesView.setText(sb.toString());
    }

    // ------------------------------------------------------------------ permissions

    private boolean granted(String p) {
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    void ensure(String[] perms, Runnable then) {
        List<String> missing = new ArrayList<String>();
        for (String p : perms) if (!granted(p)) missing.add(p);
        if (missing.isEmpty()) {
            then.run();
            return;
        }
        pending = then;
        requestPermissions(missing.toArray(new String[0]), REQ_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (results.length == 0) {          // dialog dismissed / interrupted: treat as cancelled
            pending = null;
            return;
        }
        boolean ok = true;
        for (int i = 0; i < perms.length && i < results.length; i++)
            if (results[i] != PackageManager.PERMISSION_GRANTED && !P_NOTIFY.equals(perms[i])) ok = false;
        Runnable p = pending;
        pending = null;
        if (ok && p != null) {
            p.run();
        } else if (!ok) {
            new AlertDialog.Builder(this)
                    .setTitle("Permission needed")
                    .setMessage(AppState.car().appName + " needs the Bluetooth / Nearby devices permission to talk to the "
                            + "OBD adapter (and Location on Android 11 and older, only for finding BLE "
                            + "adapters). Nothing else is accessed.")
                    .setPositiveButton("App settings", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:" + getPackageName())));
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        }
    }

    // ------------------------------------------------------------------ adapter choice

    void saveAdapter(String kind, String addr, String label) {
        prefs.edit().putString("kind", kind).putString("addr", addr).putString("label", label).apply();
        AppState.status = "Adapter set: " + label;
        refresh();
    }

    void chooseAdapter() {
        if (Build.VERSION.SDK_INT >= 31) ensure(new String[]{P_BT_CONNECT}, new Runnable() {
            @Override
            public void run() {
                showAdapterDialog();
            }
        });
        else showAdapterDialog();
    }

    private static boolean looksObd(String n) {
        String u = n.toUpperCase(Locale.US);
        return u.contains("OBD") || u.contains("ELM") || u.contains("LINK") || u.contains("VEEPEAK")
                || u.contains("VGATE") || u.contains("KONNWEI") || u.contains("CAR");
    }

    void showAdapterDialog() {
        final List<String> labels = new ArrayList<String>();
        final List<String[]> choices = new ArrayList<String[]>();
        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        if (ad == null) {
            labels.add("(this phone has no Bluetooth)");
            choices.add(new String[]{"none"});
        } else if (!ad.isEnabled()) {
            labels.add("Turn Bluetooth on...");
            choices.add(new String[]{"enable"});
        } else {
            try {
                Set<BluetoothDevice> bonded = ad.getBondedDevices();
                if (bonded == null) bonded = new java.util.HashSet<BluetoothDevice>();
                List<String> obdFirst = new ArrayList<String>(), rest = new ArrayList<String>();
                Map<String, String[]> byLabel = new LinkedHashMap<String, String[]>();
                for (BluetoothDevice d : bonded) {
                    String n = d.getName() == null ? "(no name)" : d.getName();
                    boolean le = d.getType() == BluetoothDevice.DEVICE_TYPE_LE;
                    String label = n + (le ? "  [BLE]" : "") + "\n" + d.getAddress();
                    byLabel.put(label, new String[]{le ? "ble" : "bt", d.getAddress(), n});
                    (looksObd(n) ? obdFirst : rest).add(label);
                }
                obdFirst.addAll(rest);
                for (String l : obdFirst) {
                    labels.add(l);
                    choices.add(byLabel.get(l));
                }
                if (bonded.isEmpty()) {
                    labels.add("No paired devices - open Bluetooth settings to pair the adapter (PIN 1234 or 0000)");
                    choices.add(new String[]{"settings"});
                }
            } catch (SecurityException e) {
                labels.add("(Bluetooth permission missing)");
                choices.add(new String[]{"none"});
            }
            labels.add("Scan for a BLE adapter...");
            choices.add(new String[]{"scan"});
        }
        labels.add("WiFi adapter...");
        choices.add(new String[]{"wifi"});
        labels.add("Demo - simulated " + AppState.car().description + " (no car needed)");
        choices.add(new String[]{"demo"});
        new AlertDialog.Builder(this)
                .setTitle("OBD adapter")
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String[] c = choices.get(which);
                        if (c[0].equals("enable")) {
                            try {
                                startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
                            } catch (RuntimeException e) {
                                toast("Turn Bluetooth on in the phone settings");
                            }
                        } else if (c[0].equals("settings")) {
                            startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
                        } else if (c[0].equals("scan")) {
                            startBleScan();
                        } else if (c[0].equals("wifi")) {
                            askWifi();
                        } else if (c[0].equals("demo")) {
                            saveAdapter("demo", "", "Demo (simulated car)");
                        } else if (c[0].equals("bt") || c[0].equals("ble")) {
                            saveAdapter(c[0], c[1], c[2] + (c[0].equals("ble") ? " (BLE)" : "") + " " + c[1]);
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    void askWifi() {
        final EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        e.setText("wifi".equals(prefs.getString("kind", "")) ? prefs.getString("addr", "192.168.0.10:35000")
                : "192.168.0.10:35000");
        new AlertDialog.Builder(this)
                .setTitle("WiFi adapter address")
                .setMessage("Connect the phone to the adapter's WiFi first.")
                .setView(e)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String a = e.getText().toString().trim();
                        if (!a.isEmpty()) saveAdapter("wifi", a, "WiFi " + a);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    void startBleScan() {
        String[] perms = Build.VERSION.SDK_INT >= 31 ? new String[]{P_BT_SCAN, P_BT_CONNECT}
                : new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
        ensure(perms, new Runnable() {
            @Override
            public void run() {
                doBleScan();
            }
        });
    }

    void doBleScan() {
        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        final BluetoothLeScanner sc = ad == null ? null : ad.getBluetoothLeScanner();
        if (sc == null) {
            toast("Bluetooth is off");
            return;
        }
        final Map<String, String> found = new LinkedHashMap<String, String>();
        final ScanCallback cb = new ScanCallback() {
            @Override
            public void onScanResult(int type, ScanResult r) {
                String n = null;
                try {
                    if (r.getScanRecord() != null) n = r.getScanRecord().getDeviceName();
                    if (n == null) n = r.getDevice().getName();
                } catch (SecurityException ignored) {
                }
                if (n != null && !n.trim().isEmpty())
                    synchronized (found) {
                        found.put(r.getDevice().getAddress(), n.trim());
                    }
            }

            @Override
            public void onScanFailed(int code) {
                AppState.message("BLE scan failed (code " + code + ")");
            }
        };
        try {
            sc.startScan(cb);
        } catch (SecurityException e) {
            toast("Bluetooth scan permission missing");
            return;
        }
        final AlertDialog wait = new AlertDialog.Builder(this).setTitle("Scanning for BLE adapters")
                .setMessage("Plug the adapter in (ignition ON) and keep the phone close... 8 s").setCancelable(false)
                .show();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    sc.stopScan(cb);
                } catch (RuntimeException ignored) {
                }
                wait.dismiss();
                showBleResults(found);
            }
        }, 8000);
    }

    void showBleResults(Map<String, String> found) {
        if (found.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("No BLE devices found")
                    .setMessage("Is the adapter plugged in and powered? On Android 11 and older, Location must "
                            + "be switched on for BLE scanning. Classic Bluetooth adapters are not found by this "
                            + "scan - pair them in the phone's Bluetooth settings instead.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        final List<String> addrs = new ArrayList<String>();
        final List<String> names = new ArrayList<String>();
        List<String> labels = new ArrayList<String>();
        for (Map.Entry<String, String> e : found.entrySet()) {
            boolean obd = looksObd(e.getValue());
            addrs.add(obd ? 0 : addrs.size(), e.getKey());
            names.add(obd ? 0 : names.size(), e.getValue());
            labels.add(obd ? 0 : labels.size(), e.getValue() + "\n" + e.getKey());
        }
        new AlertDialog.Builder(this).setTitle("Choose the BLE OBD adapter")
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        saveAdapter("ble", addrs.get(which), names.get(which) + " (BLE) " + addrs.get(which));
                    }
                })
                .setNegativeButton("Cancel", null).show();
    }

    // ------------------------------------------------------------------ jobs

    void startJob(final String job) {
        final String kind = prefs.getString("kind", null);
        if (kind == null) {
            toast("Choose the OBD adapter first");
            chooseAdapter();
            return;
        }
        if (AppState.running()) return;
        List<String> perms = new ArrayList<String>();
        if (Build.VERSION.SDK_INT >= 31 && (kind.equals("bt") || kind.equals("ble"))) perms.add(P_BT_CONNECT);
        if (Build.VERSION.SDK_INT >= 33) perms.add(P_NOTIFY);
        ensure(perms.toArray(new String[0]), new Runnable() {
            @Override
            public void run() {
                AppState.values = new LinkedHashMap<String, String>();
                AppState.snapshotText = null;
                Intent i = new Intent(MainActivity.this, LoggerService.class)
                        .setAction(LoggerService.ACTION_START)
                        .putExtra(LoggerService.EXTRA_JOB, job)
                        .putExtra(LoggerService.EXTRA_KIND, kind)
                        .putExtra(LoggerService.EXTRA_ADDR, prefs.getString("addr", ""))
                        .putExtra(LoggerService.EXTRA_NAME, prefs.getString("label", kind))
                        .putExtra(LoggerService.EXTRA_TRACE, traceBox.isChecked());
                try {
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
                    else startService(i);
                } catch (RuntimeException e) {
                    AppState.message("could not start: " + e);
                    toast("Could not start: " + e.getMessage());
                }
                refresh();
            }
        });
    }

    void share() {
        List<Uri> files = AppState.lastFiles();
        if (files.isEmpty()) {
            toast("Nothing recorded yet. Files are in Download/" + DownloadsSink.folder());
            return;
        }
        Intent send;
        if (files.size() == 1) {
            send = new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, files.get(0));
        } else {
            send = new Intent(Intent.ACTION_SEND_MULTIPLE)
                    .putParcelableArrayListExtra(Intent.EXTRA_STREAM, new ArrayList<Uri>(files));
        }
        ClipData clip = ClipData.newRawUri("OBD files", files.get(0));
        for (int i = 1; i < files.size(); i++) clip.addItem(new ClipData.Item(files.get(i)));
        send.setClipData(clip);
        send.setType("*/*");
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(send, "Share OBD files"));
        } catch (ActivityNotFoundException e) {
            toast("No app to share with");
        }
    }

    void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
