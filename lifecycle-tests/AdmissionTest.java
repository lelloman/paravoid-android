package com.lelloman.paravoidandroid.runtime.lifecycle;

import com.lelloman.paravoidandroid.contract.*;
import com.lelloman.paravoidandroid.contract.ContractException.Code;
import com.lelloman.paravoidandroid.contract.Protocol.*;
import java.nio.file.*;
import java.util.*;

public final class AdmissionTest {
    private static final class Clock implements AdmissionStore.Clock {
        long wall = 10000, elapsed = 100; String boot = "boot-1";
        public long unixSeconds() { return wall; }
        public long elapsedSeconds() { return elapsed; }
        public String bootId() { return boot; }
    }
    interface Action { void run() throws Exception; }
    static void fails(Code code, Action action) throws Exception {
        try { action.run(); throw new AssertionError("Expected " + code); }
        catch (ContractException e) { if (e.code != code) throw new AssertionError(e.code + " != " + code); }
    }
    static void check(boolean condition) { if (!condition) throw new AssertionError(); }
    static ShellPolicy policy(Authentication mode, String contract) {
        TrustPolicy trust = new TrustPolicy("app", Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(), 1, 1);
        return new ShellPolicy("app", contract, trust, "https://test/", "stable", mode,
            Bootstrap.EMPTY, true, false, 1, Collections.emptyMap(), new byte[0]);
    }
    static RequestScope scope(String contract, int sdk) {
        return new RequestScope("app", contract, "stable", sdk, Collections.singletonList("x86_64"), 1);
    }
    static ExpectedArchive release(long version) {
        return new ExpectedArchive("release-" + version, version, "a".repeat(64), "b".repeat(64), 3);
    }
    static VerifiedHead head(long revision, ExpectedArchive release, String body) {
        return TestEvidence.head(scope("contract", 30), revision, 9990, 10100, release, body);
    }
    public static void main(String[] args) throws Exception {
        Path parent = Files.createTempDirectory("admission-test-");
        Clock clock = new Clock(); ShellPolicy policy = policy(Authentication.PUBLIC, "contract");
        Path root = parent.resolve("security");
        AdmissionStore store = new AdmissionStore(root, policy, clock); store.initializeNew();
        fails(Code.IO, store::initializeNew);
        CredentialScope publicScope = CredentialScope.publicAccess();
        store.setCredentialScope(publicScope);
        AdmissionResult first = store.observeHead(head(1, release(1), "first"), publicScope);
        check(first.admission.value.equals(store.observeHead(head(1, release(1), "first"), publicScope).admission.value));
        check(new AdmissionStore(root, policy, clock).resolve(first.admission).equals(release(1)));
        fails(Code.IDENTITY_CONFLICT, () -> store.observeHead(head(1, release(1), "different body"), publicScope));
        store.observeHead(TestEvidence.head(scope("contract", 36), 1, 9990, 10100, release(1), "sdk36"), publicScope);
        fails(Code.EXPIRED, () -> store.observeHead(TestEvidence.head(scope("contract", 30), 900, 9990, 9999, release(900), "expired"), publicScope));
        // Rejected high numbers must not poison subsequent valid admission.
        AdmissionResult second = store.observeHead(head(2, release(2), "second"), publicScope);
        fails(Code.STALE_ADMISSION, () -> store.resolve(first.admission));
        fails(Code.REPLAY, () -> store.observeHead(head(1, release(3), "old revision"), publicScope));
        fails(Code.REPLAY, () -> store.observeHead(head(3, release(1), "lower payload"), publicScope));
        ExpectedArchive changed = new ExpectedArchive("release-2", 2, "c".repeat(64), "b".repeat(64), 3);
        fails(Code.IDENTITY_CONFLICT, () -> store.observeHead(head(3, changed, "changed bytes"), publicScope));
        ExpectedArchive reused = new ExpectedArchive("release-1", 3, "a".repeat(64), "b".repeat(64), 3);
        fails(Code.IDENTITY_CONFLICT, () -> store.observeHead(head(3, reused, "reused release"), publicScope));
        store.setCredentialScope(null);
        fails(Code.CREDENTIAL_UNAVAILABLE, () -> store.resolve(second.admission));
        store.setCredentialScope(publicScope);
        fails(Code.STALE_ADMISSION, () -> store.resolve(second.admission));
        fails(Code.REPLAY, () -> store.observeHead(head(1, release(1), "first"), publicScope));
        AdmissionResult third = store.observeHead(head(3, release(3), "third"), publicScope);
        clock.wall = 9700; clock.elapsed = 201;
        fails(Code.EXPIRED, () -> store.resolve(third.admission)); // wall remains before expiry, elapsed does not
        clock.wall = 9600;
        fails(Code.CLOCK_INVALID, () -> store.resolve(third.admission));
        clock.wall = 10000; clock.elapsed = 90;
        fails(Code.CLOCK_INVALID, () -> store.resolve(third.admission));
        clock.boot = "boot-2"; clock.elapsed = 1;
        check(store.resolve(third.admission).equals(release(3)));
        store.observeHead(head(4, null, "authenticated absence"), publicScope);
        fails(Code.STALE_ADMISSION, () -> store.resolve(third.admission));
        fails(Code.REPLAY, () -> store.observeHead(head(3, release(3), "third"), publicScope));
        System.out.println("PASS admission: restart, retries, revision/request scope, lineage identity, nonavailable heads, elapsed/wall time");

        AdmissionStore changedContract = new AdmissionStore(root, policy(Authentication.PUBLIC, "new-contract"), clock);
        changedContract.setCredentialScope(publicScope);
        fails(Code.REPLAY, () -> changedContract.observeHead(TestEvidence.head(scope("new-contract", 30), 1, 9990, 10100, release(2), "lower-new-contract"), publicScope));
        changedContract.observeHead(TestEvidence.head(scope("new-contract", 30), 1, 9990, 10100, release(4), "new-contract"), publicScope);
        byte[] valid = Files.readAllBytes(root.resolve("security")); valid[12] ^= 1;
        Files.write(root.resolve("security"), valid);
        fails(Code.CORRUPT_STATE, () -> changedContract.setCredentialScope(publicScope));
        fails(Code.IO, changedContract::initializeNew);
        Files.delete(root.resolve("security"));
        fails(Code.CORRUPT_STATE, () -> changedContract.setCredentialScope(publicScope));
        System.out.println("PASS admission: contract replacement preserves lineage, corrupt/missing established state never resets");

        AdmissionStore keyed = new AdmissionStore(parent.resolve("keyed"), policy(Authentication.APK_KEY, "contract"), clock);
        keyed.initializeNew();
        CredentialScope a = TestEvidence.credential("A", 9990, 10100), b = TestEvidence.credential("B", 9990, 10100);
        keyed.setCredentialScope(a);
        AdmissionResult aAdmission = keyed.observeHead(head(1, release(1), "first"), a);
        keyed.setCredentialScope(b);
        fails(Code.CREDENTIAL_CHANGED, () -> keyed.observeHead(head(2, release(2), "second"), a));
        fails(Code.STALE_ADMISSION, () -> keyed.resolve(aAdmission.admission));
        fails(Code.CREDENTIAL_UNAVAILABLE, () -> keyed.setCredentialScope(publicScope));
        AdmissionResult bAdmission = keyed.observeHead(head(2, release(2), "second"), b);
        clock.wall = 10100;
        fails(Code.EXPIRED, () -> keyed.resolve(bAdmission.admission));
        fails(Code.EXPIRED, () -> keyed.observeHead(TestEvidence.head(scope("contract", 30), 3, 10099, 11000, release(3), "fresh-head-expired-grant"), b));
        System.out.println("PASS admission: credential replacement races, no public fallback, grant expiry on admission/staging resolution");
        SignedMetadataVerifier verifier = new SignedMetadataVerifier();
        Path vectors = Paths.get("paravoid-contract/src/test/resources/metadata-vectors");
        TrustPolicy trust = verifier.readTrustPolicy(Files.readAllBytes(vectors.resolve("trust.json")));
        ShellPolicy signedPolicy = new ShellPolicy(trust.applicationId, "a".repeat(64), trust,
            "https://updates.example.test/", "stable", Authentication.APK_KEY, Bootstrap.EMBEDDED,
            true, false, 1, Collections.emptyMap(), new byte[0]);
        RequestScope signedScope = new RequestScope(trust.applicationId, signedPolicy.shellContractId,
            "stable", 30, Collections.singletonList("x86_64"), 1);
        CredentialScope signedCredential = CredentialScope.provisioned(verifier.verifyGrant(Files.readAllBytes(vectors.resolve("grant.json")), signedPolicy));
        VerifiedHead signedA = verifier.verifyHead(Files.readAllBytes(vectors.resolve("head-a.json")), signedPolicy, signedScope);
        VerifiedHead signedB = verifier.verifyHead(Files.readAllBytes(vectors.resolve("head-b.json")), signedPolicy, signedScope);
        clock.wall = 1800000000L; clock.elapsed = 500; clock.boot = "signed-boot";
        AdmissionStore signed = new AdmissionStore(parent.resolve("signed"), signedPolicy, clock);
        signed.initializeNew(); signed.setCredentialScope(signedCredential);
        AdmissionResult signedAdmission = signed.observeHead(signedA, signedCredential);
        check(signed.resolve(signedAdmission.admission).equals(signedA.release));
        signed.observeHead(signedB, signedCredential);
        fails(Code.REPLAY, () -> signed.observeHead(signedA, signedCredential));
        clock.wall = 1800003600L;
        fails(Code.EXPIRED, () -> signed.observeHead(signedB, signedCredential));
        System.out.println("PASS shared signed head/grant vectors through durable admission: A-to-B, replay rejection, expiration");
    }
}
