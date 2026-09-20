package com.lelloman.paravoidcompat.hiltwork;

import android.app.Application;
import android.content.Context;
import androidx.room.Room;
import dagger.hilt.android.qualifiers.ApplicationContext;
import java.util.UUID;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public final class Repository {
    final String graph = UUID.randomUUID().toString();
    final Context context;
    @Inject public Repository(@ApplicationContext Context context, Application application) {
        if (context != application) throw new IllegalStateException("Expected real Application context binding");
        this.context = context;
    }
    ProbeDatabase open() {
        ProbeDatabase db = Room.databaseBuilder(context, ProbeDatabase.class, "hilt-work.db").build();
        if (db.getClass().getClassLoader() != getClass().getClassLoader()) {
            throw new IllegalStateException("Room implementation escaped payload loader");
        }
        return db;
    }
    void record(String key, String value) {
        if (!context.getSharedPreferences("hilt-work-probe", Context.MODE_PRIVATE).edit().putString(key, value).commit()) {
            throw new IllegalStateException("Cannot persist observation");
        }
    }
}
