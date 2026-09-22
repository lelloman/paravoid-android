package com.lelloman.paravoidandroid.contract;

import com.lelloman.paravoidandroid.contract.Protocol.*;

/** TEST ONLY. Deliberately opaque fixture identity; never compiled into production. */
public final class FakeMetadata implements MetadataVerifier {
    public long issuedAt = 900, expiresAt = 1100;
    public long grantIssuedAt = 900, grantExpiresAt = 2000;
    public HeadStatus status = HeadStatus.AVAILABLE;
    public ExpectedArchive archive;
    public int headVerifications;
    @Override public VerifiedHead verifyHead(byte[] envelope, ShellPolicy policy, RequestScope scope) throws ContractException {
        headVerifications++;
        if (envelope.length != 1 || envelope[0] != 1) throw new ContractException(ContractException.Code.INVALID_SIGNATURE, "Test verifier rejected bytes");
        return new VerifiedHead(scope, 1, issuedAt, expiresAt, status, status == HeadStatus.AVAILABLE ? archive : null,
                "test", envelope, envelope);
    }
    @Override public VerifiedGrant verifyGrant(byte[] envelope, ShellPolicy policy) throws ContractException {
        if (envelope.length != 1 || envelope[0] < 1 || envelope[0] > 2)
            throw new ContractException(ContractException.Code.INVALID_SIGNATURE, "Test grant rejected");
        return new VerifiedGrant(policy.applicationId, policy.shellContractId, policy.baseUrl, "test", "key", "issuer",
                (envelope[0] == 1 ? "A" : "B").repeat(43), grantIssuedAt, grantExpiresAt, envelope);
    }
    @Override public TrustPolicy readTrustPolicy(byte[] bytes) { throw new AssertionError("not used"); }
}
