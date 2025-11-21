package com.kyopan_pan.ytdownloader;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

public class DependencyManager {

    public void ensureBinaries() {
        File binDir = new File(DownloadConfig.BIN_DIR);
        if (!binDir.exists()) {
            binDir.mkdirs();
        }

        try {
            // 1. yt-dlp の準備
            File ytDlp = new File(DownloadConfig.getYtDlpPath());
            if (!ytDlp.exists()) {
                System.out.println("yt-dlp not found. Downloading...");
                downloadYtDlp(ytDlp);
            }

            // 2. ffmpeg の準備 (リソースからコピー)
            File ffmpeg = new File(DownloadConfig.getFfmpegPath());
            if (!ffmpeg.exists()) {
                System.out.println("ffmpeg not found. Extracting from resources...");
                copyFfmpegFromResources(ffmpeg);
            }

        } catch (Exception e) {
            e.printStackTrace();
            // エラーダイアログなどを出すのが理想的
        }
    }

    private void downloadYtDlp(File destination) throws IOException {
        // Mac用のバイナリURL
        String downloadUrl = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_macos";

        try (InputStream in = URI.create(downloadUrl).toURL().openStream()) {
            Files.copy(in, destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
            makeExecutable(destination.toPath());
            System.out.println("yt-dlp ready.");
        }
    }

    private void copyFfmpegFromResources(File destination) throws IOException {
        // src/main/resources/bin/ffmpeg を参照します
        try (InputStream in = getClass().getResourceAsStream("/bin/ffmpeg")) {
            if (in == null) {
                throw new FileNotFoundException("FFmpeg binary not found in resources! Please put 'ffmpeg' in src/main/resources/bin/");
            }
            Files.copy(in, destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
            makeExecutable(destination.toPath());
            System.out.println("ffmpeg ready.");
        }
    }

    private void makeExecutable(Path path) throws IOException {
        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(path);
        perms.add(PosixFilePermission.OWNER_EXECUTE);
        perms.add(PosixFilePermission.GROUP_EXECUTE);
        perms.add(PosixFilePermission.OTHERS_EXECUTE);
        Files.setPosixFilePermissions(path, perms);
    }
}
