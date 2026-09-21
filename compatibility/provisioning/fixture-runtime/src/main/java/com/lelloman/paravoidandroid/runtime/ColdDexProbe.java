package com.lelloman.paravoidandroid.runtime;

import android.content.Context;
import android.os.Build;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import org.json.JSONObject;
import static com.lelloman.paravoidandroid.runtime.SignedDeliveryProbe.*;

/** Single-process cold-start experiment. No download or code selection is performed by an Activity. */
public final class ColdDexProbe {
    private static final String ENTRY = "com.lelloman.paravoidremote.Entry";
    private static final String TOKEN = UUID.randomUUID().toString();
    private static boolean started;
    private static String status = "disabled", value = "none", failure = "", activeVersion = "none";
    private static boolean owned;
    private static Class<?> loadedEntry;
    private static int calls;

    private static JSONObject policy(Context c) throws Exception {
        return new JSONObject(new String(asset(c, "policy.json"), StandardCharsets.UTF_8));
    }
    private static AtomicFile file(Context c, String name) throws Exception {
        // Separate independent fixture runs; not a production trust-rotation/lineage policy.
        String scope = policy(c).getString("contract");
        require(scope.matches("[0-9a-f]{64}"), "cold-scope");
        File directory = new File(c.getFilesDir(), "cold-" + scope);
        require(directory.isDirectory() || directory.mkdir(), "cold-storage");
        return new AtomicFile(new File(directory, name));
    }
    private static boolean exists(AtomicFile f) {
        return f.getBaseFile().exists() || new File(f.getBaseFile() + ".bak").exists();
    }
    private static byte[] load(AtomicFile f) throws Exception {
        try (InputStream in = f.openRead()) { return read(in, 1024 * 1024 + 8192); }
    }
    private static byte[] bundle(byte[] head, byte[] archive) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(head.length); out.write(head); out.write(archive); out.flush();
        return bytes.toByteArray();
    }
    private static final class Candidate {
        final Map<String, String> head;
        final byte[] dex;
        Candidate(Map<String, String> head, byte[] dex) { this.head = head; this.dex = dex; }
    }
    private static Candidate verifyBundle(Context c, byte[] bytes) throws Exception {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        int length = in.readInt();
        require(length > 0 && length <= 4096 && length <= bytes.length - 4, "cold-bundle");
        byte[] envelope = new byte[length]; in.readFully(envelope);
        byte[] archive = read(in, 1024 * 1024);
        Map<String, String> head = verify(envelope, "head", asset(c, "publisher.der"));
        JSONObject p = policy(c);
        require(head.get("applicationId").equals(c.getPackageName()) && head.get("contract").equals(p.getString("contract"))
            && head.get("channel").equals(p.getString("channel")), "cold-scope");
        require(head.get("size").equals(Integer.toString(archive.length)) && hash(archive).equals(head.get("sha256")), "cold-integrity");
        Map<String, byte[]> files = ArchiveProbe.verifiedFiles(archive, head, asset(c, "release.der"));
        require(files.size() == 3 && files.containsKey("code/classes.dex"), "cold-components");
        // Freshness is enforced during online selection. Retained verified code may run offline after expiry.
        return new Candidate(head, files.get("code/classes.dex"));
    }
    public static synchronized void stage(Context c, byte[] head, byte[] archive) throws Exception {
        byte[] bytes = bundle(head, archive);
        Candidate next = verifyBundle(c, bytes); // Verify before touching pending or active state.
        AtomicFile active = file(c, "active.bin");
        if (exists(active)) {
            Candidate current = verifyBundle(c, load(active));
            long version = Long.parseLong(next.head.get("payloadVersion"));
            long old = Long.parseLong(current.head.get("payloadVersion"));
            require(version >= old, "cold-downgrade");
            if (version == old) {
                require(next.head.get("sha256").equals(current.head.get("sha256")), "cold-version-reuse");
                return; // Polling the active version must not discard a newer pending candidate.
            }
        }
        write(file(c, "pending.bin"), bytes);
    }
    public static synchronized void start(Context c) {
        if (started) return;
        started = true;
        try {
            if (!SignedDeliveryProbe.configured(c) || !policy(c).optBoolean("coldDex")) return;
            require(policy(c).getString("artifactProfile").equals("stored-inventory-1"), "cold-profile");
            AtomicFile pending = file(c, "pending.bin"), active = file(c, "active.bin");
            boolean promote = exists(pending);
            if (!promote && !exists(active)) { status = "empty"; return; }
            byte[] bytes = load(promote ? pending : active);
            Candidate candidate = verifyBundle(c, bytes);
            if (promote && exists(active)) {
                Candidate previous = verifyBundle(c, load(active));
                long version = Long.parseLong(candidate.head.get("payloadVersion"));
                long old = Long.parseLong(previous.head.get("payloadVersion"));
                require(version >= old, "cold-downgrade");
                if (version == old) require(candidate.head.get("sha256").equals(previous.head.get("sha256")), "cold-version-reuse");
            }
            // Reuse Paravoid's real bounded ModuleBundle + in-memory DEX loader path.
            ByteArrayOutputStream module = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(module)) {
                zip.putNextEntry(new ZipEntry("module.properties"));
                zip.write(("format=1\napi=1\nminSdk=30\nentryPoint=" + ENTRY + "\n").getBytes(StandardCharsets.US_ASCII));
                zip.closeEntry(); zip.putNextEntry(new ZipEntry("classes.dex"));
                zip.write(candidate.dex); zip.closeEntry();
            }
            ModuleBundle parsed = ModuleBundle.read(new ByteArrayInputStream(module.toByteArray()), Build.VERSION.SDK_INT);
            ClassLoader loader = parsed.createClassLoader(ColdDexProbe.class.getClassLoader(), null);
            Class<?> entry = loader.loadClass(parsed.entryPoint);
            require(entry.getClassLoader() == loader, "cold-parent-leak");
            String nextValue = (String) entry.getMethod("value").invoke(null);
            calls++;
            if (promote) { write(active, bytes); pending.delete(); }
            loadedEntry = entry;
            value = nextValue; owned = true; activeVersion = candidate.head.get("payloadVersion"); status = "loaded";
        } catch (Exception | LinkageError error) {
            // Do not execute old code after a candidate ran: its persistent-data effects may be incompatible.
            status = "recovery";
            failure = error instanceof SignedDeliveryProbe.Rejected
                ? ((SignedDeliveryProbe.Rejected) error).reason : error.getClass().getSimpleName();
        }
    }
    public static synchronized void describe(JSONObject result) throws Exception {
        if (loadedEntry != null) {
            // Exercise the retained implementation, not merely a cached startup label.
            value = (String) loadedEntry.getMethod("value").invoke(null);
            calls++;
        }
        result.put("coldStatus", status).put("coldValue", value).put("coldVersion", activeVersion)
            .put("coldProcess", TOKEN).put("coldOwnedLoader", owned).put("coldFailure", failure).put("coldCalls", calls);
    }
}
