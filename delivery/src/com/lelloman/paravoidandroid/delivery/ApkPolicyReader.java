package com.lelloman.paravoidandroid.delivery;

import com.lelloman.paravoidandroid.contract.*;
import com.lelloman.paravoidandroid.contract.Protocol.ShellPolicy;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Read only the installed/developer-signature-verified base APK, never payload resources or asset overlays. */
public final class ApkPolicyReader {
    private ApkPolicyReader() {}
    public static ShellPolicy read(File apk, boolean debuggable) throws IOException, ContractException {
        return InstalledPolicyCodec.read(envelope(apk), debuggable);
    }
    public static byte[] envelope(File apk) throws IOException, ContractException {
        try (ZipFile zip = new ZipFile(apk)) {
            ZipEntry policy = null;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry candidate = entries.nextElement();
                if (candidate.getName().equals(InstalledPolicyCodec.APK_PATH)) {
                    if (policy != null || candidate.isDirectory()) throw invalid();
                    policy = candidate;
                }
            }
            if (policy == null || policy.getSize() < 1 || policy.getSize() > InstalledPolicyCodec.MAX_BYTES) throw invalid();
            try (InputStream input = zip.getInputStream(policy); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int count; long total = 0;
                while ((count = input.read(buffer)) != -1) {
                    total += count; if (total > policy.getSize() || total > InstalledPolicyCodec.MAX_BYTES) throw invalid();
                    out.write(buffer, 0, count);
                }
                if (total != policy.getSize()) throw invalid();
                return out.toByteArray();
            }
        }
    }
    private static ContractException invalid() { return new ContractException(ContractException.Code.INCOMPATIBLE, "APK policy missing or malformed"); }
}
