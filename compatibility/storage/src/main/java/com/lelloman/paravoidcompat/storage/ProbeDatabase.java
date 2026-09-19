package com.lelloman.paravoidcompat.storage;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.room.*;

@Database(entities = ProbeDatabase.Row.class, version = 1, exportSchema = false)
public abstract class ProbeDatabase extends RoomDatabase implements AutoCloseable {
    public abstract Rows rows();

    static ProbeDatabase open(Context context) {
        ProbeDatabase db = Room.databaseBuilder(context, ProbeDatabase.class, "probe.db").build();
        if (db.getClass().getClassLoader() != ProbeDatabase.class.getClassLoader()) {
            throw new IllegalStateException("Generated Room implementation has wrong loader");
        }
        return db;
    }

    @Entity(tableName = "probe_rows")
    public static class Row {
        @PrimaryKey @NonNull public String token;
        public int writerPid;
        public int workerPid;
        public Row(@NonNull String token, int writerPid, int workerPid) {
            this.token = token;
            this.writerPid = writerPid;
            this.workerPid = workerPid;
        }
    }

    @Dao
    public interface Rows {
        @Insert void insert(Row row);
        @Query("SELECT * FROM probe_rows WHERE token = :token") Row find(String token);
        @Query("UPDATE probe_rows SET workerPid = :pid WHERE token = :token") int complete(String token, int pid);
    }
}
