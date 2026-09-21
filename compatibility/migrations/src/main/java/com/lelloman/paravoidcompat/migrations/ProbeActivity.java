package com.lelloman.paravoidcompat.migrations;

import android.app.Activity;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import org.json.JSONObject;

public final class ProbeActivity extends Activity {
    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }
    private static long scalar(SQLiteDatabase db, String sql) {
        try (Cursor cursor = db.rawQuery(sql, null)) { cursor.moveToFirst(); return cursor.getLong(0); }
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String run = getIntent().getStringExtra("run");
        String phase = getIntent().getStringExtra("phase");
        String request = getIntent().getStringExtra("request");
        new Thread(() -> probe(run, phase, request), "migration-probe").start();
    }
    private void probe(String run, String phase, String request) {
        JSONObject result = new JSONObject();
        try {
            result.put("run", run).put("request", request).put("phase", phase)
                .put("version", BuildConfig.DATABASE_VERSION).put("pid", android.os.Process.myPid());
            boolean shell = getPackageName().endsWith(".paravoid");
            ClassLoader loader = getClass().getClassLoader();
            require((loader instanceof dalvik.system.InMemoryDexClassLoader) == shell, "App loader mismatch");
            try (ProbeDatabase db = ProbeDatabase.open(this)) {
                require(db.getClass().getClassLoader() == loader && db.notes().getClass().getClassLoader() == loader,
                    "Generated database/DAO loader mismatch");
                if ("downgrade".equals(phase)) {
                    try { db.notes().count(); throw new AssertionError("Downgrade unexpectedly opened"); }
                    catch (IllegalStateException expected) {
                        require(expected.getMessage().contains("from 2 to 1"), expected.toString());
                        result.put("downgradeRejected", true).put("reason", expected.getMessage());
                    }
                } else if ("seed".equals(phase)) {
                    require(db.notes().count() == 0, "Not a fresh v1 database");
                    db.runInTransaction(() -> {
                        db.notes().insert(new ProbeDatabase.Note(1, "α-" + run));
                        db.notes().insert(new ProbeDatabase.Note(2, "β-" + run));
                    });
                } else {
                    require(("α-" + run).equals(db.notes().find(1).title), "First record changed");
                    require(("β-" + run).equals(db.notes().find(2).title), "Second record changed");
                    if ("upgrade".equals(phase)) {
                        require(db.notes().count() == 2 && ProbeDatabase.migrations == 1, "Migration did not run once");
                        db.notes().insert(new ProbeDatabase.Note(3, "γ-" + run));
                        db.getOpenHelper().getWritableDatabase().execSQL("UPDATE notes SET priority=9 WHERE id=3");
                        boolean rolledBack = false;
                        try {
                            db.runInTransaction(() -> {
                                db.notes().insert(new ProbeDatabase.Note(4, "rollback-" + run));
                                throw new IllegalArgumentException("intentional rollback");
                            });
                        } catch (IllegalArgumentException expected) { rolledBack = true; }
                        require(rolledBack && db.notes().find(4) == null, "Transaction rollback failed");
                        boolean unique = false;
                        try { db.notes().insert(new ProbeDatabase.Note(5, "α-" + run)); }
                        catch (android.database.sqlite.SQLiteConstraintException expected) { unique = true; }
                        require(unique && db.notes().find(5) == null, "Unique index not enforced");
                        result.put("transactionRollback", true).put("uniqueIndex", true);
                    } else require(ProbeDatabase.migrations == 0, "Migration repeated after restart");
                    require(db.notes().count() == 3, "Rows lost or duplicated");
                    require(("γ-" + run).equals(db.notes().find(3).title), "v2 record changed");
                }
            }
            try (SQLiteDatabase raw = SQLiteDatabase.openDatabase(getDatabasePath("notes.db").getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
                int version = (int) scalar(raw, "PRAGMA user_version");
                result.put("databaseVersion", version).put("rows", scalar(raw, "SELECT COUNT(*) FROM notes"));
                require(version == ("seed".equals(phase) ? 1 : 2), "Unexpected database version");
                if (version == 2) {
                    require(scalar(raw, "SELECT COUNT(*) FROM notes WHERE id IN (1,2) AND priority=7") == 2, "Migration defaults wrong");
                    require(scalar(raw, "SELECT priority FROM notes WHERE id=3") == 9, "v2 write lost");
                    require(scalar(raw, "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name='index_notes_title'") == 1, "Index lost");
                }
            }
            result.put("migrations", ProbeDatabase.migrations).put("passed", true);
        } catch (Throwable error) {
            try { result.put("error", error.toString()); } catch (Exception ignored) { }
        }
        getSharedPreferences("migration-probe", MODE_PRIVATE).edit().putString("result", result.toString()).commit();
    }
}
