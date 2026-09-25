package com.lelloman.paravoidandroid.contract;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.math.BigInteger;
import java.util.*;
import com.lelloman.paravoidandroid.contract.Protocol.*;
import static com.lelloman.paravoidandroid.contract.ContractException.Code.*;

/** Stateless exact-byte verification. Does not replace lifecycle time/replay admission. */
public final class SignedMetadataVerifier implements MetadataVerifier {
    @Override public VerifiedHead verifyHead(byte[] bytes, ShellPolicy policy, RequestScope scope) throws ContractException {
        if ("feed".equals(policy.updates.get("mode"))) return verifyFeed(bytes, policy, scope);
        bytes = boundedCopy(bytes, Protocol.MAX_HEAD_BYTES);
        validatePolicy(policy);
        if (!policy.updatesEnabled) throw fail(INCOMPATIBLE, "Updates disabled");
        scope(policy, scope);
        Envelope envelope = authenticate(bytes, "head", policy.trust.headKeys, Protocol.MAX_HEAD_BYTES);
        Map<String,Object> b = envelope.body;
        fields(b, "version applicationId shellContractId channel sdk abis runtimeAbi formatVersion headRevision issuedAt expiresAt status release");
        version(b, "version"); version(b, "formatVersion");
        if (!scope.applicationId.equals(string(b, "applicationId")) || !scope.shellContractId.equals(hash(b, "shellContractId"))
                || !scope.channel.equals(identifier(b, "channel")) || scope.sdk != number(b, "sdk", 1)
                || scope.runtimeAbi != number(b, "runtimeAbi", 1) || !scope.abis.equals(abis(b.get("abis"))))
            throw fail(INCOMPATIBLE, "Head request scope mismatch");
        long revision = number(b, "headRevision", 1), issued = number(b, "issuedAt", 0), expires = number(b, "expiresAt", 0);
        if (expires <= issued || expires - issued > 86400) throw fail(MALFORMED, "Head validity interval");
        String status = string(b, "status");
        HeadStatus parsed;
        ExpectedArchive release = null;
        switch (status) {
            case "available":
                parsed = HeadStatus.AVAILABLE;
                Map<String,Object> r = object(b.get("release"));
                fields(r, "releaseId payloadVersion manifestSha256 archiveSha256 archiveSize");
                long size = number(r, "archiveSize", 1);
                if (size > Protocol.MAX_ARCHIVE_BYTES) throw fail(LIMIT_EXCEEDED, "Archive byte limit");
                release = new ExpectedArchive(identifier(r, "releaseId"), number(r, "payloadVersion", 1),
                    hash(r, "manifestSha256"), hash(r, "archiveSha256"), size);
                break;
            case "no-compatible-release": parsed = HeadStatus.NO_COMPATIBLE_RELEASE; break;
            case "shell-update-required": parsed = HeadStatus.SHELL_UPDATE_REQUIRED; break;
            default: throw fail(MALFORMED, "Unknown head status");
        }
        if (parsed != HeadStatus.AVAILABLE && b.get("release") != null) throw fail(MALFORMED, "Unexpected release offer");
        return new VerifiedHead(scope, revision, issued, expires, parsed, release, envelope.keyId, envelope.bodyBytes, bytes);
    }

