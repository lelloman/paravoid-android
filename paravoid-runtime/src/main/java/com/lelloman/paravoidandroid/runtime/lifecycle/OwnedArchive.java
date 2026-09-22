package com.lelloman.paravoidandroid.runtime.lifecycle;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/** Private byte-ownership primitive. Does not authenticate, admit, select or extract a VPK. */
final class OwnedArchive {
    static final long LIMIT = 1024L * 1024 * 1024;
    interface Verification { void verify(Path ownedBytes) throws IOException; }
    final Path path;
    final long size;
    private final byte[] digest;

    private OwnedArchive(Path path, long size, byte[] digest) {
        this.path = path; this.size = size; this.digest = digest.clone();
    }

    /** Transfer and verification run with no selection lock. Caller owns and closes input. */
    static OwnedArchive prepare(Path staging, InputStream input, long expectedSize,
            byte[] expectedDigest, Verification verification) throws IOException {
        ProcessLocks.requireOutsideSelection();
        if (expectedSize < 1 || expectedSize > LIMIT || expectedDigest.length != 32)
            throw new IOException("Invalid archive bounds");
        byte[] expected = expectedDigest.clone();
        Path temporary = Files.createTempFile(staging, "owned-", ".vpk");
        boolean success = false;
        try {
            MessageDigest hash = sha256();
            try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[8192];
                long total = 0;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > expectedSize) throw new IOException("Archive exceeds signed size");
                    hash.update(buffer, 0, count);
                    ByteBuffer bytes = ByteBuffer.wrap(buffer, 0, count);
                    while (bytes.hasRemaining()) output.write(bytes);
                }
                if (total != expectedSize || !MessageDigest.isEqual(hash.digest(), expected))
                    throw new IOException("Archive identity mismatch");
                Files.setPosixFilePermissions(temporary, EnumSet.of(PosixFilePermission.OWNER_READ));
                output.force(true);
            }
            verification.verify(temporary);
            AtomicRecord.syncDirectory(staging);
            success = true;
            return new OwnedArchive(temporary, expectedSize, expected);
        } finally { if (!success) Files.deleteIfExists(temporary); }
    }

    /** Caller holds selection lock. Unique destinations are never overwritten or activated. */
    OwnedArchive publish(Path accepted) throws IOException {
        return publish(accepted, boundary -> {});
    }

    OwnedArchive publish(Path accepted, AtomicRecord.Fault fault) throws IOException {
        ProcessLocks.requireSelection();
        Path target = accepted.resolve(UUID.randomUUID() + ".vpk");
        fault.at("before-publication");
        Files.move(path, target, StandardCopyOption.ATOMIC_MOVE);
        fault.at("archive-renamed");
        AtomicRecord.syncDirectory(accepted);
        if (!path.getParent().equals(accepted)) AtomicRecord.syncDirectory(path.getParent());
        fault.at("archive-synced");
        return new OwnedArchive(target, size, digest);
    }

    /** Caller has acquired a generation lease before calling. Never trust a prior verification. */
    void reverify(Verification verification) throws IOException {
        ProcessLocks.requireOutsideSelection();
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) != size)
            throw new IOException("Missing or invalid owned archive");
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
        if (permissions.contains(PosixFilePermission.OWNER_WRITE) || permissions.contains(PosixFilePermission.GROUP_WRITE)
                || permissions.contains(PosixFilePermission.OTHERS_WRITE)) throw new IOException("Writable owned archive");
        MessageDigest hash = sha256();
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > size) throw new IOException("Growing owned archive");
                hash.update(buffer, 0, count);
            }
            if (total != size || !MessageDigest.isEqual(hash.digest(), digest))
                throw new IOException("Owned archive identity mismatch");
        }
        verification.verify(path);
    }

    /** Under selection lock, with protections read from the journal in the same transaction. */
    boolean removeIfUnprotected(Set<Path> protectedArchives, Path permanentLease) throws IOException {
        ProcessLocks.requireSelection();
        if (protectedArchives.contains(path)) return false;
        return ProcessLocks.ifUnleased(permanentLease, () -> {
            Files.deleteIfExists(path);
            AtomicRecord.syncDirectory(path.getParent());
            return null;
        });
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
