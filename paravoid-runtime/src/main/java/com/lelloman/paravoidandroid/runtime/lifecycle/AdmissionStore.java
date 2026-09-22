package com.lelloman.paravoidandroid.runtime.lifecycle;

import com.lelloman.paravoidandroid.contract.ContractException;
import com.lelloman.paravoidandroid.contract.ContractException.Code;
import com.lelloman.paravoidandroid.contract.InstalledStateSource;
import com.lelloman.paravoidandroid.contract.Protocol.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Sole durable replay authority. Consumes authenticated shared objects, never parses signed input. */
final class AdmissionStore {
    interface Clock {
        long unixSeconds();
        long elapsedSeconds();
        String bootId();
    }
    private final Path root;
    private final ShellPolicy policy;
    private final Clock clock;
    private final AtomicRecord record;
    private final InstalledStateSource installed;

    AdmissionStore(Path root, ShellPolicy policy, Clock clock) {
        this(root, policy, clock, null);
    }
    AdmissionStore(Path root, ShellPolicy policy, Clock clock, InstalledStateSource installed) {
        this.root = root; this.policy = policy; this.clock = clock;
        this.installed = installed;
        this.record = new AtomicRecord(root.resolve("security"));
    }

    /** Only for a demonstrably new private store, never used as a read-error fallback. */
    void initializeNew() throws ContractException {
        try {
            Files.createDirectory(root); // Existing directory, even empty, fails closed.
            AtomicRecord.syncDirectory(root.getParent());
            State state = new State(); state.applicationId = policy.applicationId;
            record.write(encode(state));
        } catch (IOException e) { throw failure(Code.IO); }
    }

    static byte[] initialState(String applicationId) throws IOException {
        State state = new State(); state.applicationId = applicationId;
        return encode(state);
    }

    private interface Transaction<T> { T run(State state) throws ContractException, IOException; }
    private <T> T transaction(Transaction<T> operation) throws ContractException {
        return transaction(operation, false, null, false);
    }
    private <T> T transaction(Transaction<T> operation, boolean assertCredential, CredentialScope claimed, boolean useStoredCredential) throws ContractException {
        // Read/cryptographic work is outside selection. The short identity check below linearizes authorization.
        InstalledStateSource.Snapshot current = installed == null ? null : installed.read();
        if (installed != null && current == null) throw failure(Code.INCOMPATIBLE);
        if (current != null && (!policy.applicationId.equals(current.policy.applicationId)
                || !policy.shellContractId.equals(current.policy.shellContractId))) throw failure(Code.INCOMPATIBLE);
        final ContractException[] problem = new ContractException[1];
        try {
            T result = ProcessLocks.selection(root.resolve("selection.lock"), () -> {
                State state;
                try { state = decode(record.read()); }
                catch (IOException | RuntimeException damaged) { problem[0] = failure(Code.CORRUPT_STATE); return null; }
                if (!policy.applicationId.equals(state.applicationId)) {
                    problem[0] = failure(Code.CORRUPT_STATE); return null;
                }
                try {
                    if (current != null) {
                        if (!installed.isCurrent(current)) throw failure(Code.CREDENTIAL_CHANGED);
                        String live = current.credential == null ? null : current.credential.id;
                        if (assertCredential && (!Objects.equals(live, claimed == null ? null : claimed.id)
                                || (claimed != null && (claimed.authentication != current.credential.authentication
                                || claimed.issuedAt != current.credential.issuedAt || claimed.expiresAt != current.credential.expiresAt))))
                            throw failure(Code.CREDENTIAL_CHANGED);
                        if (useStoredCredential && !Objects.equals(live, state.credential)) throw failure(Code.CREDENTIAL_CHANGED);
                    }
                    return operation.run(state);
                }
                catch (ContractException e) { problem[0] = e; return null; }
            });
            if (problem[0] != null) throw problem[0];
            return result;
        } catch (IOException e) { throw failure(Code.IO); }
    }

    void setCredentialScope(CredentialScope credential) throws ContractException {
        if (credential != null && credential.authentication != policy.authentication) throw failure(Code.CREDENTIAL_UNAVAILABLE);
        transaction(state -> {
            String id = credential == null ? null : credential.id;
            if (!Objects.equals(id, state.credential) || !policy.shellContractId.equals(state.credentialContract)) {
                state.credential = id; state.credentialContract = policy.shellContractId;
                state.credentialEpoch = UUID.randomUUID().toString();
                state.admissions.clear();
                record.write(encode(state));
            }
            return null;
        }, true, credential, false);
    }

