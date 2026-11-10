package io.github.borohue.mustangobd;

import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

/** One adapter connection with connect / reconnect / wait-for-car handling. */
public class Session {
    public interface TransportFactory {
        Transport create() throws IOException;
    }

    /** Where output files go (app: Download/MustangOBD; tests: a folder). */
    public interface Sink {
        OutputStream create(String name, String mime) throws IOException;

        String where(String name);
    }

    /** Progress reporting to the UI. Called from the worker thread. */
    public interface Listener {
        void status(String text);

        void message(String text);

        void values(Map<String, String> latest, long rows, double rowsPerSec);
    }

    /** Thrown inside waits when the user pressed Stop. */
    public static class Stopped extends RuntimeException {
        public Stopped() {
            super("stopped");
        }
    }

    public static int reconnectDelayMs = 2000;

    final TransportFactory factory;
    final String protocol;
    final Listener ui;
    public volatile boolean stop;
    /** link failures tolerated while connecting (hands-free: practically unlimited) */
    public int connectAttempts = 5;
    public Elm elm;
    String countMode;
    Elm.Trace trace;

    public Session(TransportFactory factory, String protocol, Listener ui) {
        this.factory = factory;
        this.protocol = protocol;
        this.ui = ui;
    }

    public static String stamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    }

    void checkStop() {
        if (stop) throw new Stopped();
    }

    void sleep(long ms) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            checkStop();
            try {
                Thread.sleep(Math.min(100, Math.max(1, end - System.currentTimeMillis())));
            } catch (InterruptedException e) {
                throw new Stopped();
            }
        }
    }

    void open() throws IOException, Elm.CarSilentException, Elm.UnsupportedAdapterException {
        Transport t = factory.create();
        t.open();
        Elm e = new Elm(t);
        e.trace = trace;
        try {
            e.init(protocol);
            e.connectCar(protocol);
        } catch (IOException | Elm.CarSilentException | Elm.UnsupportedAdapterException | RuntimeException x) {
            t.close();
            throw x;
        }
        e.countMode = countMode;
        elm = e;
    }

    public void close() {
        if (elm != null) {
            elm.t.close();
            elm = null;
        }
    }

    /** Connect; with waitForCar the car may still be off (keeps trying until Stop). */
    public Elm connect(boolean waitForCar) throws IOException, Elm.CarSilentException,
            Elm.UnsupportedAdapterException {
        int tries = 0;
        while (true) {
            checkStop();
            try {
                ui.status("Connecting...");
                open();
                return elm;
            } catch (Elm.CarSilentException e) {
                close();
                if (!waitForCar) throw e;
                ui.status("Adapter OK - waiting for ignition ON (" + e.getMessage() + ")");
                sleep(3000);
            } catch (IOException e) {
                close();
                if (++tries >= connectAttempts) throw e;
                ui.message("connecting... (" + tries + (connectAttempts < 1000 ? "/" + connectAttempts : "")
                        + ": " + e.getMessage() + ")");
                sleep(2000);
            }
        }
    }

    public Elm reconnect() {
        return reconnect(-1);
    }

    /** Reopen the link; giveUpMs < 0 = keep trying. Returns null when it gave up. */
    public Elm reconnect(long giveUpMs) {
        close();
        long delay = reconnectDelayMs;
        long t0 = System.currentTimeMillis();
        while (true) {
            long left = giveUpMs < 0 ? -1 : giveUpMs - (System.currentTimeMillis() - t0);
            if (giveUpMs >= 0 && left <= 0) {
                ui.message("adapter did not come back - ending the log");
                return null;
            }
            ui.status("Link lost - reconnecting...");
            sleep(left < 0 ? delay : Math.min(delay, left));
            try {
                open();
                ui.message("reconnected");
                return elm;
            } catch (Exception e) {
                if (e instanceof Stopped) throw (Stopped) e;
                close();
                ui.message("not yet: " + e.getMessage());
                delay = Math.min(delay * 2, 15000);
            }
        }
    }

    /** Ignition off mid-log: poll slowly until the car answers again. */
    public void waitForCar() {
        waitForCar(-1);
    }

    /** Returns true when the car answers again, false after giveUpMs of silence (< 0 = never). */
    public boolean waitForCar(long giveUpMs) {
        ui.message("car not answering (ignition off?) - waiting");
        long t0 = System.currentTimeMillis();
        while (true) {
            checkStop();
            long left = giveUpMs < 0 ? -1 : giveUpMs - (System.currentTimeMillis() - t0);
            if (giveUpMs >= 0 && left <= 0) {
                ui.message("car stayed off - ending the log");
                return false;
            }
            ui.status("Waiting for the car (ignition off?)");
            try {
                elm.setHeader(null);
                if (!elm.obd("0100", 6000, 0).positive(0x01).isEmpty()) {
                    ui.message("car is back");
                    return true;
                }
            } catch (IOException e) {
                return reconnect(left) != null;
            }
            sleep(left < 0 ? 2000 : Math.max(1, Math.min(2000, left)));
        }
    }
}
