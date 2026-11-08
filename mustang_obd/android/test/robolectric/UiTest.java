package io.github.borohue.mustangobd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlertDialog;
import android.app.Application;
import android.app.Notification;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.SQLiteMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowBluetoothDevice;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * Drives the real MainActivity / LoggerService on Robolectric (Android framework on the JVM),
 * with the built-in Demo adapter. Run via run_ui_tests.sh; the SDK level comes from -Drobolectric.sdk.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
public class UiTest {
    Application app;

    @Before
    public void reset() {
        app = RuntimeEnvironment.getApplication();
        AppState.job = null;
        AppState.session = null;
        AppState.status = "Idle";
        AppState.values = new LinkedHashMap<String, String>();
        AppState.snapshotText = null;
        AppState.setLastFiles(new ArrayList<android.net.Uri>());
        app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit();
        shadowOf(app).grantPermissions("android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_SCAN",
                "android.permission.POST_NOTIFICATIONS", "android.permission.ACCESS_FINE_LOCATION");
    }

    static List<View> all(View v, List<View> out) {
        out.add(v);
        if (v instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) all(((ViewGroup) v).getChildAt(i), out);
        return out;
    }

    static Button button(MainActivity a, String label) {
        for (View v : all(a.getWindow().getDecorView(), new ArrayList<View>()))
            if (v instanceof Button && ((Button) v).getText().toString().equals(label)) return (Button) v;
        throw new AssertionError("no button " + label);
    }

    static String screenText(MainActivity a) {
        StringBuilder sb = new StringBuilder();
        for (View v : all(a.getWindow().getDecorView(), new ArrayList<View>()))
            if (v instanceof TextView) sb.append(((TextView) v).getText()).append('\n');
        return sb.toString();
    }

    static void pickDialogItem(String startsWith) {
        AlertDialog d = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("dialog shown", d);
        ListView lv = d.getListView();
        for (int i = 0; i < lv.getAdapter().getCount(); i++) {
            if (String.valueOf(lv.getAdapter().getItem(i)).startsWith(startsWith)) {
                shadowOf(d).clickOnItem(i);
                shadowOf(Looper.getMainLooper()).idle();
                return;
            }
        }
        throw new AssertionError("no dialog item " + startsWith + " in " + shadowOf(d).getItems().length + " items");
    }

    MainActivity launchWithDemo() {
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a = c.get();
        assertTrue(screenText(a).contains("Choose your OBD adapter"));
        button(a, "Adapter...").performClick();
        shadowOf(Looper.getMainLooper()).idle();
        pickDialogItem("Demo");
        assertEquals("demo", app.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("kind", null));
        return a;
    }

    /** start the job the activity asked for, the way Android would */
    ServiceController<LoggerService> runService() {
        Intent i = shadowOf(app).getNextStartedService();
        assertNotNull("service started", i);
        ServiceController<LoggerService> sc = Robolectric.buildService(LoggerService.class, i).create();
        sc.startCommand(0, 1);
        return sc;
    }