    AdmissionResult observeHead(VerifiedHead head, CredentialScope credential) throws ContractException {
        return transaction(state -> {
            checkCredential(state, credential);
            if (!head.scope.applicationId.equals(policy.applicationId)
                    || !head.scope.shellContractId.equals(policy.shellContractId)
                    || !head.scope.channel.equals(policy.channel) || head.scope.runtimeAbi != policy.runtimeAbi)
                throw failure(Code.INCOMPATIBLE);
            Time sample = effectiveTime(state);
            long now = sample.now;
            checkTimes(head.issuedAt, head.expiresAt, now, true);
            checkGrant(credential, now);
            String revisionScope = revisionScope(head.scope), request = requestScope(head.scope);
            Revision revision = state.revisions.get(revisionScope);
            long floor = Math.max(policy.trust.minimumHeadRevision, revision == null ? 0 : revision.value);
            if (head.headRevision < floor) throw failure(Code.REPLAY);
            if (revision == null || revision.value < head.headRevision) revision = new Revision(head.headRevision);
            String knownBody = revision.bodies.get(request);
            if (knownBody != null && !knownBody.equals(head.bodySha256)) throw failure(Code.IDENTITY_CONFLICT);
            if (head.release != null) rememberIdentity(state, head.release);
            revision.bodies.put(request, head.bodySha256);
            state.revisions.put(revisionScope, revision);
            state.authenticatedTime = Math.max(state.authenticatedTime, head.issuedAt);
            anchor(state, sample);
            // Superseded revisions cannot stage, even if their signed expiration has not passed.
            final long currentRevision = revision.value;
            state.admissions.values().removeIf(a -> a.revisionScope.equals(revisionScope) && a.revision < currentRevision);
            AdmissionId id = null;
            if (head.release != null) {
                // Identity-preserving retries of the same authenticated response return the same reference.
                for (Map.Entry<String, Admission> entry : state.admissions.entrySet()) {
                    Admission a = entry.getValue();
                    if (a.request.equals(request) && a.body.equals(head.bodySha256)
                            && a.epoch.equals(state.credentialEpoch)) { id = new AdmissionId(entry.getKey()); break; }
                }
                if (id == null) {
                    id = new AdmissionId(UUID.randomUUID().toString());
                    state.admissions.put(id.value, new Admission(revisionScope, request, head.bodySha256,
                        state.credentialEpoch, head.headRevision, head.issuedAt, head.expiresAt,
                        credential.issuedAt, credential.expiresAt, head.release));
                }
            }
            record.write(encode(state));
            return new AdmissionResult(head.status, id, head.release);
        }, true, credential, false);
    }

    /** Preliminary lookup only; preparation must be followed by authorizePublication. */
    ExpectedArchive resolve(AdmissionId id) throws ContractException {
        return authorizePublication(id, identity -> identity);
    }

    interface Publication<T> { T run(ExpectedArchive identity) throws IOException, ContractException; }

    /** Short publication callback under the same lock as credential/revision checks; no copy/verification. */
    <T> T authorizePublication(AdmissionId id, Publication<T> publication) throws ContractException {
        return transaction(state -> {
            if (!policy.updatesEnabled || state.credential == null) throw failure(Code.CREDENTIAL_UNAVAILABLE);
            Admission a = state.admissions.get(id.value);
            if (a == null || !a.epoch.equals(state.credentialEpoch)
                    || !policy.shellContractId.equals(state.credentialContract)) throw failure(Code.STALE_ADMISSION);
            Revision revision = state.revisions.get(a.revisionScope);
            if (revision == null || revision.value != a.revision || a.revision < policy.trust.minimumHeadRevision)
                throw failure(Code.STALE_ADMISSION);
            Time sample = effectiveTime(state);
            long now = sample.now;
            checkTimes(a.issued, a.expires, now, true);
            if (policy.authentication == Authentication.APK_KEY) checkTimes(a.grantIssued, a.grantExpires, now, false);
            rememberIdentity(state, a.release);
            anchor(state, sample);
            record.write(encode(state));
            return publication.run(a.release);
        }, false, null, true);
    }

    /** Embedded authorization still obeys lineage knowledge and never resets it. */
    void observeEmbedded(VerifiedRelease release) throws ContractException {
        authorizeEmbedded(release, identity -> null);
    }
    <T> T authorizeEmbedded(VerifiedRelease release, Publication<T> publication) throws ContractException {
        return transaction(state -> {
            if (!policy.applicationId.equals(release.applicationId) || !policy.shellContractId.equals(release.shellContractId))
                throw failure(Code.INCOMPATIBLE);
            rememberIdentity(state, release.identity);
            record.write(encode(state)); return publication.run(release.identity);
        });
    }

