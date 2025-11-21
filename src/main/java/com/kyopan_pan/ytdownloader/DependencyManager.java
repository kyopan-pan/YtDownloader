package com.kyopan_pan.ytdownloader;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.charset.StandardCharsets;
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
                AppLogger.log("[DependencyManager] yt-dlp not found. Downloading...");
                downloadYtDlp(ytDlp);
            }

            // 2. ffmpeg の準備 (リソースからコピー)
            File ffmpeg = new File(DownloadConfig.getFfmpegPath());
            if (!ffmpeg.exists()) {
                AppLogger.log("[DependencyManager] ffmpeg not found. Extracting from resources...");
                copyFfmpegFromResources(ffmpeg);
            }

        } catch (Exception e) {
            e.printStackTrace();
            // エラーダイアログなどを出すのが理想的
        }
    }

    public YtDlpVersionResult getYtDlpVersion() {
        File ytDlp = new File(DownloadConfig.getYtDlpPath());
        if (!ytDlp.exists()) {
            return new YtDlpVersionResult(false, null, "yt-dlpが見つかりません。");
        }

        ProcessBuilder pb = new ProcessBuilder(ytDlp.getAbsolutePath(), "--version");
        appendBinToPath(pb);
        pb.redirectErrorStream(true);

        try {
            Process process = pb.start();
            String firstLine;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                firstLine = reader.readLine();
            }
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                return new YtDlpVersionResult(false, null, "バージョン取得に失敗 (exit=" + exitCode + ")");
            }
            if (firstLine == null || firstLine.isBlank()) {
                return new YtDlpVersionResult(false, null, "バージョン情報が空でした。");
            }
            return new YtDlpVersionResult(true, firstLine.trim(), "yt-dlpのバージョンを取得しました。");
        } catch (Exception e) {
            return new YtDlpVersionResult(false, null, "バージョン取得に失敗: " + e.getMessage());
        }
    }

    public YtDlpUpdateResult updateYtDlp() {
        File binDir = new File(DownloadConfig.BIN_DIR);
        if (!binDir.exists() && !binDir.mkdirs()) {
            return new YtDlpUpdateResult(false, "binフォルダを作成できませんでした。");
        }
        File ytDlp = new File(DownloadConfig.getYtDlpPath());
        try {
            downloadYtDlp(ytDlp);
            return new YtDlpUpdateResult(true, "yt-dlpを更新しました。");
        } catch (IOException e) {
            return new YtDlpUpdateResult(false, "yt-dlpの更新に失敗: " + e.getMessage());
        }
    }

    public record YtDlpVersionResult(boolean success, String version, String message) {
    }

    public record YtDlpUpdateResult(boolean success, String message) {
    }

    private void downloadYtDlp(File destination) throws IOException {
        // Mac用のバイナリURL
        String downloadUrl = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_macos";

        try (InputStream in = URI.create(downloadUrl).toURL().openStream()) {
            Files.copy(in, destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
            makeExecutable(destination.toPath());
            AppLogger.log("[DependencyManager] yt-dlp ready.");
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
            AppLogger.log("[DependencyManager] ffmpeg ready.");
        }
    }

    private void makeExecutable(Path path) throws IOException {
        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(path);
        perms.add(PosixFilePermission.OWNER_EXECUTE);
        perms.add(PosixFilePermission.GROUP_EXECUTE);
        perms.add(PosixFilePermission.OTHERS_EXECUTE);
        Files.setPosixFilePermissions(path, perms);
    }

    private void appendBinToPath(ProcessBuilder pb) {
        String currentPath = System.getenv("PATH");
        pb.environment().put("PATH", DownloadConfig.BIN_DIR + File.pathSeparator + (currentPath != null ? currentPath : ""));
    }
}
