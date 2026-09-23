package com.lelloman.paravoidandroid.contract;

import java.io.*;
import java.util.*;
import java.nio.file.Files;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import static com.lelloman.paravoidandroid.contract.ContractException.Code.*;

/** Exact structural limits; sparse size probes are not gigabyte payload execution evidence. */
public class ArchiveLimitsTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void archiveSizeRejectsAboveLimitBeforeHashing() throws Exception {
        MetadataTestSupport keys = new MetadataTestSupport();
        File archive = temp.newFile();
        CompleteVpkVerifier verifier = new CompleteVpkVerifier();
        Protocol.ExpectedArchive expected = new Protocol.ExpectedArchive("r", 1, "a".repeat(64), "b".repeat(64), 22);
        try (RandomAccessFile file = new RandomAccessFile(archive, "rw")) {
            file.setLength(Protocol.MAX_ARCHIVE_BYTES + 1);
            assertEquals(LIMIT_EXCEEDED, assertThrows(ContractException.class,
                () -> verifier.verifyDownloaded(archive, keys.policy, keys.scope, expected)).code);
            file.setLength(Protocol.MAX_ARCHIVE_BYTES);
            assertEquals(INTEGRITY, assertThrows(ContractException.class,
                () -> verifier.verifyDownloaded(archive, keys.policy, keys.scope, expected)).code);
        }
    }

    @Test public void outerEntryCountAccepts4096AndRejects4097() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (int i = 0; i < 4096; i++) entries.put("entry" + i, new byte[0]);
        try (RandomAccessFile file = archive(entries)) {
            assertEquals(4096, new CheckedZip(file, 0, file.length(), true, 4096).entries.size());
        }
        entries.put("overflow", new byte[0]);
        try (RandomAccessFile file = archive(entries)) {
            assertEquals(LIMIT_EXCEEDED, assertThrows(ContractException.class,
                () -> new CheckedZip(file, 0, file.length(), true, 4096)).code);
        }
    }

    @Test public void entryNameByteLimitAccepts255AndRejects256() throws Exception {
        try (RandomAccessFile file = archive(Collections.singletonMap("a".repeat(255), new byte[0]))) {
            assertEquals(1, new CheckedZip(file, 0, file.length(), true, 4096).entries.size());
        }
        try (RandomAccessFile file = archive(Collections.singletonMap("a".repeat(256), new byte[0]))) {
            assertThrows(ContractException.class, () -> new CheckedZip(file, 0, file.length(), true, 4096));
        }
    }

    @Test public void envelopeReadIsBoundedBeforeAllocation() throws Exception {
        for (int size : new int[] {Protocol.MAX_RELEASE_BYTES, Protocol.MAX_RELEASE_BYTES + 1}) {
            try (RandomAccessFile file = archive(Collections.singletonMap("release.json", new byte[size]))) {
                CheckedZip zip = new CheckedZip(file, 0, file.length(), true, 4096);
                if (size == Protocol.MAX_RELEASE_BYTES)
                    assertEquals(size, zip.read(zip.entries.get("release.json"), Protocol.MAX_RELEASE_BYTES).length);
                else assertEquals(LIMIT_EXCEEDED, assertThrows(ContractException.class,
                    () -> zip.read(zip.entries.get("release.json"), Protocol.MAX_RELEASE_BYTES)).code);
            }
        }
    }

    private RandomAccessFile archive(Map<String, byte[]> entries) throws Exception {
        File file = temp.newFile();
        Files.write(file.toPath(), CompleteVpkVerifierTest.zip(entries, true));
        return new RandomAccessFile(file, "r");
    }
}
