package com.lelloman.paravoidandroid.contract;

import java.util.*;
import com.lelloman.paravoidandroid.contract.Protocol.*;
import static com.lelloman.paravoidandroid.contract.SignedMetadataVerifier.*;
import static com.lelloman.paravoidandroid.contract.ContractException.Code.*;

/** APK-pinned public policy. The APK, not a downloaded head/VPK, is its trust source. */
public final class InstalledPolicyCodec {
    public static final String APK_PATH = "assets/paravoid/shell-policy.json";
    public static final String PROFILE = "complete-apk-v1";
    public static final int MAX_BYTES = 1024 * 1024;
    private static final String INSTALLED_FIELDS = "applicationId minSdk manifestSha256 declarations pinnedResources runtimeClasses nativeAbis ledgerReservations apkSigners toolchain";
    private InstalledPolicyCodec() {}

    /** Adapt a reviewed installed-boundary snapshot; never carry its old embedded-only distribution identity forward. */
    public static byte[] create(byte[] embeddedContract, byte[] trustPolicy, Bootstrap bootstrap, boolean enabled,
            String baseUrl, String channel, Authentication authentication, boolean debugHttpAllowed, boolean debuggable) throws ContractException {
        Map<String,Object> original = object(StrictJson.parse(embeddedContract, MAX_BYTES));
        fields(original, "version contractId descriptor"); version(original, "version");
        Map<String,Object> installed = new LinkedHashMap<>(object(original.get("descriptor")));
        if (!hash(original, "contractId").equals(Digests.sha256(StrictJson.canonical(installed)))
                || !"embedded-apk-v1".equals(installed.remove("profile"))) throw fail(INCOMPATIBLE, "Invalid source installed boundary");
        installed.remove("distribution");
        Map<String,Object> descriptor = new LinkedHashMap<>(); descriptor.put("profile", PROFILE); descriptor.put("installed", installed);
        descriptor.put("runtimeAbi", Protocol.RUNTIME_ABI);
        descriptor.put("trustPolicy", object(StrictJson.parse(trustPolicy, MAX_BYTES)));
        Map<String,Object> distribution = new LinkedHashMap<>(); distribution.put("bootstrap", bootstrap == Bootstrap.EMBEDDED ? "embedded" : "empty");
        distribution.put("enabled", enabled); distribution.put("baseUrl", baseUrl); distribution.put("channel", channel);
        distribution.put("authentication", authentication == Authentication.PUBLIC ? "public" : "apkKey");
        distribution.put("debugHttpAllowed", debugHttpAllowed); descriptor.put("distribution", distribution);
        Map<String,Object> result = new LinkedHashMap<>(); result.put("version", 1); result.put("descriptor", descriptor);
        result.put("contractId", Digests.sha256(StrictJson.canonical(descriptor)));
        byte[] encoded = StrictJson.canonical(result);
        read(encoded, debuggable); // Same implementation as runtime; release never accepts debug HTTP policy.
        return encoded;
    }

    /** Caller gets debuggable from the actual build variant or installed ApplicationInfo, never from network input. */
    public static ShellPolicy read(byte[] encoded, boolean debuggable) throws ContractException {
        Map<String,Object> wrapper = object(StrictJson.parse(encoded, MAX_BYTES));
        ordinaryStrings(wrapper);
        fields(wrapper, "version contractId descriptor"); version(wrapper, "version");
        Map<String,Object> descriptor = object(wrapper.get("descriptor"));
        fields(descriptor, "profile installed runtimeAbi distribution trustPolicy");
        byte[] descriptorBytes = StrictJson.canonical(descriptor);
        if (!PROFILE.equals(string(descriptor, "profile")) || number(descriptor, "runtimeAbi", 1) != Protocol.RUNTIME_ABI)
            throw fail(INCOMPATIBLE, "Unsupported installed policy profile");
        String contract = hash(wrapper, "contractId");
        if (!contract.equals(Digests.sha256(descriptorBytes))) throw fail(INTEGRITY, "Installed policy contract mismatch");
        Map<String,Object> installed = object(descriptor.get("installed")); fields(installed, INSTALLED_FIELDS);
        if (number(installed, "minSdk", 30) > Integer.MAX_VALUE) throw fail(INCOMPATIBLE, "Invalid installed minimum SDK");
        hash(installed, "manifestSha256");
        strings(object(installed.get("declarations")), false); strings(object(installed.get("toolchain")), false);
        strings(object(installed.get("runtimeClasses")), true); strings(object(installed.get("nativeAbis")), true);
        if (!Protocol.ABIS.containsAll(object(installed.get("nativeAbis")).keySet())) throw fail(INCOMPATIBLE, "Unknown installed ABI");
        Map<String,String> reservations = reservations(object(installed.get("ledgerReservations")));
        for (Map.Entry<String,Object> entry : object(installed.get("pinnedResources")).entrySet()) {
            Map<String,Object> pin = object(entry.getValue()); fields(pin, "id sha256"); hash(pin, "sha256");
            if (!string(pin, "id").equals(reservations.get(entry.getKey()))) throw fail(INCOMPATIBLE, "Pinned resource lacks installed reservation");
        }
        Object signers = installed.get("apkSigners");
        if (!(signers instanceof List) || ((List<?>)signers).isEmpty()) throw fail(MALFORMED, "Missing installed signer identity");
        for (Object signer : (List<?>)signers) if (!(signer instanceof String) || !((String)signer).matches("[0-9a-f]{64}")) throw fail(MALFORMED, "Invalid installed signer identity");
        TrustPolicy trust = new SignedMetadataVerifier().readTrustPolicy(StrictJson.canonical(descriptor.get("trustPolicy")));
        Map<String,Object> distribution = object(descriptor.get("distribution"));
        fields(distribution, "bootstrap enabled baseUrl channel authentication debugHttpAllowed");
        String mode = string(distribution, "bootstrap"), auth = string(distribution, "authentication");
        if (!mode.equals("embedded") && !mode.equals("empty")) throw fail(MALFORMED, "Invalid bootstrap mode");
        if (!auth.equals("public") && !auth.equals("apkKey")) throw fail(MALFORMED, "Invalid authentication mode");
        boolean debugHttp = bool(distribution, "debugHttpAllowed"), enabled = bool(distribution, "enabled");
        if (debugHttp && !debuggable) throw fail(INCOMPATIBLE, "Debug HTTP policy in non-debuggable shell");
        ShellPolicy policy = new ShellPolicy(string(installed, "applicationId"), contract, trust,
            string(distribution, "baseUrl"), string(distribution, "channel"), auth.equals("public") ? Authentication.PUBLIC : Authentication.APK_KEY,
            mode.equals("embedded") ? Bootstrap.EMBEDDED : Bootstrap.EMPTY, enabled, debugHttp, Protocol.RUNTIME_ABI, reservations, descriptorBytes);
        validatePolicy(policy);
        return policy;
    }

