package io.github.borohue.mustangobd;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Map;

/**
 * Foreground service that owns the adapter connection while logging or taking a snapshot,
 * so the log keeps running with the screen off or the app in the background.
 */
public class LoggerService extends Service {
    public static final String ACTION_START = "io.github.borohue.mustangobd.START";
    public static final String ACTION_STOP = "io.github.borohue.mustangobd.STOP";
    public static final String EXTRA_JOB = "job", EXTRA_KIND = "kind", EXTRA_ADDR = "addr", EXTRA_NAME = "name",
            EXTRA_TRACE = "trace";
    static final String CHANNEL = "logging";
    static final String DONE_CHANNEL = "drives";
    static final int NOTIF_ID = 1, DONE_NOTIF_ID = 2;
    /** hands-free: the log ends once the car (or adapter) has been off this long */
    public static int handsFreeOffSec = 120;

    private PowerManager.WakeLock wake;
    private long lastNotif;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {                           // restarted by the system: nothing to resume
            if (!AppState.running()) stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(intent.getAction())) {
            AppState.stop();
            return START_NOT_STICKY;
        }
        // Every startForegroundService() must be answered with startForeground(), even a
        // duplicate request that arrives while a job is already running (double tap).
        goForeground(AppState.running() ? AppState.status : "Starting...");
        if (AppState.running()) return START_NOT_STICKY;
        final String job = intent.getStringExtra(EXTRA_JOB);
        final String kind = intent.getStringExtra(EXTRA_KIND);
        final String addr = intent.getStringExtra(EXTRA_ADDR);
        final String name = intent.getStringExtra(EXTRA_NAME);
        final boolean trace = intent.getBooleanExtra(EXTRA_TRACE, false);
        AppState.job = job;
        AppState.status = "Starting...";
        AppState.snapshotText = null;
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, AppState.car().folder + ":logging");
            wake.acquire();
        } catch (RuntimeException e) {
            wake = null;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                runJob(job, kind, addr, name, trace);
            }
        }, "obd-job").start();
        return START_NOT_STICKY;
    }

    Session.TransportFactory factory(final String kind, final String addr, final String name) {
        final Context ctx = getApplicationContext();
        return new Session.TransportFactory() {
            @Override
            public Transport create() throws IOException {
                if ("demo".equals(kind)) return new SimTransport(AppState.car().id);
                if ("wifi".equals(kind)) {
                    String host = addr;
                    int port = 35000;
                    int c = addr.lastIndexOf(':');
                    if (c > 0) {
                        host = addr.substring(0, c);
                        port = Integer.parseInt(addr.substring(c + 1));
                    }
                    return new Transport.Tcp(host, port, wifiSocketFactory());
                }
                BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
                if (ad == null) throw new IOException("this phone has no Bluetooth");
                if (!ad.isEnabled()) throw new IOException("Bluetooth is switched off");
                BluetoothDevice dev = ad.getRemoteDevice(addr);
                return "ble".equals(kind) ? new BleTransport(ctx, dev, name) : new BtTransport(dev, name);
            }
        };
    }

    /** The adapter's WiFi has no internet, so Android would route via mobile data - bind to WiFi. */
    javax.net.SocketFactory wifiSocketFactory() {
        try {
            android.net.ConnectivityManager cm =
                    (android.net.ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            for (android.net.Network n : cm.getAllNetworks()) {
                android.net.NetworkCapabilities c = cm.getNetworkCapabilities(n);
                if (c != null && c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI))
                    return n.getSocketFactory();
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    void runJob(String job, String kind, String addr, String name, boolean trace) {
        final DownloadsSink sink = new DownloadsSink(this);
        final Session.Listener ui = new Session.Listener() {
            @Override
            public void status(String text) {
                AppState.LISTENER.status(text);
                updateNotification(text);
            }

            @Override
            public void message(String text) {
                AppState.LISTENER.message(text);
            }

            @Override
            public void values(Map<String, String> latest, long rows, double rate) {
                AppState.LISTENER.values(latest, rows, rate);
            }
        };
        Car car = AppState.car();
        Session s = new Session(factory(kind, addr, name), car.protocol, ui);
        AppState.session = s;
        OutputStream traceOut = null;
        String doneText = null;
        try {
            if (trace) {
                traceOut = sink.create("elm_trace_" + Session.stamp() + ".txt", "text/plain");
                final OutputStream to = traceOut;
                s.trace = new Elm.Trace() {
                    @Override
                    public void trace(String dir, String text) {
                        try {
                            to.write((System.currentTimeMillis() / 1000.0 + " " + dir + " "
                                    + text.replace("\r", "\\r") + "\n").getBytes("UTF-8"));
                        } catch (IOException ignored) {
                        }
                    }
                };
            }
            if ("snapshot".equals(job)) {
                Snapshot snap = new Snapshot(s, sink);
                snap.profile = car.profile;
                snap.toolName = car.appName + " (Android)";
                snap.run();
                AppState.snapshotText = snap.text;
                AppState.status = "Snapshot saved: " + sink.where(snap.txtName);
            } else {
                boolean handsFree = "auto".equals(job);
                if (handsFree) {
                    // 1) wait for the ignition, 2) snapshot, 3) log until the car stays off
                    s.connectAttempts = Integer.MAX_VALUE;
                    ui.status("Hands-free: waiting for the car (ignition ON)...");
                    Snapshot snap = new Snapshot(s, sink);
                    snap.profile = car.profile;
                    snap.toolName = car.appName + " (Android)";
                    snap.waitForCar = true;
                    try {
                        snap.run();
                        AppState.snapshotText = snap.text;
                    } catch (Session.Stopped e) {
                        throw e;
                    } catch (Exception e) {
                        AppState.message("snapshot skipped: " + e.getMessage());
                    }
                }
                LogSession log = new LogSession(s, sink);
                log.profile = car.profile;
                log.toolName = car.appName + " (Android)";
                if (handsFree) log.stopWhenOffSec = handsFreeOffSec;
                log.run();
                String what = log.rows + " rows, " + Math.round(log.durationSec / 60) + " min";
                if (handsFree && "car off".equals(log.endReason)) {
                    AppState.status = "Drive saved (car switched off): " + what + " - " + sink.where(log.csvName);
                } else {
                    AppState.status = "Stopped. " + what + " saved: " + sink.where(log.csvName);
                }
                if (log.rows > 0) doneText = what + ". Tap to share the files.";
            }
        } catch (Session.Stopped e) {
            AppState.status = "Stopped.";
        } catch (Elm.CarSilentException e) {
            AppState.status = "Adapter OK, but the car did not answer (" + e.getMessage()
                    + "). Ignition ON? Engine running is best.";
            AppState.message(AppState.status);
        } catch (Elm.UnsupportedAdapterException e) {
            AppState.status = e.getMessage();
            AppState.message(e.getMessage());
        } catch (Exception e) {
            AppState.status = "Error: " + e.getMessage();
            AppState.message("Error: " + e);
        } finally {
            s.close();
            if (traceOut != null) {
                try {
                    traceOut.close();
                } catch (IOException ignored) {
                }
            }
            synchronized (sink.created) {
                AppState.setLastFiles(new ArrayList<android.net.Uri>(sink.created));
            }
            if (doneText != null) notifyDone(doneText);
            AppState.session = null;
            AppState.job = null;
            try {
                if (wake != null && wake.isHeld()) wake.release();
            } catch (RuntimeException ignored) {
            }
            stopForeground(true);
            stopSelf();
        }
    }

    // ------------------------------------------------------------------ notification

    private Notification build(String text) {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "OBD logging",
                    NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, LoggerService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        b.setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(AppState.car().appName)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi)
                .addAction(android.R.drawable.ic_media_pause, "Stop", stop);
        return b.build();
    }

    /** "Drive saved" notification; tapping it opens the share sheet with the session's files. */
    private void notifyDone(String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel(DONE_CHANNEL, "Finished drives",
                        NotificationManager.IMPORTANCE_DEFAULT));
                b = new Notification.Builder(this, DONE_CHANNEL);
            } else {
                b = new Notification.Builder(this);
            }
            Intent share = new Intent(this, MainActivity.class).setAction(MainActivity.ACTION_SHARE_LAST)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pi = PendingIntent.getActivity(this, 2, share,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            b.setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(AppState.car().appName + ": drive saved")
                    .setContentText(text)
                    .setAutoCancel(true)
                    .setContentIntent(pi);
            nm.notify(DONE_NOTIF_ID, b.build());
        } catch (RuntimeException e) {
            AppState.message("could not show the 'drive saved' notification: " + e.getMessage());
        }
    }

    private void goForeground(String text) {
        try {
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(NOTIF_ID, build(text), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            else startForeground(NOTIF_ID, build(text));
        } catch (RuntimeException e) {
            // keep working without the notification; Android may stop us in the background
            AppState.message("could not start the background notification (" + e.getMessage() + ")");
        }
    }

    private void updateNotification(String text) {
        long now = System.currentTimeMillis();
        if (now - lastNotif < 2000) return;
        lastNotif = now;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(NOTIF_ID, build(text));
        } catch (RuntimeException ignored) {
        }
    }
}