    /** Portable signed discovery. Device filtering happens only after authenticating the entire feed. */
    public VerifiedHead verifyFeed(byte[] bytes, ShellPolicy policy, RequestScope scope) throws ContractException {
        bytes=boundedCopy(bytes, Protocol.MAX_HEAD_BYTES); validatePolicy(policy); scope(policy,scope);
        if(!policy.updatesEnabled) throw fail(INCOMPATIBLE,"Updates disabled");
        Envelope envelope=authenticate(bytes,"feed",policy.trust.headKeys,Protocol.MAX_HEAD_BYTES);
        Map<String,Object> b=envelope.body;
        fields(b,"version applicationId shellContractId channel runtimeAbi formatVersion headRevision issuedAt expiresAt status releases");
        version(b,"version"); version(b,"formatVersion");
        if(!scope.applicationId.equals(string(b,"applicationId")) || !scope.shellContractId.equals(hash(b,"shellContractId"))
                || !scope.channel.equals(identifier(b,"channel")) || scope.runtimeAbi!=number(b,"runtimeAbi",1))
            throw fail(INCOMPATIBLE,"Feed request scope mismatch");
        long revision=number(b,"headRevision",1), issued=number(b,"issuedAt",0), expires=number(b,"expiresAt",0);
        if(expires<=issued || expires-issued>86400) throw fail(MALFORMED,"Feed validity interval");
        String status=string(b,"status");
        if(!Arrays.asList("available","no-compatible-release","shell-update-required").contains(status)) throw fail(MALFORMED,"Unknown feed status");
        if(!(b.get("releases") instanceof List)) throw fail(MALFORMED,"Invalid feed releases");
        List<?> entries=(List<?>)b.get("releases");
        if(entries.size()>128 || !status.equals("available") && !entries.isEmpty()) throw fail(MALFORMED,"Invalid feed releases");
        ExpectedArchive selected=null;
        for(Object entry:entries) {
            Map<String,Object> item=object(entry); fields(item,"minSdk maxSdk abis release");
            long min=number(item,"minSdk",30), max=number(item,"maxSdk",0);
            if(min>Integer.MAX_VALUE || max>Integer.MAX_VALUE || max!=0 && max<min) throw fail(MALFORMED,"Invalid SDK range");
            List<String> supported=abis(item.get("abis"));
            Map<String,Object> r=object(item.get("release"));
            fields(r,"releaseId payloadVersion manifestSha256 archiveSha256 archiveSize");
            long size=number(r,"archiveSize",1);
            if(size>Protocol.MAX_ARCHIVE_BYTES) throw fail(LIMIT_EXCEEDED,"Archive byte limit");
            ExpectedArchive candidate=new ExpectedArchive(identifier(r,"releaseId"),number(r,"payloadVersion",1),hash(r,"manifestSha256"),hash(r,"archiveSha256"),size);
            if(scope.sdk<min || max!=0 && scope.sdk>max || !supported.isEmpty() && Collections.disjoint(scope.abis,supported)) continue;
            if(selected!=null && selected.payloadVersion==candidate.payloadVersion && !selected.equals(candidate)) throw fail(IDENTITY_CONFLICT,"Ambiguous feed release");
            if(selected==null || candidate.payloadVersion>selected.payloadVersion) selected=candidate;
        }
        HeadStatus parsed=selected!=null ? HeadStatus.AVAILABLE : status.equals("shell-update-required") ? HeadStatus.SHELL_UPDATE_REQUIRED : HeadStatus.NO_COMPATIBLE_RELEASE;
        return new VerifiedHead(scope,revision,issued,expires,parsed,selected,envelope.keyId,envelope.bodyBytes,bytes);
    }

