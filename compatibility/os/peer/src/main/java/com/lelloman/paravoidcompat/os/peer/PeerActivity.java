package com.lelloman.paravoidcompat.os.peer;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Separate UID, no storage permissions, no Paravoid dependency. */
public final class PeerActivity extends Activity {
    private BinderClient binderClient;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if ("foreground".equals(getIntent().getStringExtra("scenario"))) {
            android.widget.Button button = new android.widget.Button(this);
            button.setText("Start foreground service");
            boolean[] started = {false};
            setContentView(button);
            button.setOnClickListener(view -> {
                try {
                    Intent service = new Intent().setClassName(getIntent().getStringExtra("target"),
                        "com.lelloman.paravoidcompat.os.ForegroundProbeService")
                        .putExtra("foregroundParcel", new com.lelloman.paravoidcompat.os.contract.WireMessage(
                            getIntent().getStringExtra("run"), 0, 0, "foreground", false));
                    if (!started[0]) startForegroundService(service);
                    else if (!stopService(service)) throw new IllegalStateException("Service was not running");
                    started[0] = !started[0];
                    button.setText(started[0] ? "Stop foreground service" : "Start foreground service");
                } catch (Exception error) {
                    getSharedPreferences("os-probe", MODE_PRIVATE).edit()
                        .putString("foregroundPeerError", error.toString()).commit();
                }
            });
            getSharedPreferences("os-probe", MODE_PRIVATE).edit()
                .putString("foregroundReady", getIntent().getStringExtra("run"))
                .putInt("foregroundPeerPid", android.os.Process.myPid()).commit();
            return;
        }
        if ("binder".equals(getIntent().getStringExtra("scenario"))) {
            binderClient = new BinderClient(this);
            binderClient.start();
            return;
        }
        if ("result".equals(getIntent().getStringExtra("scenario"))) {
            android.widget.Button button = new android.widget.Button(this);
            button.setText("Return activity result");
            setContentView(button);
            getSharedPreferences("os-probe", MODE_PRIVATE).edit()
                .putString("resultReady", getIntent().getStringExtra("run"))
                .putInt("resultPeerPid", android.os.Process.myPid()).commit();
            button.setOnClickListener(view -> {
                if (getIntent().getBooleanExtra("cancel", false)) setResult(RESULT_CANCELED);
                else setResult(RESULT_OK, new Intent().putExtra("run", getIntent().getStringExtra("run"))
                    .putExtra("reply", "reply-λ").putExtra("uid", android.os.Process.myUid()));
                finish();
            });
            return;
        }
        if ("pending".equals(getIntent().getStringExtra("scenario"))) {
            android.app.PendingIntent pending = getIntent().getParcelableExtra("pending");
            android.widget.Button button = new android.widget.Button(this);
            button.setText("Send pending action");
            setContentView(button);
            getSharedPreferences("os-probe", MODE_PRIVATE).edit()
                .putString("ready", getIntent().getStringExtra("run"))
                .putString("creator", pending.getCreatorPackage())
                .putInt("creatorUid", pending.getCreatorUid())
                .putInt("uid", android.os.Process.myUid()).commit();
            button.setOnClickListener(view -> {
                try {
                    pending.send(this, 0, new Intent().putExtra("probeRun", "tampered")
                        .putExtra("injected", true));
                    button.setText("Pending action sent");
                } catch (android.app.PendingIntent.CanceledException error) {
                    button.setText(error.toString());
                }
            });
            return;
        }
        Intent result = new Intent().putExtra("uid", android.os.Process.myUid())
            .putExtra("run", getIntent().getStringExtra("run"));
        try (InputStream input = getContentResolver().openInputStream(getIntent().getData())) {
            byte[] bytes = new byte[4096];
            int count = input.read(bytes);
            result.putExtra("read", new String(bytes, 0, count, StandardCharsets.UTF_8));
        } catch (SecurityException denied) {
            result.putExtra("read", "DENIED");
        } catch (Exception error) {
            result.putExtra("read", error.toString());
        }
        try (OutputStream output = getContentResolver().openOutputStream(getIntent().getData(), "wa")) {
            result.putExtra("write", "ALLOWED");
        } catch (SecurityException denied) {
            result.putExtra("write", "DENIED");
        } catch (Exception error) {
            result.putExtra("write", error.toString());
        }
        setResult(RESULT_OK, result);
        if (getIntent().getBooleanExtra("cold", false)) {
            getSharedPreferences("os-probe", MODE_PRIVATE).edit()
                .putString("run", result.getStringExtra("run"))
                .putString("read", result.getStringExtra("read"))
                .putString("write", result.getStringExtra("write"))
                .putInt("uid", android.os.Process.myUid()).commit();
        }
        finish();
    }
    @Override protected void onDestroy() {
        if (binderClient != null) binderClient.close();
        super.onDestroy();
    }
}
