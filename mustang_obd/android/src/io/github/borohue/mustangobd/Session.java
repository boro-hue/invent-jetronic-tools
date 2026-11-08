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
                if (++tries >= 5) throw e;
                ui.message("connecting... (" + tries + "/5: " + e.getMessage() + ")");
                sleep(2000);
            }
        }
    }

    public Elm reconnect() {
        close();
        long delay = reconnectDelayMs;
        while (true) {
            ui.status("Link lost - reconnecting...");
            sleep(delay);
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
        ui.message("car not answering (ignition off?) - waiting");
        while (true) {
            checkStop();
            ui.status("Waiting for the car (ignition off?)");
            try {
                elm.setHeader(null);
                if (!elm.obd("0100", 6000, 0).positive(0x01).isEmpty()) {
                    ui.message("car is back");
                    return;
                }
            } catch (IOException e) {
                reconnect();
                return;
            }
            sleep(2000);
        }
    }
}
