package com.lelloman.paravoidcompat.os.peer;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Separate UID, no storage permissions, no Paravoid dependency. */
public final class PeerActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
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
}
