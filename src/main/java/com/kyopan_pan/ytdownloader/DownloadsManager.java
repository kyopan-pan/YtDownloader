package com.kyopan_pan.ytdownloader;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class DownloadsManager {

    public void ensureDownloadDirectory() {
        try {
            Files.createDirectories(Paths.get(DownloadConfig.getDownloadDir()));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public List<File> loadRecentVideos() {
        try {
            return Files.list(Paths.get(DownloadConfig.getDownloadDir()))
                    .filter(Files::isRegularFile)
                    .map(Path::toFile)
                    .filter(f -> f.getName().endsWith(".mp4"))
                    .sorted(Comparator.comparingLong(File::lastModified).reversed())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            e.printStackTrace();
            return Collections.emptyList();
        }
    }

    public boolean deleteFile(File target) {
        if (target == null || !target.exists()) {
            return false;
        }
        boolean removed = target.delete();
        if (!removed) {
            System.err.println("Failed to delete file: " + target.getAbsolutePath());
        }
        return removed;
    }
}
