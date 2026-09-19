package com.lelloman.paravoidandroid.sample;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import dalvik.system.InMemoryDexClassLoader;

public final class ProbeProvider extends ContentProvider {
    private int applicationCountAtStartup;
    private boolean hadApplicationContext;

    @Override public boolean onCreate() {
        applicationCountAtStartup = SampleApplication.initializationCount;
        hadApplicationContext = getContext().getApplicationContext() != null;
        return true;
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        Bundle report = new Bundle();
        report.putInt("applicationCountAtStartup", applicationCountAtStartup);
        report.putInt("applicationCountNow", SampleApplication.initializationCount);
        report.putBoolean("hadApplicationContext", hadApplicationContext);
        report.putBoolean("payload", getClass().getClassLoader() instanceof InMemoryDexClassLoader);
        return report;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
