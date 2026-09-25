package com.lelloman.paravoidandroid.delivery;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;
import java.nio.channels.*;
import java.util.function.UnaryOperator;

/** Non-security preferences only; store beneath Android's noBackupFilesDir. */
public final class DeliveryPreferences {
    public final boolean automaticChecks, automaticDownloads, unmeteredOnly;
    final long lastAutomaticCheckSeconds;
    final String credentialPartition;
    public DeliveryPreferences(boolean checks, boolean downloads, boolean unmetered) {
        this(checks, downloads, unmetered, 0);
    }
    DeliveryPreferences(boolean checks, boolean downloads, boolean unmetered, long lastCheck) {
        this(checks, downloads, unmetered, lastCheck, null);
    }
    DeliveryPreferences(boolean checks, boolean downloads, boolean unmetered, long lastCheck, String partition) {
        if (lastCheck < 0 || partition != null && !partition.matches("[0-9a-f]{64}")) throw new IllegalArgumentException();
        credentialPartition = partition;
        automaticChecks = checks; automaticDownloads = downloads; unmeteredOnly = unmetered;
        lastAutomaticCheckSeconds = lastCheck;
    }
    public static DeliveryPreferences read(Path path) throws IOException {
        if (!Files.exists(path)) return new DeliveryPreferences(true, true, true);
        if (Files.size(path) > 4096) throw new IOException("Invalid update preferences");
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) { properties.load(input); }
        try {
            return new DeliveryPreferences(flag(properties, "checks", true), flag(properties, "downloads", true),
                    flag(properties, "unmetered", false), Long.parseLong(properties.getProperty("lastAutomaticCheck", "0")), properties.getProperty("credentialPartition"));
        } catch (IllegalArgumentException invalid) { throw new IOException("Invalid update preferences"); }
    }
    private static boolean flag(Properties properties, String name, boolean fallback) {
        String value = properties.getProperty(name);
        if (value == null) return fallback;
        if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException();
        return Boolean.parseBoolean(value);
    }
    /** Serialize read/modify/write across both processes and controllers in one VM. */
    static synchronized DeliveryPreferences update(Path path, UnaryOperator<DeliveryPreferences> change) throws IOException {
        Files.createDirectories(path.getParent());
        try (FileChannel channel = FileChannel.open(path.resolveSibling(path.getFileName() + ".lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE); FileLock lock = channel.lock()) {
            if (!lock.isValid()) throw new IOException("Preference lock unavailable");
            DeliveryPreferences result = change.apply(read(path));
            result.write(path);
            return result;
        }
    }
    void write(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Properties properties = new Properties();
        properties.setProperty("checks", "" + automaticChecks);
        properties.setProperty("downloads", "" + automaticDownloads);
        properties.setProperty("unmetered", "" + unmeteredOnly);
        properties.setProperty("lastAutomaticCheck", "" + lastAutomaticCheckSeconds);
        if (credentialPartition != null) properties.setProperty("credentialPartition", credentialPartition);
        Path temporary = Files.createTempFile(path.getParent(), "preferences", ".tmp");
        try {
            try (FileOutputStream output = new FileOutputStream(temporary.toFile())) {
                properties.store(output, "Paravoid delivery preferences; no credentials or replay history");
                output.getFD().sync();
            }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
}
