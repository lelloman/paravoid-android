package com.lelloman.paravoidandroid.runtime.lifecycle;

import com.lelloman.paravoidandroid.contract.*;
import com.lelloman.paravoidandroid.contract.Protocol.*;
import com.lelloman.paravoidandroid.contract.ContractException.Code;
import java.nio.file.*;
import static com.lelloman.paravoidandroid.runtime.lifecycle.AdmissionTest.*;

/** Test-only mutable authority; production reads the currently installed APK. */
public final class InstalledAuthorityTest {
    private static final class Authority implements InstalledStateSource {
        Snapshot snapshot;
        boolean replacedDuringCheck;
        public Snapshot read() { ProcessLocks.requireOutsideSelection(); return snapshot; }
        public boolean isCurrent(Snapshot expected) { ProcessLocks.requireSelection(); return !replacedDuringCheck && expected == snapshot; }
    }
    public static void main(String[] args) throws Exception {
        Path parent = Files.createTempDirectory("paravoid-authority-");
        ShellPolicy policy = policy(Authentication.APK_KEY, "contract");
        CredentialScope a = TestEvidence.credential("a", 9900, 11000), b = TestEvidence.credential("b", 9900, 11000);
        Authority authority = new Authority(); authority.snapshot = new InstalledStateSource.Snapshot("apk-a", policy, a);
        AdmissionStore store = new AdmissionStore(parent.resolve("state"), policy, new AdmissionStore.Clock() {
            public long unixSeconds() { return 10000; } public long elapsedSeconds() { return 100; } public String bootId() { return "boot"; }
        }, authority);
        store.initializeNew(); store.setCredentialScope(a);
        AdmissionResult accepted = store.observeHead(head(1, release(1), "a"), a);
        authority.snapshot = new InstalledStateSource.Snapshot("apk-b", policy, b);
        fails(Code.CREDENTIAL_CHANGED, () -> store.setCredentialScope(a)); // old process cannot reassert replaced grant
        fails(Code.CREDENTIAL_CHANGED, () -> store.setCredentialScope(null)); // stale failure cannot revoke current grant
        fails(Code.CREDENTIAL_CHANGED, () -> store.resolve(accepted.admission)); // recheck actual APK before publication
        store.setCredentialScope(b);
        fails(Code.STALE_ADMISSION, () -> store.resolve(accepted.admission));
        AdmissionResult next = store.observeHead(head(2, release(2), "b"), b);
        authority.replacedDuringCheck = true;
        fails(Code.CREDENTIAL_CHANGED, () -> store.resolve(next.admission));
        authority.replacedDuringCheck = false;
        check(store.resolve(next.admission).equals(release(2)));
        authority.snapshot = new InstalledStateSource.Snapshot("bad-grant", policy, null);
        store.setCredentialScope(null);
        fails(Code.CREDENTIAL_UNAVAILABLE, () -> store.resolve(next.admission));
        authority.snapshot = new InstalledStateSource.Snapshot("new-shell", policy(Authentication.APK_KEY, "new-contract"), b);
        fails(Code.INCOMPATIBLE, () -> store.setCredentialScope(b));
        // Full staging facade: APK authority changes after the owned archive copy
        // is verified, not merely between two preliminary admission lookups.
        LifecycleTest.Fixture one = new LifecycleTest.Fixture(parent, 1);
        LifecycleTest.Fixture two = new LifecycleTest.Fixture(parent, 2);
        Authority live = new Authority(); live.snapshot = new InstalledStateSource.Snapshot("apk-a", policy, a);
        boolean[] replace = {false};
        VpkVerifier verifier = new VpkVerifier() {
            private LifecycleTest.Fixture fixture(ExpectedArchive expected) { return expected.payloadVersion == 1 ? one : two; }
            public VerifiedRelease verifyDownloaded(java.io.File file, ShellPolicy p, RequestScope d, ExpectedArchive expected)
                    throws ContractException {
                VerifiedRelease result = fixture(expected).verifyDownloaded(file, p, d, expected);
                if (replace[0]) {
                    check(file.toPath().toString().contains("staging") && !file.equals(two.source));
                    live.snapshot = new InstalledStateSource.Snapshot("apk-b", policy, b);
                }
                return result;
            }
            public VerifiedRelease verifyEmbedded(java.io.File file, ShellPolicy p, RequestScope d) throws ContractException {
                return one.verifyEmbedded(file, p, d);
            }
            public VerifiedRelease verifyRetained(java.io.File file, ShellPolicy p, RequestScope d, ExpectedArchive expected)
                    throws ContractException { return fixture(expected).verifyRetained(file, p, d, expected); }
        };
        RuntimeLifecycle lifecycle = new RuntimeLifecycle(parent.resolve("facade").toFile(), policy, scope("contract", 30),
            verifier, LifecycleTest.CLOCK, true, live);
        lifecycle.initializeNew(); lifecycle.setCredentialScope(a);
        lifecycle.stageDownloaded(one.source, lifecycle.observeHead(head(1, one.release.identity, "one"), a).admission);
        GenerationLease lease = lifecycle.acquireForProcess(); lease.applicationCreated(); lease.firstFrameRendered();
        AdmissionId incoming = lifecycle.observeHead(head(2, two.release.identity, "two"), a).admission;
        replace[0] = true;
        try (DownloadReservation reserved = lifecycle.reserveDownload(incoming)) {
            fails(Code.CREDENTIAL_CHANGED, () -> reserved.stage(two.source));
        }
        check(lifecycle.snapshot().pending == null && lifecycle.snapshot().active.equals(one.release.identity));
        lease.beforeUserCode(); check(lease.files().resourcesApk.isFile());
        lifecycle.setCredentialScope(b);
        fails(Code.REPLAY, () -> lifecycle.observeHead(head(1, one.release.identity, "one"), b));
        replace[0] = false;
        try (DownloadReservation reserved = lifecycle.reserveDownload(
                lifecycle.observeHead(head(2, two.release.identity, "two"), b).admission)) {
            check(reserved.stage(two.source).status == StageStatus.PENDING);
        }
        check(lifecycle.snapshot().active.equals(one.release.identity));
        check(lifecycle.snapshot().pending.equals(two.release.identity));
        System.out.println("PASS current installed authority: stale grant/null assertions, publication race and shell replacement rejection");
        System.out.println("PASS authority replacement during owned staging rejects publication, preserves active lease/replay floors, permits fresh-grant retry");
    }
}
