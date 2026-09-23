package com.lelloman.paravoidandroid.delivery;

import com.lelloman.paravoidandroid.contract.*;
import com.lelloman.paravoidandroid.contract.Protocol.*;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Blocking worker-thread delivery orchestration; never selects or executes a payload. */
public final class DeliveryClient {
    public interface Clock { long unixSeconds(); long elapsedMillis(); }
    interface Storage { void reserve(Path directory, long archiveSize) throws IOException, ContractException; }
    public static final class Result {
        public final HeadStatus status;
        public final StageResult stage; // null when checking only or no release was offered
        Result(HeadStatus status, StageResult stage) { this.status = status; this.stage = stage; }
    }
    private static final class Session {
        final CredentialScope credential;
        final HttpTransport transport;
        final String partition;
        Session(CredentialScope credential, HttpTransport transport, String partition) {
            this.credential = credential; this.transport = transport; this.partition = partition;
        }
    }
    private static final class Cache {
        final Session session;
        final RequestScope scope;
        final VerifiedHead head;
        final long observedWall, observedElapsed;
        Cache(Session session, RequestScope scope, VerifiedHead head, long wall, long elapsed) {
            this.session = session; this.scope = scope; this.head = head; observedWall = wall; observedElapsed = elapsed;
        }
        boolean fresh(Clock clock) {
            long wall = clock.unixSeconds(), elapsed = clock.elapsedMillis();
            return wall >= observedWall - 300 && wall < head.expiresAt && elapsed >= observedElapsed
                    && elapsed - observedElapsed < (head.expiresAt - observedWall) * 1000;
        }
    }
    private final ShellPolicy policy;
    private final MetadataVerifier verifier;
    private final Lifecycle lifecycle;
    private final Clock clock;
    private final Path directory;
    private final HttpTransport.Connections connections;
    private final Storage storage;
    private Session session;
    private Cache cache;
    private HttpTransport.Cancellation active;
    private boolean authSuppressed;

    public DeliveryClient(ShellPolicy policy, MetadataVerifier verifier, Lifecycle lifecycle, Clock clock, File noBackupDirectory) {
        this(policy, verifier, lifecycle, clock, noBackupDirectory.toPath(),
                url -> (java.net.HttpURLConnection) url.openConnection(), (directory, size) -> {
                    // RuntimeLifecycle owns admission/cleanup and the shared update-space claim.
                });
    }
    DeliveryClient(ShellPolicy policy, MetadataVerifier verifier, Lifecycle lifecycle, Clock clock, Path directory,
            HttpTransport.Connections connections, Storage storage) {
        this.policy = Objects.requireNonNull(policy); this.verifier = Objects.requireNonNull(verifier);
        this.lifecycle = Objects.requireNonNull(lifecycle); this.clock = Objects.requireNonNull(clock);
        this.directory = directory; this.connections = connections; this.storage = storage;
    }

    /** At process startup/APK replacement. Null is valid only for public mode. No asset fallback. */
    public synchronized void installedCredential(byte[] grantEnvelope) throws ContractException, IOException {
        invalidateCredential();
        try {
            CredentialScope credential;
            String bearer = null;
            if (policy.authentication == Authentication.PUBLIC) credential = CredentialScope.publicAccess();
            else {
                if (grantEnvelope == null) throw new ContractException(ContractException.Code.CREDENTIAL_UNAVAILABLE, "APK update credential missing");
                VerifiedGrant grant = verifier.verifyGrant(grantEnvelope, policy);
                credential = CredentialScope.provisioned(grant);
                validCredential(credential);
                bearer = grant.bearerKey();
            }
            HttpTransport transport = new HttpTransport(URI.create(policy.baseUrl), policy.debugHttpAllowed, bearer, connections);
            Files.createDirectories(directory);
            String partition = HttpTransport.hash((policy.shellContractId + ":" + credential.id).getBytes(StandardCharsets.UTF_8));
            // Isolated B-only directory: credential replacement removes all abandoned partials.
            // An active old worker owns its open file until its finally block; its scope cannot be reused.
            if (!partition.equals(readMarker("credential-scope"))) {
                writeMarker("credential-scope", partition);
                cleanupIdlePartials();
                Files.deleteIfExists(directory.resolve("auth-denied"));
            }
            lifecycle.setCredentialScope(credential);
            authSuppressed = partition.equals(readMarker("auth-denied"));
            session = new Session(credential, transport, partition);
        } catch (IOException | ContractException | RuntimeException failed) {
            denyCredential();
            throw failed;
        }
    }

