package com.lelloman.paravoidandroid.contract;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.Arrays;
import java.util.zip.*;
import com.lelloman.paravoidandroid.contract.Protocol.*;

/** Bounded streaming decoder. Output is never executable authority: stage through Lifecycle afterward. */
public final class DeltaPatch {
    public static final String ALGORITHM = "bsdiff-deflate-v1";
    public static final int MAX_OPERATIONS = 1_000_000;
    private static final byte[] MAGIC = {'D','V','P','K','D','0','0','1'};
    @FunctionalInterface public interface Cancellation { void check() throws IOException; }
    private DeltaPatch() {}

    /** Creates a new destination, fsyncs verified target bytes, and removes its own output on failure. */
    public static void apply(File patch, DeltaBase base, File destination, ExpectedDelta delta,
            ExpectedArchive target, Cancellation cancel) throws IOException {
        if (!ALGORITHM.equals(delta.algorithm) || delta.patchSize < 38 || delta.patchSize > Protocol.MAX_DELTA_BYTES
                || delta.baseArchiveSize < 1 || delta.baseArchiveSize > Protocol.MAX_ARCHIVE_BYTES
                || target.archiveSize < 1 || target.archiveSize > Protocol.MAX_ARCHIVE_BYTES
                || !base.identity().archiveSha256.equals(delta.baseArchiveSha256)
                || base.identity().archiveSize != delta.baseArchiveSize) throw invalid();
        if (patch.length() != delta.patchSize || !hash(patch, delta.patchSize, cancel).equals(delta.patchSha256)) throw invalid();
        MessageDigest baseHash = sha256();
        byte[] bytes = new byte[8192];
        for (long position = 0; position < delta.baseArchiveSize;) {
            cancel.check();
            int count = base.read(position, bytes, 0, (int)Math.min(bytes.length, delta.baseArchiveSize-position));
            if (count <= 0) throw invalid();
            baseHash.update(bytes, 0, count); position += count;
        }
        if (base.read(delta.baseArchiveSize, bytes, 0, 1) != -1 || !hex(baseHash.digest()).equals(delta.baseArchiveSha256)) throw invalid();
        long controls, differences;
        try (RandomAccessFile header = new RandomAccessFile(patch, "r")) {
            byte[] magic = new byte[8]; header.readFully(magic);
            if (!Arrays.equals(magic, MAGIC)) throw invalid();
            controls = integer(header); differences = integer(header);
            if (integer(header) != target.archiveSize || controls < 2 || differences < 2
                    || controls > delta.patchSize-36 || differences > delta.patchSize-34-controls) throw invalid();
        }
        boolean created = false, complete = false;
        try (Block control = new Block(patch, 32, controls);
                Block diff = new Block(patch, 32+controls, differences);
                Block extra = new Block(patch, 32+controls+differences, delta.patchSize-32-controls-differences)) {
            try (OutputStream output = Files.newOutputStream(destination.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                created = true;
                MessageDigest targetHash = sha256();
                byte[] old = new byte[8192];
                long written = 0, oldPosition = 0;
                int operations = 0;
                while (written < target.archiveSize) {
                    cancel.check();
                    if (++operations > MAX_OPERATIONS) throw invalid();
                    long add = integer(control), copy = integer(control), seek = integer(control);
                    if (add < 0 || copy < 0 || add > target.archiveSize-written || copy > target.archiveSize-written-add
                            || add == 0 && copy == 0 && seek == 0) throw invalid();
                    for (long left = add; left > 0;) {
                        cancel.check(); int count = (int)Math.min(left, bytes.length);
                        exact(diff, bytes, count); Arrays.fill(old, 0, count, (byte)0);
                        long end = Math.addExact(oldPosition, count);
                        long start = Math.max(0, oldPosition), stop = Math.min(delta.baseArchiveSize, end);
                        if (stop > start) {
                            int offset = (int)(start-oldPosition), length = (int)(stop-start), done = 0;
                            while (done < length) {
                                int read = base.read(start+done, old, offset+done, length-done);
                                if (read <= 0) throw invalid(); done += read;
                            }
                        }
                        for (int i = 0; i < count; i++) bytes[i] = (byte)(bytes[i]+old[i]);
                        output.write(bytes, 0, count); targetHash.update(bytes, 0, count);
                        oldPosition = end; written += count; left -= count;
                    }
                    for (long left = copy; left > 0;) {
                        cancel.check(); int count = (int)Math.min(left, bytes.length);
                        exact(extra, bytes, count); output.write(bytes, 0, count); targetHash.update(bytes, 0, count);
                        written += count; left -= count;
                    }
                    oldPosition = Math.addExact(oldPosition, seek);
                }
                control.finish(); diff.finish(); extra.finish();
                if (!hex(targetHash.digest()).equals(target.archiveSha256)) throw invalid();
            }
            try (java.nio.channels.FileChannel output = java.nio.channels.FileChannel.open(destination.toPath(), StandardOpenOption.WRITE)) { output.force(true); }
            cancel.check(); complete = true;
        } catch (ArithmeticException overflow) { complete = false; throw invalid(); }
        catch (IOException failure) { complete = false; throw failure; }
        finally { if (created && !complete) Files.deleteIfExists(destination.toPath()); }
    }
    private static void exact(InputStream input, byte[] bytes, int size) throws IOException {
        int count = 0;
        while (count < size) { int read = input.read(bytes, count, size-count); if (read <= 0) throw invalid(); count += read; }
    }
    private static long integer(DataInput input) throws IOException {
        long value = 0; for (int i = 0; i < 8; i++) value |= (long)input.readUnsignedByte() << (8*i); return value;
    }
    private static long integer(InputStream input) throws IOException { return integer((DataInput)new DataInputStream(input)); }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static String hash(File file, long size, Cancellation cancel) throws IOException {
        MessageDigest hash = sha256(); byte[] bytes = new byte[8192];
        long total = 0;
        try (InputStream input = new FileInputStream(file)) {
            int read; while ((read = input.read(bytes)) != -1) { cancel.check(); total += read; if (total > size) throw invalid(); hash.update(bytes, 0, read); }
        }
        if (total != size) throw invalid();
        return hex(hash.digest());
    }
    private static String hex(byte[] digest) {
        StringBuilder out = new StringBuilder(64); for (byte b : digest) out.append(Character.forDigit((b>>>4)&15,16)).append(Character.forDigit(b&15,16)); return out.toString();
    }
    private static IOException invalid() { return new IOException("Invalid delta archive"); }
    private static final class Segment extends InputStream {
        final RandomAccessFile file; long remaining;
        Segment(File source, long start, long size) throws IOException { file = new RandomAccessFile(source, "r"); file.seek(start); remaining = size; }
        @Override public int read() throws IOException { byte[] one = new byte[1]; return read(one,0,1)==-1 ? -1 : one[0]&255; }
        @Override public int read(byte[] bytes, int offset, int size) throws IOException {
            if (size == 0) return 0; if (remaining == 0) return -1;
            int read = file.read(bytes, offset, (int)Math.min(size,remaining)); if (read < 0) throw invalid(); remaining -= read; return read;
        }
        @Override public void close() throws IOException { file.close(); }
    }
    private static final class Block extends InflaterInputStream {
        private final Segment segment;
        Block(File source, long start, long size) throws IOException { this(new Segment(source,start,size)); }
        Block(Segment segment) { super(segment, new Inflater(true), 8192); this.segment = segment; }
        void finish() throws IOException {
            if (read() != -1 || !inf.finished() || inf.getRemaining() != 0 || segment.remaining != 0) throw invalid();
        }
        @Override public void close() throws IOException { try { super.close(); } finally { inf.end(); } }
    }
}
