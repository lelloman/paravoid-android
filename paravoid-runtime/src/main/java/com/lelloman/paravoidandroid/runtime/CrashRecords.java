package com.lelloman.paravoidandroid.runtime;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.util.*;

/** Bounded shell-owned records. Separate unique files avoid locks on the crash path. */
final class CrashRecords {
    static final int LIMIT = 64 * 1024;
    final Path directory;
    CrashRecords(File directory) { this.directory = directory.toPath(); }
    Path record(String identity, String process, String thread, Throwable failure) throws IOException {
        return record(identity,"",process,thread,failure);
    }
    Path record(String identity, String context, String process, String thread, Throwable failure) throws IOException {
        Files.createDirectories(directory);
        String name = System.currentTimeMillis() + "-" + UUID.randomUUID();
        Path marker = directory.resolve(name + ".pending");
        // Even a truncated detail write leaves a durable recovery marker.
        atomic(marker, (identity + "\nCrash details unavailable.").getBytes(StandardCharsets.UTF_8));
        try {
            StringBuilder text = new StringBuilder(identity).append('\n')
                .append(shortText(context)).append('\n').append("Time: ").append(new Date()).append("\nProcess: ").append(shortText(process))
                .append("\nThread: ").append(shortText(thread)).append('\n');
            Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            for (int n=0; failure != null && n<8 && seen.add(failure) && text.length()<16000; n++, failure=failure.getCause()) {
                text.append(n==0 ? "" : "Caused by: ").append(failure.getClass().getName())
                    .append(": ").append(shortText(failure.getMessage())).append('\n');
                StackTraceElement[] stack=failure.getStackTrace();
                for (int i=0; i<Math.min(stack.length,128) && text.length()<16000; i++)
                    text.append("  at ").append(shortText(stack[i].toString())).append('\n');
            }
            byte[] bytes=text.toString().getBytes(StandardCharsets.UTF_8);
            atomic(marker, bytes.length<=LIMIT ? bytes : Arrays.copyOf(bytes,LIMIT));
        } catch (Throwable unavailable) { /* Keep the minimal durable record. */ }
        prune();
        return marker;
    }
    private static String shortText(String text) { return text == null ? "" : text.substring(0, Math.min(text.length(),2048)); }
    List<Path> records() throws IOException {
        if (!Files.exists(directory)) return Collections.emptyList();
        List<Path> result=new ArrayList<>();
        try (DirectoryStream<Path> stream=Files.newDirectoryStream(directory)) {
            for (Path p:stream) if (p.getFileName().toString().matches("[0-9]+-[a-f0-9-]+\\.(pending|handled)")) result.add(p);
        }
        result.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return result;
    }
    String read(Path p) throws IOException {
        if (Files.isSymbolicLink(p) || Files.size(p)>LIMIT) return "unknown\nCrash record is damaged.";
        try (InputStream input=Files.newInputStream(p)) {
            byte[] bytes=new byte[LIMIT+1]; int count=0, n;
            while(count<bytes.length && (n=input.read(bytes,count,bytes.length-count))!=-1) count+=n;
            return count>LIMIT ? "unknown\nCrash record is damaged." : new String(bytes,0,count,StandardCharsets.UTF_8);
        }
    }
    boolean pending(String identity) throws IOException {
        for (Path p:records()) if (p.toString().endsWith(".pending")) {
            String text=read(p); int end=text.indexOf('\n');
            String id=end<0 ? "unknown" : text.substring(0,end);
            if (id.equals("unknown") || identity.equals("unknown") || id.equals(identity)) return true;
        }
        return false;
    }
    String details() {
        try { List<Path> all=records(); return all.isEmpty() ? "No crash report recorded." : read(all.get(all.size()-1)); }
        catch (IOException error) { return "Crash details could not be read."; }
    }
    void acknowledge() throws IOException {
        for (Path p:records()) if (p.toString().endsWith(".pending"))
            Files.move(p, p.resolveSibling(p.getFileName().toString().replace(".pending", ".handled")), StandardCopyOption.ATOMIC_MOVE);
        syncDirectory();
    }
    boolean providerInterrupted() { return Files.exists(directory.resolve("provider-running")); }
    void providerStarted() throws IOException { atomic(directory.resolve("provider-running"), new byte[]{1}); }
    void providerFinished() throws IOException { Files.deleteIfExists(directory.resolve("provider-running")); syncDirectory(); }
    private void prune() throws IOException {
        List<Path> all=records();
        for (int i=0;i<all.size()-4;i++) Files.deleteIfExists(all.get(i));
    }
    private void atomic(Path destination, byte[] bytes) throws IOException {
        Files.createDirectories(directory);
        Path temp=directory.resolve(destination.getFileName()+".tmp");
        try {
            try (FileOutputStream output=new FileOutputStream(temp.toFile())) { output.write(bytes); output.getFD().sync(); }
            Files.move(temp,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            syncDirectory();
        } finally { Files.deleteIfExists(temp); }
    }
    private void syncDirectory() throws IOException {
        if (!Files.exists(directory)) return;
        try (FileChannel channel=FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
    }
}
