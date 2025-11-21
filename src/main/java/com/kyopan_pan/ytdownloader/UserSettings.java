package com.kyopan_pan.ytdownloader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

public final class UserSettings {

    private static final double DEFAULT_WIDTH = 300;
    private static final double DEFAULT_HEIGHT = 1000;
    private static final double MIN_WIDTH = 260;
    private static final double MIN_HEIGHT = 320;
    private static final String SETTINGS_FILE_NAME = "settings.properties";

    private double windowWidth;
    private double windowHeight;
    private String downloadDirectory;

    private UserSettings(double windowWidth, double windowHeight, String downloadDirectory) {
        this.windowWidth = windowWidth;
        this.windowHeight = windowHeight;
        this.downloadDirectory = downloadDirectory;
    }

    public static UserSettings load() {
        Properties props = new Properties();
        Path file = settingsFile();
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
            } catch (IOException e) {
                System.err.println("Failed to load settings. Using defaults. reason=" + e.getMessage());
            }
        }
        double width = parseDimension(props.getProperty("window.width"), DEFAULT_WIDTH, MIN_WIDTH);
        double height = parseDimension(props.getProperty("window.height"), DEFAULT_HEIGHT, MIN_HEIGHT);
        String dir = normalizeDir(props.getProperty("download.dir", DownloadConfig.getDefaultDownloadDir()));
        DownloadConfig.setDownloadDir(dir);
        return new UserSettings(width, height, dir);
    }

    public void save() {
        Properties props = new Properties();
        props.setProperty("window.width", String.valueOf(windowWidth));
        props.setProperty("window.height", String.valueOf(windowHeight));
        props.setProperty("download.dir", downloadDirectory);

        Path file = settingsFile();
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(out, "YT Downloader user settings");
            }
        } catch (IOException e) {
            System.err.println("Failed to save settings: " + e.getMessage());
        }
    }

    public double getWindowWidth() {
        return windowWidth;
    }

    public void setWindowWidth(double windowWidth) {
        this.windowWidth = Math.max(MIN_WIDTH, windowWidth);
    }

    public double getWindowHeight() {
        return windowHeight;
    }

    public void setWindowHeight(double windowHeight) {
        this.windowHeight = Math.max(MIN_HEIGHT, windowHeight);
    }

    public String getDownloadDirectory() {
        return downloadDirectory;
    }

    public void setDownloadDirectory(String downloadDirectory) {
        this.downloadDirectory = normalizeDir(downloadDirectory);
        DownloadConfig.setDownloadDir(this.downloadDirectory);
    }

    private static Path settingsFile() {
        return Paths.get(DownloadConfig.APP_DATA_DIR, SETTINGS_FILE_NAME);
    }

    private static double parseDimension(String raw, double fallback, double min) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            double value = Double.parseDouble(raw.trim());
            return Math.max(min, value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String normalizeDir(String dir) {
        if (dir == null || dir.isBlank()) {
            return DownloadConfig.getDefaultDownloadDir();
        }
        return Paths.get(dir.trim()).toAbsolutePath().toString();
    }
}
