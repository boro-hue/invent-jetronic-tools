package io.github.borohue.mustangobd;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/** Read-only sharing of log files kept in the app's own folder (Android 9 and older). */
public class LogProvider extends ContentProvider {
    public static final String AUTHORITY = "io.github.borohue.mustangobd.logs";

    public static Uri uriFor(String fileName) {
        return Uri.parse("content://" + AUTHORITY + "/" + Uri.encode(fileName));
    }

    private File file(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || name.contains("/") || name.contains("..")) throw new FileNotFoundException(uri.toString());
        File f = new File(DownloadsSink.privateDir(getContext()), name);
        if (!f.isFile()) throw new FileNotFoundException(uri.toString());
        return f;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String sel, String[] args, String sort) {
        try {
            File f = file(uri);
            MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
            c.addRow(new Object[]{f.getName(), f.length()});
            return c;
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    @Override
    public String getType(Uri uri) {
        String n = uri.getLastPathSegment() == null ? "" : uri.getLastPathSegment();
        return n.endsWith(".csv") ? "text/csv" : n.endsWith(".json") ? "application/json" : "text/plain";
    }

    @Override
    public Uri insert(Uri uri, ContentValues v) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int delete(Uri uri, String s, String[] a) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int update(Uri uri, ContentValues v, String s, String[] a) {
        throw new UnsupportedOperationException("read-only");
    }
}
