package io.github.borohue.mustangobd;

import android.net.Uri;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** State shared between the logging service (writer) and the screen (reader). */
public final class AppState {
    private AppState() {
    }

    public static volatile String status = "Idle";
    public static volatile String job;                 // "log" / "snapshot" while running, else null
    public static volatile Session session;            // running session (to stop it)
    public static volatile Map<String, String> values = Collections.emptyMap();
    public static volatile long rows;
    public static volatile double rate;
    public static volatile String snapshotText;
    private static final LinkedList<String> messages = new LinkedList<String>();
    private static final List<Uri> lastFiles = new ArrayList<Uri>();

    public static synchronized void message(String m) {
        messages.add(new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + "  " + m);
        while (messages.size() > 60) messages.removeFirst();
    }

    public static synchronized List<String> messages() {
        return new ArrayList<String>(messages);
    }

    public static synchronized void setLastFiles(List<Uri> files) {
        lastFiles.clear();
        lastFiles.addAll(files);
    }

    public static synchronized List<Uri> lastFiles() {
        return new ArrayList<Uri>(lastFiles);
    }

    public static boolean running() {
        return job != null;
    }

    public static void stop() {
        Session s = session;
        if (s != null) s.stop = true;
    }

    /** Listener that feeds this state. */
    public static final Session.Listener LISTENER = new Session.Listener() {
        @Override
        public void status(String text) {
            status = text;
        }

        @Override
        public void message(String text) {
            AppState.message(text);
        }

        @Override
        public void values(Map<String, String> latest, long r, double rt) {
            values = new LinkedHashMap<String, String>(latest);
            rows = r;
            rate = rt;
        }
    };
}
