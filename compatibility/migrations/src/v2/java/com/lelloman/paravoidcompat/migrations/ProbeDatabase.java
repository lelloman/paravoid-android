package com.lelloman.paravoidcompat.migrations;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.room.*;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(entities = ProbeDatabase.Note.class, version = 2, exportSchema = true)
public abstract class ProbeDatabase extends RoomDatabase implements AutoCloseable {
    static int migrations;
    public abstract Notes notes();
    static ProbeDatabase open(Context context) {
        return Room.databaseBuilder(context, ProbeDatabase.class, "notes.db")
            .addMigrations(new Migration(1, 2) {
                @Override public void migrate(@NonNull SupportSQLiteDatabase db) {
                    db.execSQL("ALTER TABLE notes ADD COLUMN priority INTEGER NOT NULL DEFAULT 7");
                    db.execSQL("CREATE UNIQUE INDEX index_notes_title ON notes(title)");
                    migrations++;
                }
            }).build();
    }
    @Entity(tableName = "notes", indices = @Index(value = "title", unique = true))
    public static class Note {
        @PrimaryKey public long id;
        @NonNull public String title;
        @ColumnInfo(defaultValue = "7") public int priority = 7;
        public Note(long id, @NonNull String title) { this.id = id; this.title = title; }
    }
    @Dao public interface Notes {
        @Insert void insert(Note note);
        @Query("SELECT * FROM notes WHERE id=:id") Note find(long id);
        @Query("SELECT COUNT(*) FROM notes") int count();
    }
}
