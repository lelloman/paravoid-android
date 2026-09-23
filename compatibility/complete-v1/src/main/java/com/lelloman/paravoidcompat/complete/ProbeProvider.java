package com.lelloman.paravoidcompat.complete;
import android.content.*;
import android.database.*;
import android.net.Uri;

public class ProbeProvider extends ContentProvider {
    public ProbeProvider() { StartupProbe.hit("provider-constructor"); }
    public static final class Worker extends ProbeProvider {}
    public static final class Private extends ProbeProvider {}
    static boolean created;
    public boolean onCreate() {
        StartupProbe.hit("provider-create");
        getContext().getString(R.string.generation);
        created = true; return true;
    }
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        MatrixCursor cursor = new MatrixCursor(new String[]{"generation"});
        cursor.addRow(new Object[]{getContext().getString(R.string.generation)}); return cursor;
    }
    public String getType(Uri uri) { return "vnd.android.cursor.item/probe"; }
    /** Fixture-only probe of the installed runtime; no production API or capacity override. */
    @Override public android.os.Bundle call(String method, String arg, android.os.Bundle extras) {
        if ("probe.read-pressure".equals(method)) {
            android.os.Bundle result = new android.os.Bundle();
            try (java.io.InputStream input = getContext().getAssets().open("paravoid-pressure.bin")) {
                java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
                byte[] buffer = new byte[65536]; long total = 0; int count;
                while ((count = input.read(buffer)) != -1) { digest.update(buffer, 0, count); total += count; }
                StringBuilder hex = new StringBuilder();
                for (byte value : digest.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
                result.putLong("bytes", total); result.putString("sha256", hex.toString());
                return result;
            } catch (Exception failure) { throw new IllegalStateException(failure); }
        }
        if (!"probe.reserve-space".equals(method)) return super.call(method, arg, extras);
        android.os.Bundle result = new android.os.Bundle();
        result.putInt("pid", android.os.Process.myPid());
        try {
            Class<?> shell = Class.forName("com.lelloman.paravoidandroid.runtime.ShellApplication");
            java.lang.reflect.Field instance = shell.getDeclaredField("instance");
            instance.setAccessible(true);
            java.lang.reflect.Field complete = shell.getDeclaredField("complete");
            complete.setAccessible(true);
            Object runtime = complete.get(instance.get(null));
            java.lang.reflect.Field lifecycle = runtime.getClass().getDeclaredField("lifecycle");
            lifecycle.setAccessible(true);
            Object owner = lifecycle.get(runtime);
            try (AutoCloseable reservation = (AutoCloseable) owner.getClass()
                    .getMethod("reserveEmbedded", long.class).invoke(owner, Long.parseLong(arg))) {
                result.putString("result", "ADMITTED");
            }
        } catch (java.lang.reflect.InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            try { result.putString("result", cause.getClass().getField("code").get(cause).toString()); }
            catch (ReflectiveOperationException unexpected) { throw new IllegalStateException(cause); }
        } catch (Exception failure) { throw new IllegalStateException(failure); }
        return result;
    }
    public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
