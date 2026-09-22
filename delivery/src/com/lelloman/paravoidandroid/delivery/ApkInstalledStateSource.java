package com.lelloman.paravoidandroid.delivery;

import com.lelloman.paravoidandroid.contract.*;
import com.lelloman.paravoidandroid.contract.Protocol.*;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;

/** PackageManager-backed supplier in Android; file-only supplier in host integration tests. */
public final class ApkInstalledStateSource implements InstalledStateSource {
    public interface Location {
        /** Must query the currently installed package, not retain ApplicationInfo from process startup. */
        File currentBaseApk() throws IOException;
        boolean debuggable() throws IOException;
    }
    private final Location location;
    private final MetadataVerifier verifier;
    private final java.util.function.LongSupplier unixSeconds;
    public ApkInstalledStateSource(Location location, MetadataVerifier verifier) {
        this(location, verifier, () -> System.currentTimeMillis() / 1000);
    }
    public ApkInstalledStateSource(Location location, MetadataVerifier verifier, java.util.function.LongSupplier unixSeconds) {
        this.location = location; this.verifier = verifier; this.unixSeconds = unixSeconds;
    }
    @Override public Snapshot read() throws ContractException {
        try {
            File file = location.currentBaseApk(); String identity = identity(file);
            boolean debug = location.debuggable();
            ShellPolicy policy = ApkPolicyReader.read(file, debug);
            CredentialScope credential = null;
            if (policy.authentication == Authentication.PUBLIC) credential = CredentialScope.publicAccess();
            else try {
                VerifiedGrant grant = verifier.verifyGrant(ApkGrantReader.read(file), policy);
                long now = unixSeconds.getAsLong();
                if (grant.issuedAt <= now + 300 && (grant.expiresAt == 0 || now < grant.expiresAt)) credential = CredentialScope.provisioned(grant);
            } catch (ContractException | IOException unavailable) { /* A bad/missing grant denies updates, not accepted offline execution. */ }
            Snapshot snapshot = new Snapshot(identity + ":debug=" + debug, policy, credential);
            if (!isCurrent(snapshot)) throw new ContractException(ContractException.Code.CREDENTIAL_CHANGED, "Installed APK changed during read");
            return snapshot;
        } catch (IOException error) { throw new ContractException(ContractException.Code.IO, "Cannot read installed APK identity"); }
    }
    @Override public boolean isCurrent(Snapshot snapshot) throws ContractException {
        try { return snapshot.fileIdentity.equals(identity(location.currentBaseApk()) + ":debug=" + location.debuggable()); }
        catch (IOException error) { throw new ContractException(ContractException.Code.IO, "Cannot check installed APK identity"); }
    }
    private static String identity(File file) throws IOException {
        Path path = file.toPath().toAbsolutePath().normalize();
        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isRegularFile()) throw new IOException("Invalid installed APK");
        return path + ":" + attrs.fileKey() + ":" + attrs.size() + ":" + attrs.lastModifiedTime();
    }
}