    /** Caller obtains this path from Android ApplicationInfo.sourceDir, never a downloaded file. */
    public synchronized void installedApk(File installedBaseApk) throws ContractException, IOException {
        invalidateCredential();
        try {
            installedCredential(policy.authentication == Authentication.PUBLIC ? null : ApkGrantReader.read(installedBaseApk));
        } catch (IOException | ContractException | RuntimeException failed) {
            denyCredential();
            throw failed;
        }
    }

    private void denyCredential() throws ContractException, IOException {
        lifecycle.setCredentialScope(null);
        Files.createDirectories(directory);
        writeMarker("credential-scope", "0".repeat(64));
        cleanupIdlePartials();
        Files.deleteIfExists(directory.resolve("auth-denied"));
    }

    // Never unlink the file synchronously borrowed by stageDownloaded, including
    // when another process refreshes its installed grant. Its owner cleans up on return.
    private void cleanupIdlePartials() throws IOException {
        try (DeliveryLocks.Claim lock = DeliveryLocks.tryAcquire(directory.resolve("transfer.lock"))) {
            if (lock == null) return;
            try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.part")) {
                for (Path file : files) Files.deleteIfExists(file);
            }
        }
    }

    private void invalidateCredential() {
        if (active != null) active.cancel();
        session = null; cache = null; authSuppressed = false;
        // Do not transiently revoke the same valid scope in other processes. Publish
        // the verified replacement (or null on failure) to C exactly when known.
    }

    /** Must run off the UI thread. explicitRetry lifts HTTP auth suppression, never grant checks. */
    public Result check(RequestScope scope, boolean download, boolean explicitRetry) throws ContractException, IOException {
        return check(scope, download, explicitRetry, () -> {});
    }
    @FunctionalInterface interface Checkpoint { void check() throws IOException; }
    @FunctionalInterface interface DownloadPermission { boolean allowed() throws IOException; }
    private volatile boolean downloading;
    boolean isDownloading() { return downloading; }
    Result check(RequestScope scope, boolean download, boolean explicitRetry, Checkpoint checkpoint) throws ContractException, IOException {
        return check(scope, download, explicitRetry, checkpoint, () -> true);
    }
    Result check(RequestScope scope, boolean download, boolean explicitRetry, Checkpoint checkpoint,
            DownloadPermission permission) throws ContractException, IOException {
        final Session captured;
        final HttpTransport.Cancellation cancel = new HttpTransport.Cancellation();
        final Cache cached;
        synchronized (this) {
            if (!policy.updatesEnabled) throw new ContractException(ContractException.Code.UNAVAILABLE, "Updates disabled by shell policy");
            if (active != null) throw new ContractException(ContractException.Code.UNAVAILABLE, "Update already in progress");
            authSuppressed = session != null && session.partition.equals(readMarker("auth-denied"));
            if (session == null || (authSuppressed && !explicitRetry))
                throw new ContractException(ContractException.Code.CREDENTIAL_UNAVAILABLE, "Update access unavailable");
            if (!scope.applicationId.equals(policy.applicationId) || !scope.shellContractId.equals(policy.shellContractId)
                    || !scope.channel.equals(policy.channel) || scope.runtimeAbi != policy.runtimeAbi)
                throw new ContractException(ContractException.Code.INCOMPATIBLE, "Request differs from installed policy");
            captured = session; validCredential(captured.credential);
            cached = cache != null && cache.session == captured && cache.scope.equals(scope) && cache.fresh(clock) ? cache : null;
            active = cancel;
        }
        Path partial = null;
        boolean handedOff = false;
        DeliveryLocks.Claim lock = null;
        try {
            lock = DeliveryLocks.tryAcquire(directory.resolve("transfer.lock"));
            if (lock == null) throw new ContractException(ContractException.Code.UNAVAILABLE, "Another process is checking updates");
            current(captured, cancel); checkpoint.check();
            // Only the current transfer owner may lift suppression. A stale/busy
            // controller cannot clear another process's authentication failure.
            if (explicitRetry) {
                Files.deleteIfExists(directory.resolve("auth-denied"));
                synchronized (this) { authSuppressed = false; }
            }
            if (!explicitRetry && captured.partition.equals(readMarker("auth-denied")))
                throw new ContractException(ContractException.Code.CREDENTIAL_UNAVAILABLE, "Update access unavailable");
            HttpTransport transport = captured.transport;
            URI headUri = transport.headUri(scope.applicationId, scope.shellContractId, scope.channel, scope.sdk, scope.abis, scope.runtimeAbi);
            HttpTransport.HeadBytes response = transport.head(headUri, cached == null ? null : "\"" + cached.head.envelopeSha256 + "\"", cancel);
            VerifiedHead head;
            if (response.notModified) {
                if (cached == null || !cached.fresh(clock)) throw new ContractException(ContractException.Code.EXPIRED, "Cached discovery expired");
                head = cached.head;
            } else head = verifier.verifyHead(response.body, policy, scope);
            current(captured, cancel); checkpoint.check();
            AdmissionResult admission = lifecycle.observeHead(head, captured.credential);
            synchronized (this) {
                current(captured, cancel);
                // Preserve original elapsed deadline on 304; never extend cache freshness.
                cache = response.notModified ? cached : new Cache(captured, scope, head, clock.unixSeconds(), clock.elapsedMillis());
            }
            // Controller cancellation takes its operation lock before the client
            // monitor. Never invoke its checkpoint while holding this monitor.
            checkpoint.check();
            if (!download || admission.status != HeadStatus.AVAILABLE || !permission.allowed())
                return new Result(admission.status, null);
            ExpectedArchive expected = admission.release;
            validCredential(captured.credential);
            URI archiveUri = transport.archiveUri(scope.applicationId, expected.releaseId);
            String partialScope = policy.shellContractId + ":" + captured.credential.id + ":" + expected.manifestSha256;
            partial = transport.partial(directory, partialScope, archiveUri, expected.archiveSize, expected.archiveSha256);
            // At most one resumable candidate. This directory never contains C-owned archives.
            try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.part")) {
                for (Path file : files) if (!file.equals(partial)) Files.deleteIfExists(file);
            }
            try (DownloadReservation reservation = lifecycle.reserveDownload(admission.admission)) {
                storage.reserve(directory, expected.archiveSize); // Additional host-test fault seam only.
                downloading = true;
                current(captured, cancel); checkpoint.check();
                transport.download(archiveUri, partial, expected.archiveSize, expected.archiveSha256, cancel);
                current(captured, cancel); checkpoint.check();
                downloading = false; // Successful checkpoint is the non-cancellable staging boundary.
                handedOff = true;
                StageResult staged = reservation.stage(partial.toFile());
                return new Result(admission.status, staged);
            }
        } catch (HttpTransport.Failure failure) {
            if (failure.status == 401 || failure.status == 403) synchronized (this) {
                if (session == captured && captured.partition.equals(readMarker("credential-scope"))) {
                    authSuppressed = true;
                    writeMarker("auth-denied", captured.partition);
                }
            }
            throw failure;
        } finally {
            downloading = false;
            synchronized (this) {
                try {
                    if (partial != null && (handedOff || session != captured
                            || !captured.partition.equals(readMarker("credential-scope")))) Files.deleteIfExists(partial);
                } finally {
                    try { if (lock != null) lock.close(); }
                    finally { if (active == cancel) active = null; }
                }
            }
        }
    }

    /** Opaque cache partition for scheduling only; never a bearer credential. */
    synchronized String credentialPartition() { return session == null ? null : session.partition; }

    public synchronized void cancelDownload() { if (active != null) active.cancel(); }
    private String readMarker(String name) throws IOException {
        Path path = directory.resolve(name);
        if (!Files.exists(path)) return null;
        try (InputStream input = Files.newInputStream(path)) {
            byte[] bytes = new byte[65]; int total = 0, count;
            while (total < bytes.length && (count = input.read(bytes, total, bytes.length - total)) != -1) total += count;
            if (total != 64) return null;
            String value = new String(bytes, 0, total, StandardCharsets.US_ASCII);
            return value.matches("[0-9a-f]{64}") ? value : null;
        }
    }
    private void writeMarker(String name, String value) throws IOException {
        Path temporary = Files.createTempFile(directory, name, ".tmp");
        try {
            try (FileOutputStream output = new FileOutputStream(temporary.toFile())) {
                output.write(value.getBytes(StandardCharsets.US_ASCII)); output.getFD().sync();
            }
            Files.move(temporary, directory.resolve(name), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    private synchronized void current(Session captured, HttpTransport.Cancellation cancel) throws ContractException, IOException {
        if (session != captured || !captured.partition.equals(readMarker("credential-scope"))) throw new ContractException(ContractException.Code.CREDENTIAL_CHANGED, "Installed credential changed");
        cancel.check();
    }
    private void validCredential(CredentialScope credential) throws ContractException {
        if (credential.authentication == Authentication.PUBLIC) return;
        long now = clock.unixSeconds();
        if (credential.issuedAt > now + 300)
            throw new ContractException(ContractException.Code.CLOCK_INVALID, "Check device time");
        if (credential.expiresAt != 0 && now >= credential.expiresAt)
            throw new ContractException(ContractException.Code.CREDENTIAL_UNAVAILABLE, "APK update credential expired");
    }
}
