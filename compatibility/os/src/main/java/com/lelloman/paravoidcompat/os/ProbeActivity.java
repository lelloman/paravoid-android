package com.lelloman.paravoidcompat.os;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

public final class ProbeActivity extends Activity {
    private final JSONObject results = new JSONObject();
    private Uri uri;
    private String run;
    private void check(String name, boolean passed) throws Exception {
        results.put(name, passed ? "PASS" : "FAIL");
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getSharedPreferences("os-probe", MODE_PRIVATE).edit()
            .putInt("activityPid", android.os.Process.myPid()).commit();
        run = getIntent().getStringExtra("probeRun");
        try {
            File directory = new File(getFilesDir(), "shared");
            directory.mkdirs();
            File file = new File(directory, "hello.txt");
            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write(("hello-λ-" + run).getBytes(StandardCharsets.UTF_8));
            }
            uri = FileProvider.getUriForFile(this, getPackageName() + ".files", file);
            check("content_authority", "content".equals(uri.getScheme())
                && (getPackageName() + ".files").equals(uri.getAuthority()));
            boolean rejected = false;
            try { FileProvider.getUriForFile(this, getPackageName() + ".files", new File(getFilesDir(), "private.txt")); }
            catch (IllegalArgumentException expected) { rejected = true; }
            check("outside_path_rejected", rejected);
            check("dependency_same_loader", FileProvider.class.getClassLoader() == getClass().getClassLoader());
            boolean shell = getPackageName().endsWith(".paravoid");
            boolean parentSees = true;
            try { getClass().getClassLoader().getParent().loadClass(FileProvider.class.getName()); }
            catch (ClassNotFoundException expected) { parentSees = false; }
            check("shell_parent_isolation", !shell || !parentSees);
            launchPeer(10, false);
        } catch (Exception error) { fail(error); }
    }
    private void launchPeer(int request, boolean grant) {
        Intent intent = new Intent().setClassName("com.lelloman.paravoidcompat.os.peer",
            "com.lelloman.paravoidcompat.os.peer.PeerActivity").setData(uri).putExtra("run", run);
        intent.setClipData(ClipData.newRawUri("probe", uri));
        if (grant) intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent, request);
    }
    @Override protected void onActivityResult(int request, int code, Intent data) {
        super.onActivityResult(request, code, data);
        try {
            check("result_" + request, code == RESULT_OK && data != null && run.equals(data.getStringExtra("run")));
            check("cross_uid_" + request, data.getIntExtra("uid", -1) > 0
                && data.getIntExtra("uid", -1) != android.os.Process.myUid());
            check("read_" + request, (request == 11 ? "hello-λ-" + run : "DENIED").equals(data.getStringExtra("read")));
            check("write_denied_" + request, "DENIED".equals(data.getStringExtra("write")));
            if (request == 10) launchPeer(11, true);
            else if (request == 11) {
                revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                launchPeer(12, false);
            } else {
                // Explicit package grant survives Activity completion and process death.
                // The driver clears fixture packages, so this test grant cannot leak runs.
                grantUriPermission("com.lelloman.paravoidcompat.os.peer", uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
                report();
            }
        } catch (Exception error) { fail(error); }
    }
    private void fail(Exception error) {
        try { results.put("error", error.toString()); } catch (Exception ignored) { }
        report();
    }
    private void report() {
        getSharedPreferences("os-probe", MODE_PRIVATE).edit().putString("run", run)
            .putString("uri", uri == null ? "" : uri.toString())
            .putInt("activityPid", android.os.Process.myPid())
            .putString("results", results.toString()).commit();
    }
}
