package com.lelloman.paravoidandroid.runtime;

import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import java.util.Collections;

/** Complete-profile shell controls remain reachable without cooperation from payload UI. */
final class ShellControlShortcut {
    static final String ID = "paravoid.updates";
    static void install(Application app) {
        try {
            ShortcutManager shortcuts = app.getSystemService(ShortcutManager.class);
            if (shortcuts == null) return;
            ShortcutInfo shortcut = new ShortcutInfo.Builder(app, ID)
                .setShortLabel("App updates")
                .setLongLabel("Paravoid app updates")
                .setIcon(Icon.createWithResource(app, android.R.drawable.ic_menu_manage))
                .setActivity(new ComponentName(app, LauncherActivity.class))
                .setIntent(new Intent(Intent.ACTION_VIEW)
                    .setClass(app, com.lelloman.paravoidandroid.delivery.ShellUpdatesActivity.class))
                .build();
            // Preserve app-owned shortcuts. This reserved ID belongs to the shell.
            if (!shortcuts.addDynamicShortcuts(Collections.singletonList(shortcut)))
                android.util.Log.w("ParavoidAndroid", "Update shortcut could not be registered");
        } catch (RuntimeException unavailable) {
            // Launcher/rate-limit failures must never quarantine a usable payload.
            android.util.Log.w("ParavoidAndroid", "Update shortcut unavailable");
        }
    }
}
