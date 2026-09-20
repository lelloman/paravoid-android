package com.lelloman.paravoidcompat.jni;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import com.lelloman.paravoidcompat.jni.bridge.NativeBridge;

public final class ProbeProvider extends ContentProvider {
    static String result = "not run";
    @Override public boolean onCreate() {
        result = probe(() -> {
            if (ProbeApplication.created || NativeBridge.registered(33) != 42
                    || NativeBridge.callback() != 42) throw new AssertionError("Provider JNI startup/order");
        });
        return true;
    }
    static String probe(Runnable action) {
        try { action.run(); return "PASS"; }
        catch (RuntimeException | LinkageError | AssertionError error) {
            android.util.Log.e("JniProbe", "Startup probe failed", error);
            return error.toString();
        }
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
