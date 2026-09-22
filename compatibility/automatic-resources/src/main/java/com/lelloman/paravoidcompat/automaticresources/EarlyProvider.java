package com.lelloman.paravoidcompat.automaticresources;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;

public class EarlyProvider extends ContentProvider {
    static String earlyTitle;
    static String earlyJava;
    static boolean beforeApplication;
    @Override public boolean onCreate() {
        earlyJava = JavaProbe.verify();
        earlyTitle = ProbeActivity.title(getContext());
        beforeApplication = !ProbeApplication.created;
        return true;
    }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
