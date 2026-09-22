package com.lelloman.paravoidandroid.runtime.lifecycle;

import com.lelloman.paravoidandroid.contract.*;
import com.lelloman.paravoidandroid.contract.ContractException.Code;
import com.lelloman.paravoidandroid.contract.Protocol.*;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Materializes only inventory already authenticated by the mandatory shared VPK verifier. */
final class GenerationStore {
    static final class Prepared {
        final Path directory; final VerifiedRelease release;
        Prepared(Path directory, VerifiedRelease release) { this.directory = directory; this.release = release; }
    }
    static final class Loaded {
        final VerifiedRelease release; final GenerationFiles files;
        Loaded(VerifiedRelease release, GenerationFiles files) { this.release = release; this.files = files; }
    }
    private final Path root;
    private final ShellPolicy policy;
    private final RequestScope device;
    private final VpkVerifier verifier;
    GenerationStore(Path root, ShellPolicy policy, RequestScope device, VpkVerifier verifier) {
        this.root = root; this.policy = policy; this.device = device; this.verifier = Objects.requireNonNull(verifier);
    }
    Prepared prepare(File source, ExpectedArchive expected) throws ContractException {
        ProcessLocks.requireOutsideSelection();
        Path staging = null;
        try {
            // Reservation is serialized by the facade's preparation lock. No selection lock is held.
            long sourceSize = expected == null ? Files.size(source.toPath()) : expected.archiveSize;
            long required = Math.addExact(Math.multiplyExact(sourceSize, 2), 64L * 1024 * 1024);
            if (sourceSize < 1 || sourceSize > Protocol.MAX_ARCHIVE_BYTES) throw fail(Code.LIMIT_EXCEEDED);
            if (Files.getFileStore(root).getUsableSpace() < required) throw fail(Code.INSUFFICIENT_STORAGE);
            staging = Files.createTempDirectory(root.resolve("staging"), "generation-");
            Path archive = staging.resolve("archive.vpk");
            try (InputStream input = Files.newInputStream(source.toPath())) {
                copy(input, archive, sourceSize, expected == null ? null : expected.archiveSha256);
            }
            VerifiedRelease release = expected == null
                ? verifier.verifyEmbedded(archive.toFile(), policy, device)
                : verifier.verifyDownloaded(archive.toFile(), policy, device, expected);
            if (expected != null && !expected.equals(release.identity)) throw fail(Code.INTEGRITY);
            long materialized = 0;
            for (InventoryEntry entry : release.inventory) {
                materialized = Math.addExact(materialized, entry.size);
                if (entry.size < 0 || materialized > 2L * 1024 * 1024 * 1024) throw fail(Code.LIMIT_EXCEEDED);
            }
            if (Files.getFileStore(root).getUsableSpace() < materialized + 64L * 1024 * 1024)
                throw fail(Code.INSUFFICIENT_STORAGE);
            Path components = Files.createDirectory(staging.resolve("components"));
            try (ZipFile zip = new ZipFile(archive.toFile())) {
                for (InventoryEntry entry : release.inventory) {
                    Path target = component(components, entry.path);
                    Files.createDirectories(target.getParent());
                    ZipEntry zipped = zip.getEntry(entry.path);
                    if (zipped == null) throw fail(Code.INTEGRITY);
                    try (InputStream input = zip.getInputStream(zipped)) { copy(input, target, entry.size, entry.sha256); }
                }
            }
            try (java.util.stream.Stream<Path> paths = Files.walk(staging)) {
                for (Path directory : (Iterable<Path>) paths.filter(Files::isDirectory).sorted(Comparator.reverseOrder())::iterator) {
                    // Renaming across parents needs write permission on the moved directory.
                    if (!directory.equals(staging)) Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("r-x------"));
                    AtomicRecord.syncDirectory(directory);
                }
            }
            AtomicRecord.syncDirectory(root.resolve("staging"));
            return new Prepared(staging, release);
        } catch (IOException | ArithmeticException e) { throw fail(Code.IO); }
    }
    SelectionJournal.Generation publish(Prepared prepared) throws IOException {
        ProcessLocks.requireSelection();
        String name = UUID.randomUUID().toString();
        Path destination = root.resolve("generations").resolve(name);
        Files.move(prepared.directory, destination, StandardCopyOption.ATOMIC_MOVE);
        Files.setPosixFilePermissions(destination, PosixFilePermissions.fromString("r-x------"));
        AtomicRecord.syncDirectory(destination);
        AtomicRecord.syncDirectory(destination.getParent()); AtomicRecord.syncDirectory(root.resolve("staging"));
        return new SelectionJournal.Generation(name, prepared.release.identity);
    }
    Loaded load(SelectionJournal.Generation generation) throws ContractException {
        ProcessLocks.requireOutsideSelection();
        Path directory = root.resolve("generations").resolve(generation.directory);
        Path archive = directory.resolve("archive.vpk");
        try {
            immutable(archive);
            VerifiedRelease release = verifier.verifyRetained(archive.toFile(), policy, device, generation.identity);
            if (!generation.identity.equals(release.identity)) throw fail(Code.INTEGRITY);
            Path components = directory.resolve("components");
            for (InventoryEntry entry : release.inventory) verify(component(components, entry.path), entry.size, entry.sha256);
            Set<String> inventory = new HashSet<>();
            for (InventoryEntry entry : release.inventory) inventory.add(entry.path);
            List<File> dex = new ArrayList<>();
            if (!inventory.contains("code/classes.dex") || !inventory.contains("resources.apk")
                    || !inventory.contains("java-resources.jar")) throw fail(Code.INTEGRITY);
            dex.add(components.resolve("code/classes.dex").toFile());
            for (int n = 2; inventory.contains("code/classes" + n + ".dex"); n++)
                dex.add(components.resolve("code/classes" + n + ".dex").toFile());
            File nativeDirectory = null;
            if (!release.abis.isEmpty()) {
                for (String abi : device.abis) if (release.abis.contains(abi)) {
                    nativeDirectory = components.resolve("native").resolve(abi).toFile(); break;
                }
                if (nativeDirectory == null || !nativeDirectory.isDirectory()) throw fail(Code.INCOMPATIBLE);
            }
            return new Loaded(release, new GenerationFiles(dex, components.resolve("resources.apk").toFile(),
                components.resolve("java-resources.jar").toFile(), nativeDirectory));
        } catch (IOException e) { throw fail(Code.INTEGRITY); }
    }
    private static Path component(Path root, String name) throws ContractException {
        Path path = root.resolve(name).normalize();
        if (!path.startsWith(root) || path.equals(root)) throw fail(Code.INTEGRITY);
        return path;
    }
    private static void immutable(Path path) throws IOException, ContractException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.getPosixFilePermissions(path).stream().anyMatch(p -> p.name().endsWith("WRITE")))
            throw fail(Code.INTEGRITY);
    }
    private static void verify(Path path, long size, String hash) throws IOException, ContractException {
        immutable(path);
        try (InputStream input = Files.newInputStream(path)) { digest(input, null, size, hash); }
    }
    private static void copy(InputStream input, Path target, long size, String hash) throws IOException, ContractException {
        try (FileChannel file = FileChannel.open(target, StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW)) {
            digest(input, java.nio.channels.Channels.newOutputStream(file), size, hash);
            Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("r--------"));
            file.force(true);
        }
    }
    private static void digest(InputStream input, OutputStream output, long size, String expected) throws IOException, ContractException {
        MessageDigest hash;
        try { hash = MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
        byte[] buffer = new byte[8192]; long total = 0; int count;
        while ((count = input.read(buffer)) != -1) {
            total += count; if (total > size) throw fail(Code.INTEGRITY);
            hash.update(buffer, 0, count); if (output != null) output.write(buffer, 0, count);
        }
        if (total != size) throw fail(Code.INTEGRITY);
        StringBuilder actual = new StringBuilder(); for (byte b : hash.digest()) actual.append(String.format(Locale.ROOT, "%02x", b & 255));
        if (expected != null && !expected.contentEquals(actual)) throw fail(Code.INTEGRITY);
    }
    private static ContractException fail(Code code) { return new ContractException(code, "Generation storage: " + code.name()); }
}
