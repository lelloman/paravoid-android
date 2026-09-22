package com.lelloman.paravoidandroid.integration;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import com.lelloman.paravoidandroid.contract.*;
import com.lelloman.paravoidandroid.contract.Protocol.*;
import com.lelloman.paravoidandroid.delivery.DeliveryClient;
import com.lelloman.paravoidandroid.runtime.lifecycle.RuntimeLifecycle;

/** Separate JVMs model cold process boundaries; no fake verifier/admission/transport. */
public final class RealVpkFlow {
    @SuppressWarnings("unchecked") public static void main(String[] args) throws Exception {
        Path out = Paths.get(args[0]); String command = args[1];
        Map<String,Object> config = (Map<String,Object>)StrictJson.parse(Files.readAllBytes(out.resolve("fixture.json")), 1024 * 1024);
        SignedMetadataVerifier metadata = new SignedMetadataVerifier();
        String app = (String)config.get("applicationId"), contract = (String)config.get("shellContractId");
        ShellPolicy policy = new ShellPolicy(app, contract, metadata.readTrustPolicy(Files.readAllBytes(out.resolve("trust.json"))),
            (String)config.get("baseUrl"), "stable", Authentication.PUBLIC, Bootstrap.EMBEDDED, true, true, 1,
            (Map<String,String>)config.get("resourceReservations"), Files.readAllBytes(out.resolve("descriptor.json")));
        RequestScope device = new RequestScope(app, contract, "stable", 30, Collections.singletonList("x86_64"), 1);
        long now = ((Long)config.get("issuedAt")) + 1;
        RuntimeLifecycle lifecycle = new RuntimeLifecycle(out.resolve("lifecycle").toFile(), policy, device, new CompleteVpkVerifier(),
            new RuntimeLifecycle.Clock() { public long unixSeconds() { return now; } public long elapsedSeconds() { return 1; } public String bootId() { return "integration-boot"; } }, true);
        if (command.equals("init")) { lifecycle.initializeNew(); lifecycle.stageEmbedded(out.resolve("A.vpk").toFile()); }
        else if (command.equals("download")) {
            DeliveryClient delivery = new DeliveryClient(policy, metadata, lifecycle, new DeliveryClient.Clock() {
                public long unixSeconds() { return now; } public long elapsedMillis() { return 1000; }
            }, out.resolve("delivery").toFile());
            delivery.installedCredential(null);
            DeliveryClient.Result result = delivery.check(device, true, false);
            if (result.stage == null || lifecycle.snapshot().pending == null) throw new AssertionError("Not staged");
            if (lifecycle.snapshot().active != null && lifecycle.snapshot().active.payloadVersion != 1) throw new AssertionError("Download activated payload");
        } else if (command.equals("run")) {
            GenerationLease lease = lifecycle.acquireForProcess(); lease.beforeUserCode();
            long expected = Long.parseLong(args[2]);
            if (lease.release().identity.payloadVersion != expected || lease.files().dexFiles.isEmpty()
                    || !lease.files().resourcesApk.isFile() || !lease.files().javaResourcesJar.isFile()) throw new AssertionError("Incoherent generation");
            lease.applicationCreated(); lease.firstFrameRendered();
            System.out.println("PASS cold selection, authenticated materialization and healthy callbacks: " + expected);
        } else if (command.equals("replay")) {
            lifecycle.setCredentialScope(CredentialScope.publicAccess());
            VerifiedHead old = metadata.verifyHead(Files.readAllBytes(out.resolve("head-A.json")), policy, device);
            try { lifecycle.observeHead(old, CredentialScope.publicAccess()); throw new AssertionError("Replay accepted"); }
            catch (ContractException failure) { if (failure.code != ContractException.Code.REPLAY) throw failure; }
        } else throw new IllegalArgumentException("Unknown test command");
    }
}