    @Override public VerifiedGrant verifyGrant(byte[] bytes, ShellPolicy policy) throws ContractException {
        bytes = boundedCopy(bytes, Protocol.MAX_GRANT_BYTES);
        validatePolicy(policy);
        if (!policy.updatesEnabled || policy.authentication != Authentication.APK_KEY) throw fail(INCOMPATIBLE, "Grant not enabled");
        Envelope envelope = authenticate(bytes, "grant", policy.trust.grantKeys, Protocol.MAX_GRANT_BYTES);
        Map<String,Object> b = envelope.body;
        fields(b, "version applicationId shellContractId audience grantId keyId key issuedAt expiresAt");
        version(b, "version");
        if (!policy.applicationId.equals(string(b, "applicationId")) || !policy.shellContractId.equals(hash(b, "shellContractId"))
                || !policy.baseUrl.equals(string(b, "audience"))) throw fail(INCOMPATIBLE, "Grant scope mismatch");
        long issued = number(b, "issuedAt", 0), expires = number(b, "expiresAt", 0);
        if (expires != 0 && expires <= issued) throw fail(MALFORMED, "Grant validity interval");
        String key = string(b, "key");
        if (!key.matches("[A-Za-z0-9_-]{43}")) throw fail(MALFORMED, "Grant key encoding");
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(key);
            if (decoded.length != 32 || !Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(key))
                throw fail(MALFORMED, "Grant key encoding");
        } catch (IllegalArgumentException error) { throw fail(MALFORMED, "Grant key encoding"); }
        return new VerifiedGrant(policy.applicationId, policy.shellContractId, policy.baseUrl, identifier(b, "grantId"),
            identifier(b, "keyId"), envelope.keyId, key, issued, expires, bytes);
    }

    @Override public TrustPolicy readTrustPolicy(byte[] bytes) throws ContractException {
        bytes = boundedCopy(bytes, Protocol.MAX_RELEASE_BYTES);
        Map<String,Object> b = object(StrictJson.parse(bytes, Protocol.MAX_RELEASE_BYTES));
        ordinaryStrings(b);
        fields(b, "version applicationId releaseKeys headKeys grantKeys minimumPayloadVersion minimumHeadRevision");
        version(b, "version");
        TrustPolicy trust = new TrustPolicy(string(b, "applicationId"), keys(b.get("releaseKeys")), keys(b.get("headKeys")),
            keys(b.get("grantKeys")), number(b, "minimumPayloadVersion", 0), number(b, "minimumHeadRevision", 0));
        validateTrust(trust);
        return trust;
    }

    static final class Envelope {
        final String keyId;
        final byte[] bodyBytes;
        final Map<String,Object> body;
        Envelope(String keyId, byte[] bodyBytes, Map<String,Object> body) {
            this.keyId = keyId; this.bodyBytes = bodyBytes; this.body = body;
        }
    }
    static Envelope authenticate(byte[] bytes, String role, Map<String,PublicKey> keys, int limit) throws ContractException {
        Map<String,Object> envelope = object(StrictJson.parse(bytes, limit));
        fields(envelope, "keyId body signature");
        String keyId = identifier(envelope, "keyId");
        PublicKey key = keys.get(keyId);
        if (key == null) throw fail(UNTRUSTED_KEY, "Untrusted signing key");
        rsa(key);
        byte[] body = base64(envelope.get("body")), signature = base64(envelope.get("signature"));
        if (signature.length != 384) throw fail(INVALID_SIGNATURE, "Invalid signature length");
        try {
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(key);
            verifier.update(("paravoid/v1/" + role + "\n").getBytes(StandardCharsets.US_ASCII));
            verifier.update(body);
            if (!verifier.verify(signature)) throw fail(INVALID_SIGNATURE, "Signature verification failed");
        } catch (GeneralSecurityException error) { throw fail(INVALID_SIGNATURE, "Signature verification failed"); }
        Map<String,Object> parsed = object(StrictJson.parse(body, limit));
        ordinaryStrings(parsed);
        return new Envelope(keyId, body, parsed);
    }
    static void validatePolicy(ShellPolicy policy) throws ContractException {
        validateTrust(policy.trust);
        UpdateConfiguration.validate(policy.updates);
        if(policy.updates.getOrDefault("pushWebSocketUrl", "").startsWith("ws://") && !policy.debugHttpAllowed)
            throw fail(INCOMPATIBLE,"Insecure push endpoint in release policy");
        for(String key: Arrays.asList("metadataUrl","payloadUrlTemplate")) {
            String url=policy.updates.getOrDefault(key, "");
            if(!url.isEmpty() && !url.startsWith("https://") && !policy.debugHttpAllowed) throw fail(INCOMPATIBLE,"HTTP update endpoint in release policy");
        }
        if (!policy.applicationId.equals(policy.trust.applicationId) || !policy.shellContractId.matches("[0-9a-f]{64}")
                || policy.runtimeAbi != Protocol.RUNTIME_ABI || !policy.channel.matches("[A-Za-z0-9_-]{1,64}"))
            throw fail(INCOMPATIBLE, "Invalid installed policy");
        if (policy.updatesEnabled && policy.trust.headKeys.isEmpty()) throw fail(INCOMPATIBLE, "Missing head trust");
        if (policy.authentication == Authentication.APK_KEY && policy.trust.grantKeys.isEmpty()) throw fail(INCOMPATIBLE, "Missing grant trust");
        if (policy.bootstrap == Bootstrap.EMPTY && !policy.updatesEnabled) throw fail(INCOMPATIBLE, "Empty bootstrap requires updates");
        if (policy.updatesEnabled) {
            try {
                java.net.URI uri = new java.net.URI(policy.baseUrl);
                String scheme = uri.getScheme();
                if (!("https".equals(scheme) || (policy.debugHttpAllowed && "http".equals(scheme)))
                        || uri.getHost() == null || !uri.getHost().equals(uri.getHost().toLowerCase(Locale.ROOT))
                        || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                        || !policy.baseUrl.endsWith("/") || !uri.normalize().equals(uri)
                        || uri.getPort() == ("https".equals(scheme) ? 443 : 80)
                        || uri.getPort() > 65535 || uri.getPort() == 0)
                    throw fail(INCOMPATIBLE, "Invalid installed endpoint");
            } catch (java.net.URISyntaxException error) { throw fail(INCOMPATIBLE, "Invalid installed endpoint"); }
        }
    }
    static void scope(ShellPolicy policy, RequestScope scope) throws ContractException {
        if (!scope.applicationId.equals(policy.applicationId) || !scope.shellContractId.equals(policy.shellContractId)
                || !scope.channel.equals(policy.channel) || scope.runtimeAbi != policy.runtimeAbi || scope.sdk < 30)
            throw fail(INCOMPATIBLE, "Invalid local request scope");
        abis(scope.abis);
    }
    static void validateTrust(TrustPolicy trust) throws ContractException {
        if (trust.applicationId.isEmpty() || trust.applicationId.getBytes(StandardCharsets.UTF_8).length > 4096
                || trust.releaseKeys.isEmpty() || trust.minimumHeadRevision < 0 || trust.minimumHeadRevision > Protocol.MAX_INTEGER
                || trust.minimumPayloadVersion < 0 || trust.minimumPayloadVersion > Protocol.MAX_INTEGER)
            throw fail(INCOMPATIBLE, "Invalid trust policy");
        Set<String> previousRoles = new HashSet<>();
        for (Map<String,PublicKey> role : Arrays.asList(trust.releaseKeys, trust.headKeys, trust.grantKeys)) {
            Set<String> thisRole = new HashSet<>();
            for (Map.Entry<String,PublicKey> entry : role.entrySet()) {
                if (!entry.getKey().matches("[A-Za-z0-9_-]{1,64}")) throw fail(MALFORMED, "Key ID");
                rsa(entry.getValue());
                String hash = Digests.sha256(entry.getValue().getEncoded());
                if (previousRoles.contains(hash)) throw fail(INCOMPATIBLE, "Signing key roles must be separate");
                thisRole.add(hash);
            }
            previousRoles.addAll(thisRole);
        }
    }
    private static void rsa(PublicKey key) throws ContractException {
        if (!(key instanceof RSAPublicKey) || ((RSAPublicKey)key).getModulus().bitLength() != 3072
                || !((RSAPublicKey)key).getPublicExponent().equals(BigInteger.valueOf(65537)))
            throw fail(INCOMPATIBLE, "Unsupported signing key profile");
    }
    private static Map<String,PublicKey> keys(Object value) throws ContractException {
        Map<String,PublicKey> result = new LinkedHashMap<>();
        for (Map.Entry<String,Object> e : object(value).entrySet()) {
            byte[] der = base64(e.getValue());
            try {
                PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
                rsa(key);
                if (!Arrays.equals(der, key.getEncoded())) throw fail(MALFORMED, "Noncanonical public key encoding");
                result.put(e.getKey(), key);
            } catch (GeneralSecurityException error) { throw fail(MALFORMED, "Invalid public key"); }
        }
        return result;
    }
    static byte[] base64(Object value) throws ContractException {
        if (!(value instanceof String)) throw fail(MALFORMED, "Base64 type");
        String text = (String)value;
        try {
            byte[] decoded = Base64.getDecoder().decode(text);
            if (!Base64.getEncoder().encodeToString(decoded).equals(text)) throw fail(MALFORMED, "Noncanonical Base64");
            return decoded;
        } catch (IllegalArgumentException error) { throw fail(MALFORMED, "Invalid Base64"); }
    }
    @SuppressWarnings("unchecked") static Map<String,Object> object(Object value) throws ContractException {
        if (!(value instanceof Map)) throw fail(MALFORMED, "Expected JSON object");
        return (Map<String,Object>)value;
    }
    static void fields(Map<String,Object> map, String names) throws ContractException {
        if (!map.keySet().equals(new HashSet<>(Arrays.asList(names.split(" "))))) throw fail(MALFORMED, "Unknown or missing fields");
    }
    static String string(Map<String,Object> map, String key) throws ContractException {
        if (!(map.get(key) instanceof String)) throw fail(MALFORMED, "Expected string field");
        return (String)map.get(key);
    }
    static String identifier(Map<String,Object> map, String key) throws ContractException {
        String value = string(map, key);
        if (!value.matches("[A-Za-z0-9_-]{1,64}")) throw fail(MALFORMED, "Invalid identifier");
        return value;
    }
    static String hash(Map<String,Object> map, String key) throws ContractException {
        String value = string(map, key);
        if (!value.matches("[0-9a-f]{64}")) throw fail(MALFORMED, "Invalid SHA-256");
        return value;
    }
    static long number(Map<String,Object> map, String key, long minimum) throws ContractException {
        Object value = map.get(key);
        if (!(value instanceof Long) || (Long)value < minimum) throw fail(MALFORMED, "Invalid integer field");
        return (Long)value;
    }
    static void version(Map<String,Object> map, String key) throws ContractException {
        if (number(map, key, 1) != 1) throw fail(INCOMPATIBLE, "Unsupported version");
    }
    static List<String> abis(Object value) throws ContractException {
        if (!(value instanceof List)) throw fail(MALFORMED, "Invalid ABI list");
        List<String> result = new ArrayList<>();
        for (Object item : (List<?>)value) {
            if (!(item instanceof String) || !Protocol.ABIS.contains(item) || result.contains(item)) throw fail(MALFORMED, "Invalid ABI list");
            result.add((String)item);
        }
        return result;
    }
    static void ordinaryStrings(Object value) throws ContractException {
        if (value instanceof String && ((String)value).getBytes(StandardCharsets.UTF_8).length > 4096)
            throw fail(LIMIT_EXCEEDED, "Ordinary string byte limit");
        if (value instanceof Map) for (Map.Entry<?,?> entry : ((Map<?,?>)value).entrySet()) { ordinaryStrings(entry.getKey()); ordinaryStrings(entry.getValue()); }
        if (value instanceof List) for (Object item : (List<?>)value) ordinaryStrings(item);
    }
    static ContractException fail(ContractException.Code code, String message) { return new ContractException(code, message); }
    private static byte[] boundedCopy(byte[] bytes, int limit) throws ContractException {
        if (bytes.length > limit) throw fail(LIMIT_EXCEEDED, "Metadata byte limit");
        return bytes.clone();
    }
}