    static void waitFor(String what, java.util.concurrent.Callable<Boolean> cond) throws Exception {
        long end = System.currentTimeMillis() + 20000;
        while (!cond.call()) {
            if (System.currentTimeMillis() > end) throw new AssertionError("timeout waiting for " + what
                    + "; status=" + AppState.status + " msgs=" + AppState.messages());
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100));
            Thread.sleep(50);
        }
    }

    @Test
    public void launchesAndShowsHint() {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        String t = screenText(a);
        assertTrue(t, t.contains("Mustang OBD"));
        assertTrue(t, t.contains("(none chosen)"));
    }

    @Test
    public void demoLogEndToEnd() throws Exception {
        final MainActivity a = launchWithDemo();
        button(a, "Start log").performClick();
        ServiceController<LoggerService> sc = runService();
        waitFor("rows", new java.util.concurrent.Callable<Boolean>() {
            public Boolean call() {
                return AppState.rows > 15;
            }
        });
        Notification n = shadowOf(sc.get()).getLastForegroundNotification();
        assertNotNull("foreground notification", n);
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600));
        String t = screenText(a);
        assertTrue(t, t.contains("RPM ") && t.contains("Coolant_C") && t.contains("TransFluidTemp_C"));
        assertTrue(t, t.contains("Stop"));                       // log button became Stop
        button(a, "Stop").performClick();
        waitFor("job end", new java.util.concurrent.Callable<Boolean>() {
            public Boolean call() {
                return !AppState.running();
            }
        });
        assertTrue(AppState.status, AppState.status.startsWith("Stopped."));
        List<android.net.Uri> files = AppState.lastFiles();
        assertEquals("csv + meta.json", 2, files.size());
        if (Build.VERSION.SDK_INT < 29) {
            File dir = DownloadsSink.privateDir(app);
            String[] names = dir.list();
            assertTrue(java.util.Arrays.toString(names), names != null && names.length >= 2);
        }
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600));
        button(a, "Share files").performClick();
        Intent chooser = shadowOf(a).getNextStartedActivity();
        assertEquals(Intent.ACTION_CHOOSER, chooser.getAction());
        Intent send = chooser.getParcelableExtra(Intent.EXTRA_INTENT);
        assertEquals(Intent.ACTION_SEND_MULTIPLE, send.getAction());
        assertTrue((send.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
    }

    @Test
    public void demoSnapshot() throws Exception {
        MainActivity a = launchWithDemo();
        button(a, "Snapshot").performClick();
        runService();
        waitFor("snapshot", new java.util.concurrent.Callable<Boolean>() {
            public Boolean call() {
                return !AppState.running() && AppState.snapshotText != null;
            }
        });
        assertTrue(AppState.snapshotText, AppState.snapshotText.contains("P0303")
                && AppState.snapshotText.contains("Misfire cylinder 3"));
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600));
        assertTrue(screenText(a).contains("TROUBLE CODES"));
        assertEquals("snapshot json + txt", 2, AppState.lastFiles().size());
    }

    @Test
    public void pairedAdapterAppearsFirst() {
        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        shadowOf(ad).setEnabled(true);
        BluetoothDevice phone = ShadowBluetoothDevice.newInstance("AA:AA:AA:AA:AA:AA");
        shadowOf(phone).setName("Galaxy Buds");
        BluetoothDevice obd = ShadowBluetoothDevice.newInstance("00:1D:A5:68:9A:0B");
        shadowOf(obd).setName("OBDII");
        Set<BluetoothDevice> bonded = new HashSet<BluetoothDevice>();
        bonded.add(phone);
        bonded.add(obd);
        shadowOf(ad).setBondedDevices(bonded);
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        button(a, "Adapter...").performClick();
        shadowOf(Looper.getMainLooper()).idle();
        AlertDialog d = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue(String.valueOf(d.getListView().getAdapter().getItem(0)).startsWith("OBDII"));
        pickDialogItem("OBDII");
        assertEquals("bt", app.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("kind", null));
        assertEquals("00:1D:A5:68:9A:0B",
                app.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("addr", null));
    }

    /** Minimal stand-in for Android's MediaStore (Robolectric has none): insert + open for writing. */
    public static class FakeMediaStore extends android.content.ContentProvider {
        static final java.util.Map<String, File> files = new java.util.LinkedHashMap<String, File>();

        @Override
        public boolean onCreate() {
            return true;
        }

        @Override
        public android.net.Uri insert(android.net.Uri uri, android.content.ContentValues v) {
            try {
                File f = File.createTempFile("mediastore", ".bin");
                String path = v.getAsString(android.provider.MediaStore.MediaColumns.RELATIVE_PATH) + "/"
                        + v.getAsString(android.provider.MediaStore.MediaColumns.DISPLAY_NAME);
                android.net.Uri u = android.net.Uri.withAppendedPath(uri, String.valueOf(files.size() + 1));
                files.put(u.toString() + "|" + path, f);
                return u;
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public android.os.ParcelFileDescriptor openFile(android.net.Uri uri, String mode)
                throws java.io.FileNotFoundException {
            for (java.util.Map.Entry<String, File> e : files.entrySet())
                if (e.getKey().startsWith(uri.toString() + "|"))
                    return android.os.ParcelFileDescriptor.open(e.getValue(),
                            android.os.ParcelFileDescriptor.parseMode(mode));
            throw new java.io.FileNotFoundException(uri.toString());
        }

        @Override
        public android.database.Cursor query(android.net.Uri u, String[] p, String s, String[] a, String o) {
            return null;
        }

        @Override
        public String getType(android.net.Uri u) {
            return null;
        }

        @Override
        public int delete(android.net.Uri u, String s, String[] a) {
            return 0;
        }

        @Override
        public int update(android.net.Uri u, android.content.ContentValues v, String s, String[] a) {
            return 0;
        }
    }

    @Test
    public void logsGoToDownloadsFolderOnAndroid10Plus() throws Exception {
        if (Build.VERSION.SDK_INT < 29) return;                  // MediaStore Downloads is Android 10+
        FakeMediaStore.files.clear();
        Robolectric.buildContentProvider(FakeMediaStore.class).create("media");
        MainActivity a = launchWithDemo();
        button(a, "Start log").performClick();
        runService();
        waitFor("rows", new java.util.concurrent.Callable<Boolean>() {
            public Boolean call() {
                return AppState.rows > 5;
            }
        });
        AppState.stop();
        waitFor("job end", new java.util.concurrent.Callable<Boolean>() {
            public Boolean call() {
                return !AppState.running();
            }
        });
        String csvKey = null;
        for (String k : FakeMediaStore.files.keySet())
            if (k.endsWith(".csv")) csvKey = k;
        assertNotNull("CSV created through MediaStore: " + FakeMediaStore.files.keySet() + " msgs " + AppState.messages(), csvKey);
        assertTrue(csvKey, csvKey.contains("|Download/MustangOBD/mustang_"));
        String csv = new String(java.nio.file.Files.readAllBytes(FakeMediaStore.files.get(csvKey).toPath()), "UTF-8");
        assertTrue(csv.substring(0, Math.min(80, csv.length())), csv.startsWith("t_s,clock,"));
        assertTrue("rows written", csv.split("\r\n").length > 5);
        for (android.net.Uri u : AppState.lastFiles())
            assertTrue(u.toString(), u.toString().startsWith("content://media/external/downloads/"));
        assertTrue(AppState.status, AppState.status.contains("Download/MustangOBD/"));
    }

    @Test
    public void deniedPermissionExplains() {
        if (Build.VERSION.SDK_INT < 31) return;                  // runtime BT permission is Android 12+
        shadowOf(app).denyPermissions("android.permission.BLUETOOTH_CONNECT");
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        button(a, "Adapter...").performClick();
        a.onRequestPermissionsResult(1, new String[]{"android.permission.BLUETOOTH_CONNECT"},
                new int[]{android.content.pm.PackageManager.PERMISSION_DENIED});
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(shadowOf(ShadowAlertDialog.getLatestAlertDialog()).getTitle().toString()
                .contains("Permission needed"));
    }
}
