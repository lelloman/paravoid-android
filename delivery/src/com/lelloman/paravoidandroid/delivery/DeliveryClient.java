package com.lelloman.paravoidandroid.delivery;

import com.lelloman.paravoidandroid.contract.*;
import com.lelloman.paravoidandroid.contract.Protocol.*;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
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
        Session(CredentialScope credential, HttpTransport transport) { this.credential = credential; this.transport = transport; }
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
                    // Conservative reservation: B source + C private copy + maximum materialization + headroom.
                    long required = 2 * size + (2L << 30) + (64L << 20);
                    if (Files.getFileStore(directory).getUsableSpace() < required)
                        throw new ContractException(ContractException.Code.INSUFFICIENT_STORAGE, "Insufficient update storage");
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
        if (active != null) active.cancel();
        session = null; cache = null; authSuppressed = false;
        lifecycle.setCredentialScope(null); // Fail closed even if extraction/verification subsequently fails.
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
        // Isolated B-only directory: credential replacement removes all abandoned partials.
        // An active old worker owns its open file until its finally block; its scope cannot be reused.
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.part")) {
            for (Path file : files) Files.deleteIfExists(file);
        }
        lifecycle.setCredentialScope(credential);
        session = new Session(credential, transport);
    }

    /** Must run off the UI thread. explicitRetry lifts HTTP auth suppression, never grant checks. */
    public Result check(RequestScope scope, boolean download, boolean explicitRetry) throws ContractException, IOException {
        final Session captured;
        final HttpTransport.Cancellation cancel = new HttpTransport.Cancellation();
        final Cache cached;
        synchronized (this) {
            if (!policy.updatesEnabled) throw new ContractException(ContractException.Code.UNAVAILABLE, "Updates disabled by shell policy");
            if (active != null) throw new ContractException(ContractException.Code.UNAVAILABLE, "Update already in progress");
            if (explicitRetry) authSuppressed = false;
            if (session == null || authSuppressed)
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
        try {
            HttpTransport transport = captured.transport;
            URI headUri = transport.headUri(scope.applicationId, scope.shellContractId, scope.channel, scope.sdk, scope.abis, scope.runtimeAbi);
            HttpTransport.HeadBytes response = transport.head(headUri, cached == null ? null : "\"" + cached.head.envelopeSha256 + "\"", cancel);
            VerifiedHead head;
            if (response.notModified) {
                if (cached == null || !cached.fresh(clock)) throw new ContractException(ContractException.Code.EXPIRED, "Cached discovery expired");
                head = cached.head;
            } else head = verifier.verifyHead(response.body, policy, scope);
            current(captured, cancel);
            AdmissionResult admission = lifecycle.observeHead(head, captured.credential);
            synchronized (this) {
                current(captured, cancel);
                // Preserve original elapsed deadline on 304; never extend cache freshness.
                cache = response.notModified ? cached : new Cache(captured, scope, head, clock.unixSeconds(), clock.elapsedMillis());
            }
            if (!download || admission.status != HeadStatus.AVAILABLE) return new Result(admission.status, null);
            ExpectedArchive expected = admission.release;
            validCredential(captured.credential);
            URI archiveUri = transport.archiveUri(scope.applicationId, expected.releaseId);
            String partialScope = policy.shellContractId + ":" + captured.credential.id + ":" + expected.manifestSha256;
            partial = transport.partial(directory, partialScope, archiveUri, expected.archiveSize, expected.archiveSha256);
            storage.reserve(directory, expected.archiveSize);
            current(captured, cancel);
            transport.download(archiveUri, partial, expected.archiveSize, expected.archiveSha256, cancel);
            current(captured, cancel);
            handedOff = true;
            StageResult staged = lifecycle.stageDownloaded(partial.toFile(), admission.admission);
            return new Result(admission.status, staged);
        } catch (HttpTransport.Failure failure) {
            if (failure.status == 401 || failure.status == 403) synchronized (this) {
                if (session == captured) authSuppressed = true;
            }
            throw failure;
        } finally {
            synchronized (this) {
                try {
                    if (partial != null && (handedOff || session != captured)) Files.deleteIfExists(partial);
                } finally { if (active == cancel) active = null; }
            }
        }
    }

    public synchronized void cancelDownload() { if (active != null) active.cancel(); }
    private synchronized void current(Session captured, HttpTransport.Cancellation cancel) throws ContractException, IOException {
        if (session != captured) throw new ContractException(ContractException.Code.CREDENTIAL_CHANGED, "Installed credential changed");
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
