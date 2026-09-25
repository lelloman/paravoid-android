package com.lelloman.paravoidandroid.contract;

import java.io.File;
import java.security.PublicKey;
import java.util.*;

/** Shared immutable values. No Android, network, persistence or credential logging. */
public final class Protocol {
    private Protocol() {}
    public static final int PROTOCOL_VERSION = 1, FORMAT_VERSION = 1, RUNTIME_ABI = 1;
    public static final long MAX_INTEGER = 9007199254740991L;
    public static final long MAX_ARCHIVE_BYTES = 1024L * 1024 * 1024;
    public static final int MAX_RELEASE_BYTES = 1024 * 1024, MAX_HEAD_BYTES = 64 * 1024;
    public static final int MAX_GRANT_BYTES = 16 * 1024;
    public static final int GRANT_BLOCK_ID = 0x50564132; // Candidate, collision review still required.
    public static final Set<String> ABIS = Collections.unmodifiableSet(new LinkedHashSet<>(
        Arrays.asList("arm64-v8a", "armeabi-v7a", "x86", "x86_64")));

    static String required(String value) { return Objects.requireNonNull(value); }
    static <T> List<T> list(List<T> source) {
        ArrayList<T> result = new ArrayList<>(source);
        for (T value : result) Objects.requireNonNull(value);
        return Collections.unmodifiableList(result);
    }
    static <K,V> Map<K,V> map(Map<K,V> source) {
        LinkedHashMap<K,V> result = new LinkedHashMap<>(source);
        result.forEach((k,v) -> { Objects.requireNonNull(k); Objects.requireNonNull(v); });
        return Collections.unmodifiableMap(result);
    }

    public enum Authentication { PUBLIC, APK_KEY }
    public enum Bootstrap { EMBEDDED, EMPTY }
    public enum HeadStatus { AVAILABLE, NO_COMPATIBLE_RELEASE, SHELL_UPDATE_REQUIRED }

    /** Public keys only. Key/profile validation is also performed by the verifier. */
    public static final class TrustPolicy {
        public final String applicationId;
        public final Map<String, PublicKey> releaseKeys, headKeys, grantKeys;
        public final long minimumPayloadVersion, minimumHeadRevision;
        public TrustPolicy(String applicationId, Map<String, PublicKey> releaseKeys,
                Map<String, PublicKey> headKeys, Map<String, PublicKey> grantKeys,
                long minimumPayloadVersion, long minimumHeadRevision) {
            this.applicationId = required(applicationId);
            this.releaseKeys = map(releaseKeys); this.headKeys = map(headKeys); this.grantKeys = map(grantKeys);
            this.minimumPayloadVersion = minimumPayloadVersion; this.minimumHeadRevision = minimumHeadRevision;
        }
    }

    /** Installed policy, not values obtained from an update server. */
    public static final class ShellPolicy {
        public final String applicationId, shellContractId, baseUrl, channel;
        public final TrustPolicy trust;
        public final Authentication authentication;
        public final Bootstrap bootstrap;
        public final boolean updatesEnabled, debugHttpAllowed;
        public final int runtimeAbi;
        public final Map<String, String> resourceReservations;
        public final Map<String, String> updates;
        private final byte[] contractDescriptor;
        public ShellPolicy(String applicationId, String shellContractId, TrustPolicy trust,
                String baseUrl, String channel, Authentication authentication, Bootstrap bootstrap,
                boolean updatesEnabled, boolean debugHttpAllowed, int runtimeAbi,
                Map<String,String> resourceReservations, byte[] contractDescriptor) {
            this(applicationId, shellContractId, trust, baseUrl, channel, authentication, bootstrap,
                updatesEnabled, debugHttpAllowed, runtimeAbi, resourceReservations, contractDescriptor, Collections.emptyMap());
        }
        public ShellPolicy(String applicationId, String shellContractId, TrustPolicy trust,
                String baseUrl, String channel, Authentication authentication, Bootstrap bootstrap,
                boolean updatesEnabled, boolean debugHttpAllowed, int runtimeAbi,
                Map<String,String> resourceReservations, byte[] contractDescriptor, Map<String,String> updates) {
            this.updates = map(updates);
            this.applicationId = required(applicationId); this.shellContractId = required(shellContractId);
            this.trust = Objects.requireNonNull(trust); this.baseUrl = required(baseUrl);
            this.channel = required(channel); this.authentication = Objects.requireNonNull(authentication);
            this.bootstrap = Objects.requireNonNull(bootstrap); this.updatesEnabled = updatesEnabled;
            this.debugHttpAllowed = debugHttpAllowed; this.runtimeAbi = runtimeAbi;
            this.resourceReservations = map(resourceReservations); this.contractDescriptor = contractDescriptor.clone();
        }
        public byte[] contractDescriptor() { return contractDescriptor.clone(); }
    }

