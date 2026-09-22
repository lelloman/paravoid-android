package com.lelloman.paravoidandroid.runtime.lifecycle;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;

/** Shell-UID emulator harness only. Does not claim app-component or app-sandbox acceptance. */
public final class AndroidPrimitiveProbe {
    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[1]); Files.createDirectories(root);
        Path selection = root.resolve("selection.lock"), lease = root.resolve("generation.lock");
        AtomicRecord record = new AtomicRecord(root.resolve("record"));
        switch (args[0]) {
            case "hold":
                ProcessLocks.selection(selection, () -> { ProcessLocks.leaseForProcess(lease); return null; });
                String pid = new String(Files.readAllBytes(Paths.get("/proc/self/stat")), StandardCharsets.US_ASCII).split(" ")[0];
                System.out.println("LEASED " + pid); System.out.flush();
                Thread.sleep(60000); return;
            case "probe":
                System.out.println(ProcessLocks.selection(selection, () -> ProcessLocks.ifUnleased(lease, () -> null)) ? "FREE" : "BUSY"); return;
            case "write": record.write(new byte[] {1}); System.out.println("WROTE"); return;
            case "crash":
                new AtomicRecord(root.resolve("record"), boundary -> {
                    if (boundary.equals(args[2])) Runtime.getRuntime().halt(73);
                }).write(new byte[] {2}); return;
            case "read": System.out.println("VALUE " + record.read()[0]); return;
            case "increment":
                for (int i = 0; i < 30; i++) ProcessLocks.selection(selection, () -> {
                    byte[] value = record.read(); value[0]++; record.write(value); return null;
                });
                System.out.println("INCREMENTED"); return;
            default: throw new IllegalArgumentException("Unknown probe");
        }
    }
}