    private void checkCredential(State state, CredentialScope credential) throws ContractException {
        if (!policy.updatesEnabled || credential == null || state.credential == null
                || credential.authentication != policy.authentication) throw failure(Code.CREDENTIAL_UNAVAILABLE);
        if (!credential.id.equals(state.credential) || !policy.shellContractId.equals(state.credentialContract))
            throw failure(Code.CREDENTIAL_CHANGED);
    }
    private void checkGrant(CredentialScope credential, long now) throws ContractException {
        if (credential.authentication == Authentication.APK_KEY) checkTimes(credential.issuedAt, credential.expiresAt, now, false);
    }
    private static void checkTimes(long issued, long expires, long now, boolean head) throws ContractException {
        if (issued > now + 300) throw failure(Code.CLOCK_INVALID);
        if (head && (expires <= issued || expires - issued > 86400)) throw failure(Code.EXPIRED);
        if (expires != 0 && now >= expires) throw failure(Code.EXPIRED);
    }
    private static final class Time {
        final long now, elapsed; final String boot;
        Time(long now, long elapsed, String boot) { this.now = now; this.elapsed = elapsed; this.boot = boot; }
    }
    private Time effectiveTime(State state) throws ContractException {
        // Keep the earliest elapsed sample: disk work must not extend a cached head lifetime.
        String boot = clock.bootId(); long elapsed = clock.elapsedSeconds(), wall = clock.unixSeconds();
        if (wall < 0 || wall > com.lelloman.paravoidandroid.contract.Protocol.MAX_INTEGER || elapsed < 0 || wall < state.authenticatedTime - 300) throw failure(Code.CLOCK_INVALID);
        long effective = wall;
        if (boot.equals(state.boot)) {
            if (elapsed < state.elapsed) throw failure(Code.CLOCK_INVALID);
            try { effective = Math.max(effective, Math.addExact(state.effective, elapsed - state.elapsed)); }
            catch (ArithmeticException overflow) { throw failure(Code.CLOCK_INVALID); }
        }
        return new Time(effective, elapsed, boot);
    }
    private void anchor(State state, Time sample) {
        state.boot = sample.boot; state.elapsed = sample.elapsed; state.effective = sample.now;
    }
    private void rememberIdentity(State state, ExpectedArchive release) throws ContractException {
        ExpectedArchive version = state.identities.get(release.payloadVersion);
        if (version != null && !version.equals(release)) throw failure(Code.IDENTITY_CONFLICT);
        for (ExpectedArchive known : state.identities.values())
            if (known.releaseId.equals(release.releaseId) && !known.equals(release)) throw failure(Code.IDENTITY_CONFLICT);
        long floor = Math.max(policy.trust.minimumPayloadVersion, state.payloadFloor);
        if (release.payloadVersion < floor) throw failure(Code.REPLAY);
        state.payloadFloor = Math.max(state.payloadFloor, release.payloadVersion);
        state.identities.put(release.payloadVersion, release);
    }
    private static String revisionScope(RequestScope scope) {
        return hashStrings(scope.applicationId, scope.shellContractId, scope.channel);
    }
    private static String requestScope(RequestScope scope) {
        List<String> fields = new ArrayList<>(Arrays.asList(scope.applicationId, scope.shellContractId,
            scope.channel, Integer.toString(scope.sdk), Integer.toString(scope.runtimeAbi)));
        fields.addAll(scope.abis); return hashStrings(fields.toArray(new String[0]));
    }
    private static String hashStrings(String... fields) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
            for (String field : fields) out.writeUTF(field);
            return Base64.getEncoder().encodeToString(AtomicRecord.hash(bytes.toByteArray()));
        } catch (IOException impossible) { throw new IllegalArgumentException("Invalid internal scope"); }
    }
    private static ContractException failure(Code code) { return new ContractException(code, "Lifecycle admission: " + code.name()); }

    private static final class State {
        String applicationId, credential, credentialContract = "", credentialEpoch = "", boot = "";
        long authenticatedTime, payloadFloor, elapsed, effective;
        final Map<Long, ExpectedArchive> identities = new TreeMap<>();
        final Map<String, Revision> revisions = new TreeMap<>();
        final Map<String, Admission> admissions = new TreeMap<>();
    }
    private static final class Revision {
        final long value; final Map<String, String> bodies = new TreeMap<>();
        Revision(long value) { this.value = value; }
    }
    private static final class Admission {
        final String revisionScope, request, body, epoch;
        final long revision, issued, expires, grantIssued, grantExpires;
        final ExpectedArchive release;
        Admission(String scope, String request, String body, String epoch, long revision,
                long issued, long expires, long grantIssued, long grantExpires, ExpectedArchive release) {
            revisionScope = scope; this.request = request; this.body = body; this.epoch = epoch;
            this.revision = revision; this.issued = issued; this.expires = expires;
            this.grantIssued = grantIssued; this.grantExpires = grantExpires; this.release = release;
        }
    }
    private static byte[] encode(State s) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream o = new DataOutputStream(bytes);
        o.writeInt(1); o.writeUTF(s.applicationId); o.writeBoolean(s.credential != null);
        if (s.credential != null) o.writeUTF(s.credential);
        o.writeUTF(s.credentialContract); o.writeUTF(s.credentialEpoch); o.writeUTF(s.boot);
        o.writeLong(s.authenticatedTime); o.writeLong(s.payloadFloor); o.writeLong(s.elapsed); o.writeLong(s.effective);
        o.writeInt(s.identities.size()); for (ExpectedArchive identity : s.identities.values()) writeIdentity(o, identity);
        o.writeInt(s.revisions.size());
        for (Map.Entry<String, Revision> e : s.revisions.entrySet()) {
            o.writeUTF(e.getKey()); o.writeLong(e.getValue().value); o.writeInt(e.getValue().bodies.size());
            for (Map.Entry<String, String> body : e.getValue().bodies.entrySet()) { o.writeUTF(body.getKey()); o.writeUTF(body.getValue()); }
        }
        o.writeInt(s.admissions.size());
        for (Map.Entry<String, Admission> e : s.admissions.entrySet()) {
            o.writeUTF(e.getKey()); Admission a = e.getValue();
            o.writeUTF(a.revisionScope); o.writeUTF(a.request); o.writeUTF(a.body); o.writeUTF(a.epoch);
            o.writeLong(a.revision); o.writeLong(a.issued); o.writeLong(a.expires);
            o.writeLong(a.grantIssued); o.writeLong(a.grantExpires); writeIdentity(o, a.release);
        }
        if (bytes.size() > AtomicRecord.MAX_BYTES) throw new IOException("Security history capacity exhausted");
        return bytes.toByteArray();
    }
    private static State decode(byte[] bytes) throws IOException {
        DataInputStream i = new DataInputStream(new ByteArrayInputStream(bytes)); State s = new State();
        if (i.readInt() != 1) throw new IOException("Unknown admission state");
        s.applicationId = i.readUTF(); s.credential = i.readBoolean() ? i.readUTF() : null;
        s.credentialContract = i.readUTF(); s.credentialEpoch = i.readUTF(); s.boot = i.readUTF();
        s.authenticatedTime = number(i); s.payloadFloor = number(i); s.elapsed = number(i); s.effective = number(i);
        for (int n = count(i); n > 0; n--) {
            ExpectedArchive identity = readIdentity(i);
            if (s.identities.put(identity.payloadVersion, identity) != null) throw new IOException("Duplicate identity");
        }
        for (int n = count(i); n > 0; n--) {
            String key = i.readUTF(); Revision r = new Revision(number(i));
            for (int m = count(i); m > 0; m--) if (r.bodies.put(i.readUTF(), i.readUTF()) != null) throw new IOException("Duplicate body");
            if (s.revisions.put(key, r) != null) throw new IOException("Duplicate revision scope");
        }
        for (int n = count(i); n > 0; n--) {
            String key = i.readUTF(); Admission a = new Admission(i.readUTF(), i.readUTF(), i.readUTF(), i.readUTF(),
                number(i), number(i), number(i), number(i), number(i), readIdentity(i));
            if (s.admissions.put(key, a) != null) throw new IOException("Duplicate admission");
        }
        if (i.read() != -1) throw new IOException("Trailing admission state");
        return s;
    }
    private static int count(DataInputStream i) throws IOException {
        int count = i.readInt(); if (count < 0 || count > AtomicRecord.MAX_BYTES / 8) throw new IOException("Invalid count"); return count;
    }
    private static long number(DataInputStream i) throws IOException {
        long n = i.readLong(); if (n < 0) throw new IOException("Negative state number"); return n;
    }
    private static void writeIdentity(DataOutputStream o, ExpectedArchive a) throws IOException {
        o.writeUTF(a.releaseId); o.writeLong(a.payloadVersion); o.writeUTF(a.manifestSha256);
        o.writeUTF(a.archiveSha256); o.writeLong(a.archiveSize);
    }
    private static ExpectedArchive readIdentity(DataInputStream i) throws IOException {
        return new ExpectedArchive(i.readUTF(), number(i), i.readUTF(), i.readUTF(), number(i));
    }
}
