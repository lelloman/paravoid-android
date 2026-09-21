package com.lelloman.paravoidandroid.runtime;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Base64;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;

/** Independent verifier/client for a bounded fixture profile; never loads downloaded code. */
public final class SignedDeliveryProbe {
    private static final int MAX = 1024 * 1024;
    private static final String[] GRANT = {"applicationId", "audience", "keyId", "key", "issued", "expires"};
    private static final String[] HEAD = {"applicationId", "contract", "channel", "revision", "releaseId",
        "payloadVersion", "issued", "expires", "size", "sha256"};

    static final class Rejected extends Exception {
        final String reason;
        Rejected(String reason) { super(reason); this.reason = reason; }
    }
    static void require(boolean value, String reason) throws Rejected {
        if (!value) throw new Rejected(reason);
    }
    public static boolean configured(Context context) throws IOException {
        return Arrays.asList(context.getAssets().list("")).contains("probe-trust");
    }
    private static byte[] asset(Context c, String path) throws Exception {
        try (InputStream in = c.getAssets().open("probe-trust/" + path)) { return read(in, 4096); }
    }
    private static byte[] read(InputStream in, int limit) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096]; int count;
        while ((count = in.read(buf)) != -1) {
            require(out.size() + count <= limit, "bounds");
            out.write(buf, 0, count);
        }
        return out.toByteArray();
    }
    private static byte[] decode(String encoded) throws Exception {
        require(encoded.matches("[A-Za-z0-9+/]*={0,2}"), "encoding");
        byte[] result;
        try { result = Base64.decode(encoded, Base64.NO_WRAP); }
        catch (IllegalArgumentException e) { throw new Rejected("encoding"); }
        require(Base64.encodeToString(result, Base64.NO_WRAP).equals(encoded), "encoding");
        return result;
    }
    static Map<String, String> verify(byte[] bytes, String kind, byte[] root) throws Exception {
        require(bytes.length <= 4096, "bounds");
        for (byte b : bytes) require(b >= 0, "encoding");
        String[] lines = new String(bytes, StandardCharsets.US_ASCII).split("\n", -1);
        require(lines.length == 5 && lines[0].equals("PARAVOID-PROBE-1") && lines[1].equals(kind)
            && lines[4].isEmpty(), "envelope");
        byte[] body = decode(lines[2]), signature = decode(lines[3]);
        require(signature.length == 256, "signature");
        PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(root));
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(key);
        verifier.update(("PARAVOID-PROBE-1\n" + kind + "\n").getBytes(StandardCharsets.US_ASCII));
        verifier.update(body);
        require(verifier.verify(signature), "signature");
        for (byte b : body) require(b >= 0, "encoding");
        String[] fields;
        if (kind.equals("grant")) fields = GRANT;
        else if (kind.equals("head")) fields = HEAD;
        else if (kind.equals("manifest")) fields = new String[] {"applicationId", "contract", "releaseId",
            "payloadVersion", "format", "inventorySize", "inventorySha256"};
        else throw new Rejected("envelope");
        String[] rows = new String(body, StandardCharsets.US_ASCII).split("\n", -1);
        require(rows.length == fields.length + 1 && rows[fields.length].isEmpty(), "fields");
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i < fields.length; i++) {
            String prefix = fields[i] + "=";
            require(rows[i].startsWith(prefix), "fields");
            String value = rows[i].substring(prefix.length());
            require(value.matches("[A-Za-z0-9._:/-]{1,256}"), "fields");
            values.put(fields[i], value);
        }
        return values;
    }
    private static long number(Map<String, String> values, String key) throws Exception {
        String value = values.get(key);
        require(value.matches("0|[1-9][0-9]{0,15}"), "number");
        long result = Long.parseLong(value);
        require(result <= 9007199254740991L, "number");
        return result;
    }
    private static void fresh(Map<String, String> values, long maxAge) throws Exception {
        long issued = number(values, "issued"), expires = number(values, "expires");
        long now = System.currentTimeMillis() / 1000;
        require(issued <= now + 60 && expires > now && expires > issued && expires - issued <= maxAge, "freshness");
    }
    private static void write(AtomicFile file, byte[] bytes) throws Exception {
        FileOutputStream out = file.startWrite();
        try { out.write(bytes); file.finishWrite(out); }
        catch (Exception e) { file.failWrite(out); throw e; }
    }
    static String hash(byte[] bytes) throws Exception {
        StringBuilder out = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) out.append(String.format("%02x", b & 255));
        return out.toString();
    }
    private static final class Response {
        final int code; final byte[] body; final String etag, range;
        Response(int code, byte[] body, String etag, String range) {
            this.code = code; this.body = body; this.etag = etag; this.range = range;
        }
    }
    private static Response request(String endpoint, String path, Map<String, String> grant,
                                    String conditional, String range, String validator, int limit) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(endpoint + path).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(5000); c.setReadTimeout(5000);
        if (grant != null) {
            c.setRequestProperty("X-Paravoid-Key-Id", grant.get("keyId"));
            c.setRequestProperty("Authorization", "Bearer " + grant.get("key"));
        }
        if (conditional != null) c.setRequestProperty("If-None-Match", conditional);
        if (range != null) c.setRequestProperty("Range", range);
        if (validator != null) c.setRequestProperty("If-Range", validator);
        try {
            int code = c.getResponseCode();
            byte[] body = new byte[0];
            if (code == 200 || code == 206) {
                try (InputStream in = c.getInputStream()) { body = read(in, limit); }
            }
            return new Response(code, body, c.getHeaderField("ETag"), c.getHeaderField("Content-Range"));
        } finally { c.disconnect(); }
    }

    public static void run(Context context, JSONObject result, String mode, String endpoint) throws Exception {
        try { execute(context, result, mode, endpoint); }
        catch (Rejected e) { result.put("status", "rejected"); result.put("reason", e.reason); }
    }
    private static void execute(Context c, JSONObject result, String mode, String endpoint) throws Exception {
        JSONObject policy = new JSONObject(new String(asset(c, "policy.json"), StandardCharsets.UTF_8));
        byte[] issuer = asset(c, "issuer.der"), publisher = asset(c, "publisher.der");
        String app = c.getPackageName(), contract = policy.getString("contract"), channel = policy.getString("channel");
        Map<String, String> grant = null;
        if (mode.equals("key")) {
            byte[] record = ApkProvisioningProbe.read(new File(c.getApplicationInfo().sourceDir));
            if (record == null) { result.put("status", "missing-key"); return; }
            grant = verify(record, "grant", issuer);
            require(grant.get("applicationId").equals(app) && grant.get("audience").equals(policy.getString("audience")), "grant-scope");
            fresh(grant, 86400);
            require(grant.get("keyId").matches("[A-Za-z0-9_-]{1,64}") && grant.get("key").matches("[A-Za-z0-9_-]{32,128}"), "grant-fields");
            result.put("keyId", grant.get("keyId"));
        }
        String scope = hash((app + "/" + contract + "/" + channel + "/" + hash(publisher)).getBytes(StandardCharsets.UTF_8));
        AtomicFile stateFile = new AtomicFile(new File(c.getFilesDir(), "signed-state-" + scope));
        JSONObject state = new JSONObject();
        if (stateFile.getBaseFile().exists() || new File(stateFile.getBaseFile() + ".bak").exists()) {
            try (InputStream in = stateFile.openRead()) {
                state = new JSONObject(new String(read(in, 16384), StandardCharsets.UTF_8));
            } // Corrupt local state fails closed; do not silently reset replay protection.
        }
        String base = "/probe/v1/apps/" + app + "/";
        Response response = request(endpoint, base + "head", grant, state.optString("etag", null), null, null, 4096);
        result.put("http", response.code);
        if (response.code != 200 && response.code != 304) {
            result.put("status", "http-error"); return;
        }
        byte[] envelope = response.body;
        if (response.code == 304) {
            require(state.has("envelope") && Objects.equals(response.etag, state.getString("etag")), "cache");
            envelope = decode(state.getString("envelope"));
        }
        // Revalidate signatures and expiry even on 304, including after process death.
        Map<String, String> head = verify(envelope, "head", publisher);
        require(head.get("applicationId").equals(app) && head.get("contract").equals(contract)
            && head.get("channel").equals(channel), "head-scope");
        fresh(head, 3600);
        long revision = number(head, "revision"), version = number(head, "payloadVersion"), size = number(head, "size");
        require(revision > 0 && version > 0 && size > 0 && size <= MAX, "bounds");
        require(head.get("sha256").matches("[0-9a-f]{64}") && head.get("releaseId").matches("[A-Za-z0-9_-]{1,64}"), "head-fields");
        String digest = hash(envelope);
        require(revision >= state.optLong("revision", 0) && version >= state.optLong("version", 0), "replay");
        if (revision == state.optLong("revision", 0)) require(digest.equals(state.getString("digest")), "equivocation");
        if (version == state.optLong("version", 0)) require(head.get("sha256").equals(state.getString("artifact"))
            && head.get("releaseId").equals(state.getString("release")), "version-reuse");
        require(response.etag != null && response.etag.equals("\"" + digest + "\""), "validator");
        // Persist the verified head before transfer: a failed download must not undo its high-water mark.
        JSONObject next = new JSONObject().put("revision", revision).put("version", version).put("digest", digest)
            .put("artifact", head.get("sha256")).put("release", head.get("releaseId"))
            .put("etag", response.etag).put("envelope", Base64.encodeToString(envelope, Base64.NO_WRAP));
        write(stateFile, next.toString().getBytes(StandardCharsets.UTF_8));
        String path = base + "releases/" + head.get("releaseId") + "/artifact";
        String validator = "\"" + head.get("sha256") + "\"";
        Response first = request(endpoint, path, grant, null, "bytes=0-7", validator, (int) size);
        byte[] artifact;
        if (first.code == 200) artifact = first.body; // Full response replaces the partial request.
        else {
            require(first.code == 206 && Objects.equals(first.etag, validator)
                && Objects.equals(first.range, "bytes 0-7/" + size) && first.body.length == 8, "range");
            Response rest = request(endpoint, path, grant, null, "bytes=8-", validator, (int) size);
            if (rest.code == 200) artifact = rest.body;
            else {
                require(rest.code == 206 && Objects.equals(rest.etag, validator)
                    && Objects.equals(rest.range, "bytes 8-" + (size - 1) + "/" + size), "range");
                ByteArrayOutputStream out = new ByteArrayOutputStream(); out.write(first.body); out.write(rest.body);
                artifact = out.toByteArray();
            }
        }
        require(artifact.length == size && hash(artifact).equals(head.get("sha256")), "artifact-integrity");
        if (policy.optString("artifactProfile").equals("stored-inventory-1")) {
            int count = ArchiveProbe.verify(artifact, head, asset(c, "release.der"));
            result.put("components", count);
        }
        // Atomic replacement of harmless data, NOT executable-payload selection or activation.
        write(new AtomicFile(new File(c.getFilesDir(), "signed-artifact.bin")), artifact);
        result.put("status", "verified"); result.put("revision", revision); result.put("sha256", hash(artifact));
    }
}
