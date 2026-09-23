package com.lelloman.paravoidandroid.delivery;

import com.lelloman.paravoidandroid.contract.*;
import com.lelloman.paravoidandroid.contract.Protocol.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static com.lelloman.paravoidandroid.delivery.TransportTest.*;

public final class DeliveryClientTest {
    static final class Clock implements DeliveryClient.Clock {
        long wall = 1000, elapsed = 0;
        public long unixSeconds() { return wall; }
        public long elapsedMillis() { return elapsed; }
    }
    static final class Life implements Lifecycle {
        CredentialScope credential;
        int observations, stages, credentialDenials;
        int reservations, releasedReservations;
        boolean reserved;
        Clock clock;
        VerifiedHead last;
        Action duringStage;
        public void setCredentialScope(CredentialScope scope) { credential = scope; if (scope == null) credentialDenials++; }
        public AdmissionResult observeHead(VerifiedHead head, CredentialScope scope) throws ContractException {
            if (credential == null || !scope.id.equals(credential.id)) throw new ContractException(ContractException.Code.CREDENTIAL_CHANGED, "changed");
            if (clock.wall >= head.expiresAt) throw new ContractException(ContractException.Code.EXPIRED, "expired");
            observations++; last = head;
            return new AdmissionResult(head.status, head.release == null ? null : new AdmissionId("test-admission"), head.release);
        }
        public StageResult stageDownloaded(File archive, AdmissionId admission) throws ContractException {
            check(reserved);
            try {
                if (duringStage != null) duringStage.run();
                check(Arrays.equals(ARCHIVE, Files.readAllBytes(archive.toPath())));
            } catch (Exception e) { throw new AssertionError(e); }
            check(admission.value.equals("test-admission")); stages++;
            return new StageResult(StageStatus.PENDING, last.release);
        }
        public DownloadReservation reserveDownload(AdmissionId admission) {
            check(!reserved); reserved = true; reservations++;
            return new DownloadReservation() {
                public StageResult stage(File archive) throws ContractException { return stageDownloaded(archive, admission); }
                public void close() { check(reserved); reserved = false; releasedReservations++; }
            };
        }
        public StageResult stageEmbedded(File archive) { throw new AssertionError("delivery must not stage embedded"); }
        public LifecycleSnapshot snapshot() {
            return new LifecycleSnapshot(Availability.RUNNABLE, null, stages == 0 ? null : last.release,
                    null, stages > 0, 1, 0, null);
        }
        public GenerationLease acquireForProcess() { throw new AssertionError("delivery must never select or execute"); }
        public void retryQuarantined(ExpectedArchive release) { throw new AssertionError("not a download action"); }
        public void setRetainedPrevious(int count) { throw new AssertionError("not used"); }
    }
    static final class Setup implements AutoCloseable {
        final Fixture f = new Fixture();
        final FakeMetadata metadata = new FakeMetadata();
        final Clock clock = new Clock();
        final Life life = new Life();
        final DeliveryClient client;
        ShellPolicy policy;
        final RequestScope scope = new RequestScope("example.app", "a".repeat(64), "stable", 30, Arrays.asList("x86_64"), 1);
        Setup(boolean apkKey) throws Exception { this(apkKey, false); }
        Setup(boolean apkKey, boolean insufficientStorage) throws Exception {
            metadata.archive = new ExpectedArchive("r1", 1, "b".repeat(64), HASH, ARCHIVE.length);
            life.clock = clock;
            policy = new ShellPolicy("example.app", "a".repeat(64),
                    new TrustPolicy("example.app", Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(), 1, 1),
                    BASE.toString(), "stable", apkKey ? Authentication.APK_KEY : Authentication.PUBLIC,
                    Bootstrap.EMBEDDED, true, false, 1, Collections.emptyMap(), new byte[0]);
            client = new DeliveryClient(policy, metadata, life, clock, f.dir, url -> {
                f.requests++; Fake next = f.responses.poll();
                if (next == null) throw new AssertionError("unexpected HTTP"); return next;
            }, (directory, size) -> {
                check(size == ARCHIVE.length);
                if (insufficientStorage) throw new ContractException(ContractException.Code.INSUFFICIENT_STORAGE, "Insufficient update storage");
            });
            client.installedCredential(apkKey ? new byte[] {1} : null);
        }
        Fake head() throws Exception {
            Fake fake = new Fake(200, new byte[] {1}).put("Content-Type", "application/json")
                    .put("ETag", "\"" + HttpTransport.hash(new byte[] {1}) + "\"");
            f.responses.add(fake); return fake;
        }
        DeliveryClient peer() throws Exception {
            DeliveryClient peer = new DeliveryClient(policy, metadata, life, clock, f.dir, url -> {
                f.requests++; Fake next = f.responses.poll();
                if (next == null) throw new AssertionError("unexpected peer HTTP"); return next;
            }, (directory, size) -> {});
            peer.installedCredential(new byte[] {1});
            return peer;
        }
        Fake cached() throws Exception {
            Fake fake = new Fake(304, new byte[0]).put("ETag", "\"" + HttpTransport.hash(new byte[] {1}) + "\"");
            f.responses.add(fake); return fake;
        }
        @Override public void close() throws IOException { f.close(); }
    }
    static void contractFailure(ContractException.Code code, Action action) throws Exception {
        try { action.run(); throw new AssertionError("expected " + code); }
        catch (ContractException failure) { check(failure.code == code); }
    }
    public static void main(String[] args) throws Exception {
        try (Setup s = new Setup(true)) {
            s.metadata.grantExpiresAt = 0;
            s.client.installedCredential(new byte[] {1});
            DeliveryClient peer = s.peer();
            s.f.responses.add(new Fake(403, new byte[0]));
            fails("http-status", () -> s.client.check(s.scope, false, false));
            s.clock.wall += 7 * 60 * 60; s.metadata.expiresAt = s.clock.wall + 100;
            contractFailure(ContractException.Code.CREDENTIAL_UNAVAILABLE, () -> peer.check(s.scope, false, false));
            check(s.f.requests == 1); // Shared denial, independent of six-hour controller throttle.
            s.head(); peer.check(s.scope, false, true);
            check(s.f.requests == 2); // Credential is still valid; denial was not grant expiry.
        }
        try (Setup s = new Setup(true)) {
            DeliveryClient peer = s.peer();
            s.f.responses.add(new Fake(403, new byte[0]));
            fails("http-status", () -> s.client.check(s.scope, false, false));
            contractFailure(ContractException.Code.CREDENTIAL_UNAVAILABLE, () -> peer.check(s.scope, false, false));
            s.head(); s.client.check(s.scope, false, true);
            s.head(); peer.check(s.scope, false, false);
            check(s.f.requests == 3); // Explicit retry lifts the peer's cached denial too.
        }
        try (Setup s = new Setup(true)) {
            DeliveryClient peer = s.peer();
            s.f.responses.add(new Fake(403, new byte[0]));
            fails("http-status", () -> s.client.check(s.scope, false, false));
            try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(s.f.dir.resolve("transfer.lock"),
                    StandardOpenOption.WRITE); java.nio.channels.FileLock lock = channel.lock()) {
                check(lock.isValid());
                contractFailure(ContractException.Code.UNAVAILABLE, () -> peer.check(s.scope, false, true));
                DeliveryLocksTest.probe(s.f.dir.resolve("transfer.lock"), "BUSY");
            }
            contractFailure(ContractException.Code.CREDENTIAL_UNAVAILABLE, () -> s.client.check(s.scope, false, false));
            check(s.f.requests == 1); // Busy retry must not erase a denial without an attempt.
        }
        for (boolean apkKey : new boolean[] {false, true}) try (Setup s = new Setup(apkKey)) {
            Path abandoned = s.f.dir.resolve("old-release.part"); Files.write(abandoned, new byte[] {1});
            s.head(); s.f.responses.add(new Fake(200, ARCHIVE));
            DeliveryClient.Result result = s.client.check(s.scope, true, false);
            check(result.stage.status == StageStatus.PENDING); check(s.life.stages == 1);
            check(s.life.reservations == 1 && s.life.releasedReservations == 1 && !s.life.reserved);
            check(!Files.exists(abandoned));
            try (DirectoryStream<Path> files = Files.newDirectoryStream(s.f.dir, "*.part")) { check(!files.iterator().hasNext()); }
            s.cached(); s.client.check(s.scope, false, false);
            check(s.life.observations == 2); check(s.metadata.headVerifications == 1);
            s.clock.wall = 1100; s.clock.elapsed = 100000;
            Fake fresh = s.head(); s.metadata.expiresAt = 1200;
            s.client.check(s.scope, false, false); check(fresh.getRequestProperty("If-None-Match") == null);
        }
        try (Setup s = new Setup(false)) {
            s.head(); s.client.check(s.scope, false, false);
            s.clock.wall = 999; s.clock.elapsed = 100001;
            Fake fresh = s.head(); s.client.check(s.scope, false, false);
            check(fresh.getRequestProperty("If-None-Match") == null); // elapsed expiry despite wall rollback
            RequestScope other = new RequestScope("example.app", "a".repeat(64), "stable", 31, Arrays.asList("x86_64"), 1);
            fresh = s.head(); s.client.check(other, false, false);
            check(fresh.getRequestProperty("If-None-Match") == null);
        }
        for (int status : new int[] {401, 403}) try (Setup s = new Setup(true)) {
            s.f.responses.add(new Fake(status, new byte[0]));
            fails("http-status", () -> s.client.check(s.scope, true, false));
            contractFailure(ContractException.Code.CREDENTIAL_UNAVAILABLE, () -> s.client.check(s.scope, true, false));
            check(s.f.requests == 1);
            s.client.installedCredential(new byte[] {1}); // Same APK/process restart preserves suppression.
            contractFailure(ContractException.Code.CREDENTIAL_UNAVAILABLE, () -> s.client.check(s.scope, true, false));
            s.head(); s.client.check(s.scope, false, true); check(s.f.requests == 2);
            s.f.responses.add(new Fake(status, new byte[0]));
            fails("http-status", () -> s.client.check(s.scope, false, false));
            Files.write(s.f.partial, new byte[] {1}); s.client.installedCredential(new byte[] {2});
            check(!Files.exists(s.f.partial));
            Fake fresh = s.head(); s.client.check(s.scope, false, false);
            check(fresh.getRequestProperty("If-None-Match") == null);
            check(fresh.getRequestProperty("Authorization").equals("Bearer " + "B".repeat(43)));
        }
        try (Setup s = new Setup(true)) {
            Files.write(s.f.partial, new byte[] {1, 2, 3});
            s.client.installedCredential(new byte[] {1});
            check(Files.size(s.f.partial) == 3); // Same credential preserves resumable bytes.
            check(s.life.credentialDenials == 0); // A new process must not revoke another same-scope admission.
            s.client.installedCredential(new byte[] {2});
            check(!Files.exists(s.f.partial));
        }
        try (Setup s = new Setup(true)) {
            Files.write(s.f.partial, new byte[] {1});
            contractFailure(ContractException.Code.CREDENTIAL_UNAVAILABLE, () -> s.client.installedCredential(null));
            check(!Files.exists(s.f.partial));
            check(s.life.credential == null);
            contractFailure(ContractException.Code.CREDENTIAL_UNAVAILABLE, () -> s.client.check(s.scope, false, true));
            check(s.f.requests == 0);
        }
        try (Setup s = new Setup(true)) {
            s.clock.wall = 2000;
            contractFailure(ContractException.Code.CREDENTIAL_UNAVAILABLE, () -> s.client.check(s.scope, true, false));
            check(s.f.requests == 0);
        }
        for (HeadStatus status : new HeadStatus[] {HeadStatus.NO_COMPATIBLE_RELEASE, HeadStatus.SHELL_UPDATE_REQUIRED})
            try (Setup s = new Setup(false)) {
                s.metadata.status = status; s.head();
                check(s.client.check(s.scope, true, false).status == status);
                check(s.life.observations == 1 && s.life.stages == 0);
            }
        try (Setup s = new Setup(false)) {
            s.f.responses.add(new Fake(200, new byte[] {0}).put("Content-Type", "application/json")
                    .put("ETag", "\"" + HttpTransport.hash(new byte[] {0}) + "\""));
            contractFailure(ContractException.Code.INVALID_SIGNATURE, () -> s.client.check(s.scope, true, false));
            check(s.life.observations == 0);
        }
        try (Setup s = new Setup(true)) {
            s.head(); s.f.responses.add(new Fake(200, ARCHIVE));
            s.life.duringStage = () -> {
                s.client.installedCredential(new byte[] {2});
                DeliveryLocksTest.probe(s.f.dir.resolve("transfer.lock"), "BUSY");
            };
            s.client.check(s.scope, true, false);
            check(s.life.stages == 1); // Borrowed source survives refresh until stage returns.
            try (DirectoryStream<Path> files = Files.newDirectoryStream(s.f.dir, "*.part")) {
                check(!files.iterator().hasNext());
            }
        }
        try (Setup s = new Setup(false, true)) {
            s.head();
            contractFailure(ContractException.Code.INSUFFICIENT_STORAGE, () -> s.client.check(s.scope, true, false));
            check(s.f.requests == 1 && s.life.stages == 0);
            check(s.life.observations == 1); // Storage rejection never undoes authenticated head observation.
            check(s.life.snapshot().availability == Availability.RUNNABLE);
            check(s.life.reservations == 1 && s.life.releasedReservations == 1 && !s.life.reserved);
        }
        System.out.println("DeliveryClientTest: " + TransportTest.assertions + " assertions passed");
    }
}
