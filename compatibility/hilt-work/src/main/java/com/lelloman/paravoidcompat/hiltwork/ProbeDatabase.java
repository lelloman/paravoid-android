package com.lelloman.paravoidcompat.hiltwork;

import androidx.annotation.NonNull;
import androidx.room.*;

@Database(entities = ProbeDatabase.Row.class, version = 1, exportSchema = false)
public abstract class ProbeDatabase extends RoomDatabase implements AutoCloseable {
    public abstract Rows rows();
    @Entity(tableName = "probe_rows")
    public static class Row {
        @PrimaryKey @NonNull public String token;
        public int writerPid;
        public int workerPid;
        public String graph;
        public Row(@NonNull String token, int writerPid, int workerPid, String graph) {
            this.token = token; this.writerPid = writerPid; this.workerPid = workerPid; this.graph = graph;
        }
    }
    @Dao public interface Rows {
        @Insert void insert(Row row);
        @Query("SELECT * FROM probe_rows WHERE token = :token") Row find(String token);
        @Query("UPDATE probe_rows SET workerPid = :pid, graph = :graph WHERE token = :token")
        int complete(String token, int pid, String graph);
    }
}
