package com.lelloman.paravoidandroid.delivery;

import com.lelloman.paravoidandroid.contract.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import static com.lelloman.paravoidandroid.delivery.TransportTest.check;

public final class ApkGrantReaderTest {
    static byte[] apk(byte[] grant, boolean duplicate, boolean signature) throws IOException {
        ByteArrayOutputStream pairs = new ByteArrayOutputStream();
        if (signature) pair(pairs, 0x7109871a, new byte[] {0});
        if (grant != null) {
            pair(pairs, Protocol.GRANT_BLOCK_ID, grant);
            if (duplicate) pair(pairs, Protocol.GRANT_BLOCK_ID, grant);
        }
        ByteBuffer apk = ByteBuffer.allocate(8 + pairs.size() + 24 + 22).order(ByteOrder.LITTLE_ENDIAN);
        apk.putLong(pairs.size() + 24).put(pairs.toByteArray()).putLong(pairs.size() + 24);
        apk.put("APK Sig Block 42".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        int directory = apk.position();
        apk.putInt(0x06054b50).putShort((short) 0).putShort((short) 0).putShort((short) 0).putShort((short) 0);
        apk.putInt(0).putInt(directory).putShort((short) 0);
        return apk.array();
    }
    static void pair(ByteArrayOutputStream out, int id, byte[] value) throws IOException {
        out.write(ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putLong(value.length + 4).putInt(id).array());
        out.write(value);
    }
    public static void main(String[] args) throws Exception {
        Path file = Files.createTempFile("paravoid-grant-carrier-", ".apk");
        try {
            byte[] grant = "opaque grant; shared verifier must authenticate this".getBytes();
            Files.write(file, apk(grant, false, true));
            check(Arrays.equals(grant, ApkGrantReader.read(file.toFile())));
            // This is a structural-only fake APK; no test claims it has a valid developer signature.
            for (byte[] invalid : new byte[][] {apk(grant, true, true), apk(grant, false, false), new byte[100],
                    Arrays.copyOf(apk(grant, false, true), 30)}) {
                Files.write(file, invalid);
                DeliveryClientTest.contractFailure(ContractException.Code.MALFORMED, () -> ApkGrantReader.read(file.toFile()));
            }
            Files.write(file, apk(null, false, true));
            DeliveryClientTest.contractFailure(ContractException.Code.CREDENTIAL_UNAVAILABLE, () -> ApkGrantReader.read(file.toFile()));
            for (byte[] invalid : new byte[][] {new byte[0], new byte[16385]}) {
                Files.write(file, apk(invalid, false, true));
                DeliveryClientTest.contractFailure(ContractException.Code.LIMIT_EXCEEDED, () -> ApkGrantReader.read(file.toFile()));
            }
        } finally { Files.deleteIfExists(file); }
        System.out.println("ApkGrantReaderTest: " + TransportTest.assertions + " assertions passed");
    }
}
