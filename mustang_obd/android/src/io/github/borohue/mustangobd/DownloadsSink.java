package io.github.borohue.mustangobd;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Output files. Android 10+: Download/<app folder> via MediaStore (visible in any file manager,
 * kept if the app is uninstalled). Older: the app's own folder, shared via LogProvider.
 */
public class DownloadsSink implements Session.Sink {
    private final Context ctx;
    public final List<Uri> created = new ArrayList<Uri>();

    public DownloadsSink(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    /** Download sub-folder of this app variant, e.g. "MustangOBD" or "W211OBD". */
    public static String folder() {
        return AppState.car().folder;
    }

    public static File privateDir(Context ctx) {
        File d = ctx.getExternalFilesDir("logs");
        if (d == null) d = new File(ctx.getFilesDir(), "logs");
        d.mkdirs();
        return d;
    }

    @Override
    public OutputStream create(String name, String mime) throws IOException {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + folder());
                ContentResolver r = ctx.getContentResolver();
                Uri uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri != null) {
                    OutputStream o = null;
                    try {
                        o = r.openOutputStream(uri, "w");
                    } finally {
                        if (o == null) {
                            try {
                                r.delete(uri, null, null);     // don't leave an empty entry behind
                            } catch (RuntimeException ignored) {
                            }
                        }
                    }
                    if (o != null) {
                        synchronized (created) {
                            created.add(uri);
                        }
                        return o;
                    }
                }
                AppState.message("Download folder not available - saving in the app folder");
            } catch (Exception e) {              // MediaStore quirks: fall back, never lose the log
                AppState.message("Download folder not writable (" + e.getMessage() + ") - saving in the app folder");
            }
        }
        File f = new File(privateDir(ctx), name);
        synchronized (created) {
            created.add(LogProvider.uriFor(ctx, f.getName()));
        }
        return new FileOutputStream(f);
    }

    @Override
    public String where(String name) {
        File f = new File(privateDir(ctx), name);
        return f.exists() ? f.getPath() : "Download/" + folder() + "/" + name;
    }
}
