package com.lelloman.paravoidandroid.delivery.tools;

import com.lelloman.paravoidandroid.contract.*;
import com.lelloman.paravoidandroid.contract.Protocol.*;
import com.lelloman.paravoidandroid.delivery.ApkGrantReader;
import com.lelloman.paravoidandroid.delivery.ApkPolicyReader;
import java.nio.file.*;
import java.util.*;

/** Personalization uses A's verifier; no second signed-JSON/security parser. */
public final class GrantCheck {
    public static void main(String[] args) throws Exception {
        try {
            if (args.length == 4 && args[0].equals("pinned")) {
                SignedMetadataVerifier verifier = new SignedMetadataVerifier();
                // The caller verifies developer signatures before invoking this path.
                ShellPolicy policy = ApkPolicyReader.read(Paths.get(args[1]).toFile(), false);
                byte[] bytes = args[2].equals("apk") ? ApkGrantReader.read(Paths.get(args[3]).toFile())
                    : bounded(Paths.get(args[3]), Protocol.MAX_GRANT_BYTES);
                if (args[2].equals("apk") && !policy.shellContractId.equals(ApkPolicyReader.read(Paths.get(args[3]).toFile(), false).shellContractId))
                    throw new IllegalArgumentException();
                VerifiedGrant grant = verifier.verifyGrant(bytes, policy);
                long now = System.currentTimeMillis() / 1000;
                if (grant.issuedAt > now + 300 || (grant.expiresAt != 0 && now >= grant.expiresAt)) throw new IllegalArgumentException();
                System.out.println("Grant verified against APK-pinned policy");
                return;
            }
            // Legacy six-argument entry is retained only for isolated stateless signature vectors.
            if (args.length != 6) throw new IllegalArgumentException();
            SignedMetadataVerifier verifier = new SignedMetadataVerifier();
            byte[] trustBytes = bounded(Paths.get(args[0]), Protocol.MAX_RELEASE_BYTES);
            TrustPolicy trust = verifier.readTrustPolicy(trustBytes);
            ShellPolicy policy = new ShellPolicy(trust.applicationId, args[1], trust, args[2], args[3],
                    Authentication.APK_KEY, Bootstrap.EMPTY, true, false, 1, Collections.emptyMap(), new byte[0]);
            byte[] bytes = args[4].equals("apk") ? ApkGrantReader.read(Paths.get(args[5]).toFile())
                    : bounded(Paths.get(args[5]), Protocol.MAX_GRANT_BYTES);
            verifier.verifyGrant(bytes, policy);
            System.out.println("Grant signature and scope verified");
        } catch (Exception rejected) {
            System.err.println("Grant signature, scope or carrier verification failed");
            System.exit(1); // Never print parser exceptions, grant bodies or keys.
        }
    }
    private static byte[] bounded(Path file, int limit) throws Exception {
        try (java.io.InputStream input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) throw new IllegalArgumentException();
            return bytes;
        }
    }
}
