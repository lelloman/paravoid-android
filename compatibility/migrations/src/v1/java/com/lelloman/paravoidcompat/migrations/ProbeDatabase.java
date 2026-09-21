package com.lelloman.paravoidcompat.migrations;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.room.*;

@Database(entities = ProbeDatabase.Note.class, version = 1, exportSchema = true)
public abstract class ProbeDatabase extends RoomDatabase implements AutoCloseable {
    static int migrations;
    public abstract Notes notes();
    static ProbeDatabase open(Context context) {
        return Room.databaseBuilder(context, ProbeDatabase.class, "notes.db").build();
    }
    @Entity(tableName = "notes")
    public static class Note {
        @PrimaryKey public long id;
        @NonNull public String title;
        public Note(long id, @NonNull String title) { this.id = id; this.title = title; }
    }
    @Dao public interface Notes {
        @Insert void insert(Note note);
        @Query("SELECT * FROM notes WHERE id=:id") Note find(long id);
        @Query("SELECT COUNT(*) FROM notes") int count();
    }
}