    /** Recover the boundary representation consumed by existing ledger/contract diagnostics. */
    public static byte[] installedBoundary(byte[] encoded, boolean debuggable) throws ContractException {
        ShellPolicy policy = read(encoded, debuggable);
        Map<String,Object> descriptor = object(StrictJson.parse(policy.contractDescriptor(), MAX_BYTES));
        Map<String,Object> installed = new LinkedHashMap<>(object(descriptor.get("installed")));
        installed.put("profile", "embedded-apk-v1");
        Map<String,Object> distribution = new LinkedHashMap<>(); distribution.put("bootstrap", "embedded"); distribution.put("updates", false);
        installed.put("distribution", distribution);
        Map<String,Object> wrapper = new LinkedHashMap<>(); wrapper.put("version", 1); wrapper.put("descriptor", installed);
        wrapper.put("contractId", Digests.sha256(StrictJson.canonical(installed)));
        byte[] canonical = StrictJson.canonical(wrapper);
        byte[] newline = Arrays.copyOf(canonical, canonical.length + 1); newline[canonical.length] = '\n';
        return newline;
    }
    private static boolean bool(Map<String,Object> map, String name) throws ContractException {
        Object value = map.get(name); if (!(value instanceof Boolean)) throw fail(MALFORMED, "Expected policy boolean"); return (Boolean)value;
    }
    private static void strings(Map<String,Object> map, boolean hashes) throws ContractException {
        for (Map.Entry<String,Object> entry : map.entrySet()) {
            if (entry.getKey().length() > 4096 || !(entry.getValue() instanceof String) || ((String)entry.getValue()).length() > 4096
                    || (hashes && !((String)entry.getValue()).matches("[0-9a-f]{64}"))) throw fail(MALFORMED, "Invalid installed boundary entry");
        }
    }
    private static Map<String,String> reservations(Map<String,Object> map) throws ContractException {
        Map<String,String> result = new LinkedHashMap<>(), types = new HashMap<>(), typeIds = new HashMap<>(); Set<String> ids = new HashSet<>();
        for (Map.Entry<String,Object> e : map.entrySet()) {
            if (e.getKey().length() > 4096 || !e.getKey().matches("[a-z][a-z0-9_]*/[A-Za-z_][A-Za-z0-9_.]*")
                    || !(e.getValue() instanceof String) || !((String)e.getValue()).matches("0x7f[0-9a-f]{6}")) throw fail(MALFORMED, "Invalid installed reservation");
            String id = (String)e.getValue(), type = e.getKey().substring(0, e.getKey().indexOf('/')), typeId = id.substring(4, 6);
            String previousType = types.put(type, typeId), previousId = typeIds.put(typeId, type);
            if (!ids.add(id) || typeId.equals("00") || (previousType != null && !previousType.equals(typeId)) || (previousId != null && !previousId.equals(type)))
                throw fail(MALFORMED, "Conflicting installed reservation");
            result.put(e.getKey(), id);
        }
        return result;
    }
}