    /** Ordered ABIs must already be filtered to the current process bitness. */
    public static final class RequestScope {
        public final String applicationId, shellContractId, channel;
        public final int sdk, runtimeAbi;
        public final List<String> abis;
        public RequestScope(String applicationId, String shellContractId, String channel,
                int sdk, List<String> abis, int runtimeAbi) {
            this.applicationId = required(applicationId); this.shellContractId = required(shellContractId);
            this.channel = required(channel); this.sdk = sdk; this.abis = list(abis); this.runtimeAbi = runtimeAbi;
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof RequestScope)) return false;
            RequestScope b = (RequestScope) other;
            return applicationId.equals(b.applicationId) && shellContractId.equals(b.shellContractId)
                && channel.equals(b.channel) && sdk == b.sdk && runtimeAbi == b.runtimeAbi && abis.equals(b.abis);
        }
        @Override public int hashCode() { return Objects.hash(applicationId, shellContractId, channel, sdk, abis, runtimeAbi); }
    }

    public static final class ExpectedArchive {
        public final String releaseId, manifestSha256, archiveSha256;
        public final long payloadVersion, archiveSize;
        public ExpectedArchive(String releaseId, long payloadVersion, String manifestSha256,
                String archiveSha256, long archiveSize) {
            this.releaseId = required(releaseId); this.payloadVersion = payloadVersion;
            this.manifestSha256 = required(manifestSha256); this.archiveSha256 = required(archiveSha256);
            this.archiveSize = archiveSize;
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof ExpectedArchive)) return false;
            ExpectedArchive b = (ExpectedArchive) other;
            return releaseId.equals(b.releaseId) && payloadVersion == b.payloadVersion
                && manifestSha256.equals(b.manifestSha256) && archiveSha256.equals(b.archiveSha256)
                && archiveSize == b.archiveSize;
        }
        @Override public int hashCode() { return Objects.hash(releaseId, payloadVersion, manifestSha256, archiveSha256, archiveSize); }
    }

    /** Authenticated metadata only: temporal/replay admission belongs to Lifecycle. */
    public static final class VerifiedHead {
        public final RequestScope scope;
        public final long headRevision, issuedAt, expiresAt;
        public final HeadStatus status;
        public final ExpectedArchive release; // null exactly for non-available outcomes
        public final String signerKeyId, bodySha256, envelopeSha256;
        private final byte[] body, envelope;
        VerifiedHead(RequestScope scope, long revision, long issued, long expires, HeadStatus status,
                ExpectedArchive release, String signer, byte[] body, byte[] envelope) {
            this.scope = scope; headRevision = revision; issuedAt = issued; expiresAt = expires;
            this.status = status; this.release = release; signerKeyId = signer;
            this.body = body.clone(); this.envelope = envelope.clone();
            bodySha256 = Digests.sha256(body); envelopeSha256 = Digests.sha256(envelope);
        }
        public byte[] body() { return body.clone(); }
        public byte[] envelope() { return envelope.clone(); }
    }

    /** Never persist/log this object or the raw grant. Re-read from the installed APK. */
    public static final class VerifiedGrant {
        public final String applicationId, shellContractId, audience, grantId, keyId, signerKeyId;
        public final long issuedAt, expiresAt;
        public final String scopeId;
        private final String bearerKey;
        VerifiedGrant(String applicationId, String contract, String audience, String grantId,
                String keyId, String signer, String bearerKey, long issuedAt, long expiresAt, byte[] envelope) {
            this.applicationId = applicationId; shellContractId = contract; this.audience = audience;
            this.grantId = grantId; this.keyId = keyId; signerKeyId = signer; this.bearerKey = bearerKey;
            this.issuedAt = issuedAt; this.expiresAt = expiresAt; scopeId = Digests.sha256(envelope);
        }
        public String bearerKey() { return bearerKey; }
        @Override public String toString() { return "VerifiedGrant[redacted]"; }
    }

    /** Non-secret credential binding. Freshness is checked again by C at admission/staging. */
    public static final class CredentialScope {
        public final String id;
        public final long issuedAt, expiresAt;
        public final Authentication authentication;
        private CredentialScope(String id, long issuedAt, long expiresAt, Authentication authentication) {
            this.id = id; this.issuedAt = issuedAt; this.expiresAt = expiresAt; this.authentication = authentication;
        }
        public static CredentialScope publicAccess() { return new CredentialScope("public", 0, 0, Authentication.PUBLIC); }
        public static CredentialScope provisioned(VerifiedGrant grant) {
            return new CredentialScope(grant.scopeId, grant.issuedAt, grant.expiresAt, Authentication.APK_KEY);
        }
    }

    public static final class InventoryEntry {
        public final String path, sha256;
        public final long size;
        public InventoryEntry(String path, long size, String sha256) {
            this.path = required(path); this.size = size; this.sha256 = required(sha256);
        }
    }

    public static final class VerifiedRelease {
        public final String applicationId, shellContractId, signerKeyId, ledgerSha256;
        public final ExpectedArchive identity;
        public final int runtimeAbi, minSdk, maxSdk;
        public final List<String> abis;
        public final List<InventoryEntry> inventory;
        private final byte[] envelope;
        VerifiedRelease(String app, String contract, String signer, String ledger, ExpectedArchive identity,
                int runtimeAbi, int minSdk, int maxSdk, List<String> abis, List<InventoryEntry> inventory, byte[] envelope) {
            applicationId = app; shellContractId = contract; signerKeyId = signer; ledgerSha256 = ledger;
            this.identity = identity; this.runtimeAbi = runtimeAbi; this.minSdk = minSdk; this.maxSdk = maxSdk;
            this.abis = list(abis); this.inventory = list(inventory); this.envelope = envelope.clone();
        }
        public byte[] envelope() { return envelope.clone(); }
    }

    /** C-issued durable opaque reference; possession alone is not authorization. */
    public static final class AdmissionId {
        public final String value;
        public AdmissionId(String value) { this.value = required(value); }
    }
    public static final class AdmissionResult {
        public final HeadStatus status;
        public final AdmissionId admission; // null for non-available
        public final ExpectedArchive release; // null for non-available
        public AdmissionResult(HeadStatus status, AdmissionId admission, ExpectedArchive release) {
            this.status = Objects.requireNonNull(status); this.admission = admission; this.release = release;
            if ((status == HeadStatus.AVAILABLE) != (admission != null && release != null)
                    || (status != HeadStatus.AVAILABLE && (admission != null || release != null)))
                throw new IllegalArgumentException("Inconsistent admission result");
        }
    }
    public enum StageStatus { PENDING, ALREADY_PENDING, ALREADY_SELECTED }
    public static final class StageResult {
        public final StageStatus status;
        public final ExpectedArchive release;
        public StageResult(StageStatus status, ExpectedArchive release) {
            this.status = Objects.requireNonNull(status); this.release = Objects.requireNonNull(release);
        }
    }
    public enum Availability { EMPTY, RUNNABLE, TRIAL, RECOVERY }
    public static final class LifecycleSnapshot {
        public final Availability availability;
        public final ExpectedArchive active, pending, lastHealthy;
        public final boolean waitingForProcesses;
        public final int retainedPrevious;
        public final long storageBytes;
        public final ContractException.Code error; // nullable; never credential-bearing text
        public LifecycleSnapshot(Availability availability, ExpectedArchive active, ExpectedArchive pending,
                ExpectedArchive lastHealthy, boolean waitingForProcesses, int retainedPrevious,
                long storageBytes, ContractException.Code error) {
            this.availability = Objects.requireNonNull(availability); this.active = active; this.pending = pending;
            this.lastHealthy = lastHealthy; this.waitingForProcesses = waitingForProcesses;
            this.retainedPrevious = retainedPrevious; this.storageBytes = storageBytes; this.error = error;
        }
    }

    /** Paths valid only while the generation lease is held; no paths are download staging. */
    public static final class GenerationFiles {
        public final List<File> dexFiles;
        public final File resourcesApk, javaResourcesJar, nativeDirectory;
        public GenerationFiles(List<File> dexFiles, File resourcesApk, File javaResourcesJar, File nativeDirectory) {
            this.dexFiles = list(dexFiles); this.resourcesApk = Objects.requireNonNull(resourcesApk);
            this.javaResourcesJar = Objects.requireNonNull(javaResourcesJar); this.nativeDirectory = nativeDirectory;
        }
    }
}
