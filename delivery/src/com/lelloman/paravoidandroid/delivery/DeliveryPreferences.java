package com.lelloman.paravoidandroid.delivery;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

/** Non-security preferences only; store beneath Android's noBackupFilesDir. */
public final class DeliveryPreferences {
    public final boolean automaticChecks, automaticDownloads, unmeteredOnly;
    final long lastAutomaticCheckSeconds;
    public DeliveryPreferences(boolean checks, boolean downloads, boolean unmetered) {
        this(checks, downloads, unmetered, 0);
    }
    DeliveryPreferences(boolean checks, boolean downloads, boolean unmetered, long lastCheck) {
        automaticChecks = checks; automaticDownloads = downloads; unmeteredOnly = unmetered;
        lastAutomaticCheckSeconds = lastCheck;
    }
    static DeliveryPreferences read(Path path) throws IOException {
        if (!Files.exists(path)) return new DeliveryPreferences(true, true, false);
        if (Files.size(path) > 4096) throw new IOException("Invalid update preferences");
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) { properties.load(input); }
        try {
            return new DeliveryPreferences(flag(properties, "checks", true), flag(properties, "downloads", true),
                    flag(properties, "unmetered", false), Long.parseLong(properties.getProperty("lastAutomaticCheck", "0")));
        } catch (IllegalArgumentException invalid) { throw new IOException("Invalid update preferences"); }
    }
    private static boolean flag(Properties properties, String name, boolean fallback) {
        String value = properties.getProperty(name);
        if (value == null) return fallback;
        if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException();
        return Boolean.parseBoolean(value);
    }
    void write(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Properties properties = new Properties();
        properties.setProperty("checks", "" + automaticChecks);
        properties.setProperty("downloads", "" + automaticDownloads);
        properties.setProperty("unmetered", "" + unmeteredOnly);
        properties.setProperty("lastAutomaticCheck", "" + lastAutomaticCheckSeconds);
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
