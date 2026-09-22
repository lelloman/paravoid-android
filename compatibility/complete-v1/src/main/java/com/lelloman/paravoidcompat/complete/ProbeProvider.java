package com.lelloman.paravoidcompat.complete;
import android.content.*;
import android.database.*;
import android.net.Uri;

public class ProbeProvider extends ContentProvider {
    public static final class Worker extends ProbeProvider {}
    public static final class Private extends ProbeProvider {}
    static boolean created;
    public boolean onCreate() {
        getContext().getString(R.string.generation);
        created = true; return true;
    }
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        MatrixCursor cursor = new MatrixCursor(new String[]{"generation"});
        cursor.addRow(new Object[]{getContext().getString(R.string.generation)}); return cursor;
    }
    public String getType(Uri uri) { return "vnd.android.cursor.item/probe"; }
    public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
