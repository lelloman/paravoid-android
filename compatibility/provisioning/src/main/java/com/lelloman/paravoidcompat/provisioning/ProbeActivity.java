package com.lelloman.paravoidcompat.provisioning;

import android.app.Activity;
import android.os.Bundle;
import android.content.pm.PackageManager;
import android.widget.TextView;
import com.lelloman.paravoidandroid.runtime.ApkProvisioningProbe;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.JSONObject;

/** Downloads harmless bytes only. No downloaded classes/resources are ever loaded. */
public final class ProbeActivity extends Activity {
    private JSONObject grant;
    private String endpoint;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        TextView status = new TextView(this);
        status.setText("Provisioning probe running");
        setContentView(status);
        int port = getIntent().getIntExtra("port", 18765);
        String stage = getIntent().getStringExtra("stage");
        new Thread(() -> {
            JSONObject result = new JSONObject();
            try {
                if (port < 1024 || port > 65535) throw new IllegalArgumentException("port");
                endpoint = "http://127.0.0.1:" + port;
                String app = getPackageName();
                String mode = getPackageManager().getApplicationInfo(app, PackageManager.GET_META_DATA).metaData.getString("probe.mode");
                result.put("stage", stage);
                android.content.SharedPreferences preferences = getSharedPreferences("probe", MODE_PRIVATE);
                String installation = preferences.getString("installation", null);
                if (installation == null) {
                    installation = java.util.UUID.randomUUID().toString();
                    if (!preferences.edit().putString("installation", installation).commit()) throw new IllegalStateException("preferences");
                }
                result.put("installation", installation);
                result.put("mode", mode);
                result.put("version", getPackageManager().getPackageInfo(app, 0).getLongVersionCode());
                result.put("shellReader", ApkProvisioningProbe.class.getClassLoader() != getClass().getClassLoader());
                if (com.lelloman.paravoidandroid.runtime.SignedDeliveryProbe.configured(this)) {
                    com.lelloman.paravoidandroid.runtime.SignedDeliveryProbe.run(this, result, mode, endpoint);
                    finishResult(result, status);
                    return;
                }
                if (mode.equals("key")) {
                    byte[] bytes = ApkProvisioningProbe.read(new File(getApplicationInfo().sourceDir));
                    if (bytes == null) {
                        result.put("status", "missing-key");
                        finishResult(result, status);
                        return;
                    }
                    grant = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
                    if (grant.length() != 4 || grant.getInt("version") != 1 || !grant.getString("applicationId").equals(app)) {
                        result.put("status", "invalid-record");
                        finishResult(result, status);
                        return;
                    }
                    if (!grant.getString("keyId").matches("[a-zA-Z0-9_-]{1,64}") ||
                        !grant.getString("key").matches("[a-zA-Z0-9_-]{32,128}")) throw new IllegalArgumentException("record");
                    result.put("keyId", grant.getString("keyId")); // Never report key material.
                }
                String base = "/probe/v1/apps/" + app + "/";
                Response head = request(base + "head", null, null);
                result.put("http", head.code);
                if (head.code != 200) {
                    result.put("status", "http-error");
                } else {
                    JSONObject metadata = new JSONObject(new String(head.body, StandardCharsets.UTF_8));
                    if (!metadata.getString("profile").equals("UNSIGNED_FIXTURE_ONLY") ||
                        !metadata.getString("applicationId").equals(app)) throw new IllegalArgumentException("metadata");
                    String path = metadata.getString("path");
                    if (!path.startsWith(base + "releases/") || path.contains("..") || path.contains("?"))
                        throw new IllegalArgumentException("artifact path");
                    if (metadata.getInt("size") < 1 || metadata.getInt("size") > 1024 * 1024)
                        throw new IllegalArgumentException("artifact size");
                    Response unchanged = request(base + "head", "If-None-Match", head.etag);
                    if (unchanged.code != 304) throw new IllegalStateException("conditional response");
                    Response first = request(path, "Range", "bytes=0-7");
                    Response rest = request(path, "Range", "bytes=8-");
                    if (first.code != 206 || rest.code != 206 || first.etag == null || !first.etag.equals(rest.etag))
                        throw new IllegalStateException("range response");
                    ByteArrayOutputStream joined = new ByteArrayOutputStream();
                    joined.write(first.body); joined.write(rest.body);
                    byte[] artifact = joined.toByteArray();
                    if (artifact.length != metadata.getInt("size") || !hex(MessageDigest.getInstance("SHA-256").digest(artifact)).equals(metadata.getString("sha256")))
                        throw new IllegalStateException("transfer digest");
                    // Durable harmless transfer result; never interpreted as code or a VPK.
                    try (FileOutputStream output = openFileOutput("artifact.bin", MODE_PRIVATE)) { output.write(artifact); }
                    result.put("status", "downloaded");
                    result.put("conditional", unchanged.code);
                    result.put("sha256", metadata.getString("sha256"));
                }
            } catch (Exception failure) {
                try { result.put("status", "failure"); result.put("errorType", failure.getClass().getSimpleName()); }
                catch (Exception ignored) { }
            }
            finishResult(result, status);
        }, "provisioning-probe").start();
    }

    private Response request(String path, String header, String value) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint + path).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(5000); connection.setReadTimeout(5000);
        if (grant != null) {
            connection.setRequestProperty("X-Paravoid-Key-Id", grant.getString("keyId"));
            connection.setRequestProperty("Authorization", "Bearer " + grant.getString("key"));
        }
        if (header != null && value != null) connection.setRequestProperty(header, value);
        try {
            int code = connection.getResponseCode();
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            if (code == 200 || code == 206) {
                try (InputStream input = connection.getInputStream()) {
                    byte[] buffer = new byte[4096]; int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (body.size() + count > 1024 * 1024) throw new IllegalStateException("response limit");
                        body.write(buffer, 0, count);
                    }
                }
            }
            return new Response(code, connection.getHeaderField("ETag"), body.toByteArray());
        } finally { connection.disconnect(); }
    }
    private void finishResult(JSONObject result, TextView status) {
        try (FileOutputStream output = openFileOutput("result.json", MODE_PRIVATE)) {
            output.write(result.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
        runOnUiThread(() -> status.setText(result.toString()));
    }
    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format("%02x", value & 255));
        return result.toString();
    }
    private static final class Response {
        final int code; final String etag; final byte[] body;
        Response(int code, String etag, byte[] body) { this.code = code; this.etag = etag; this.body = body; }
    }
}
