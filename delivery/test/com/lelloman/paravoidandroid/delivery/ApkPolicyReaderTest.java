package com.lelloman.paravoidandroid.delivery;

import com.lelloman.paravoidandroid.contract.*;
import com.lelloman.paravoidandroid.contract.Protocol.*;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.*;
import static com.lelloman.paravoidandroid.delivery.TransportTest.check;

public final class ApkPolicyReaderTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("paravoid-policy-test-");
        File a = apk(root.resolve("a.apk"), InstalledPolicyCodec.APK_PATH, PolicyFixtures.policy(Authentication.PUBLIC, "https://updates.example.test/"));
        File b = apk(root.resolve("b.apk"), InstalledPolicyCodec.APK_PATH, PolicyFixtures.policy(Authentication.PUBLIC, "https://other.example/"));
        AtomicReference<File> current = new AtomicReference<>(a);
        ApkInstalledStateSource source = new ApkInstalledStateSource(new ApkInstalledStateSource.Location() {
            public File currentBaseApk() { return current.get(); } public boolean debuggable() { return false; }
        }, new SignedMetadataVerifier());
        InstalledStateSource.Snapshot first = source.read(); check(source.isCurrent(first)); check(first.credential.authentication == Authentication.PUBLIC);
        current.set(b); check(!source.isCurrent(first)); check(!first.policy.shellContractId.equals(source.read().policy.shellContractId));
        current.set(apk(root.resolve("key.apk"), InstalledPolicyCodec.APK_PATH, PolicyFixtures.policy(Authentication.APK_KEY, "https://updates.example.test/")));
        check(source.read().credential == null); // Missing grant denies updates but policy/offline payloads remain readable.
        File missing = apk(root.resolve("missing.apk"), "assets/other", new byte[]{1});
        reject(() -> ApkPolicyReader.read(missing, false));
        File large = apk(root.resolve("large.apk"), InstalledPolicyCodec.APK_PATH, new byte[InstalledPolicyCodec.MAX_BYTES + 1]);
        reject(() -> ApkPolicyReader.read(large, false));
        System.out.println("ApkPolicyReaderTest: " + TransportTest.assertions + " assertions passed");
    }
    private static File apk(Path path, String name, byte[] bytes) throws Exception {
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(path))) { out.putNextEntry(new ZipEntry(name)); out.write(bytes); out.closeEntry(); }
        return path.toFile();
    }
    interface Action { void run() throws Exception; }
    private static void reject(Action action) throws Exception {
        try { action.run(); throw new AssertionError("Accepted invalid policy"); }
        catch (ContractException expected) { check(true); }
    }
}
