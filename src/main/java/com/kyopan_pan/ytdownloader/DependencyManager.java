package com.kyopan_pan.ytdownloader;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

public class DependencyManager {

    public void ensureBinaries() {
        AppLogger.log("[DependencyManager] Initial setup: ensureBinaries started");
        File binDir = new File(DownloadConfig.BIN_DIR);
        if (!binDir.exists()) {
            boolean created = binDir.mkdirs();
            AppLogger.log("[DependencyManager] bin dir created: " + binDir.getAbsolutePath() + " (ok=" + created + ")");
        } else {
            AppLogger.log("[DependencyManager] bin dir already exists: " + binDir.getAbsolutePath());
        }

        try {
            // 1. yt-dlp の準備
            File ytDlp = new File(DownloadConfig.getYtDlpPath());
            if (!ytDlp.exists()) {
                AppLogger.log("[DependencyManager] yt-dlp not found. Downloading to " + ytDlp.getAbsolutePath());
                downloadYtDlp(ytDlp);
            } else if (!ytDlp.canExecute()) {
                AppLogger.log("[DependencyManager] yt-dlp found but not executable. Re-applying permission...");
                makeExecutable(ytDlp.toPath());
                AppLogger.log("[DependencyManager] yt-dlp permission refreshed.");
            } else {
                AppLogger.log("[DependencyManager] yt-dlp already present: " + ytDlp.getAbsolutePath());
            }

            // 2. ffmpeg の準備 (リソースからコピー)
            File ffmpeg = new File(DownloadConfig.getFfmpegPath());
            if (!ffmpeg.exists()) {
                AppLogger.log("[DependencyManager] ffmpeg not found. Extracting to " + ffmpeg.getAbsolutePath());
                copyFfmpegFromResources(ffmpeg);
            } else if (!ffmpeg.canExecute()) {
                AppLogger.log("[DependencyManager] ffmpeg found but not executable. Re-applying permission...");
                makeExecutable(ffmpeg.toPath());
                AppLogger.log("[DependencyManager] ffmpeg permission refreshed.");
            } else {
                AppLogger.log("[DependencyManager] ffmpeg already present: " + ffmpeg.getAbsolutePath());
            }

        } catch (Exception e) {
            AppLogger.log("[DependencyManager] ensureBinaries failed: " + e.getMessage());
            e.printStackTrace();
        }
        AppLogger.log("[DependencyManager] Initial setup: ensureBinaries finished");
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
            AppLogger.log("[DependencyManager] Checking yt-dlp version...");
            Process process = pb.start();
            String firstLine;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                firstLine = reader.readLine();
            }
            int exitCode = process.waitFor();
            AppLogger.log("[DependencyManager] yt-dlp --version exit=" + exitCode + ", firstLine=" + firstLine);
            if (exitCode != 0) {
                return new YtDlpVersionResult(false, null, "バージョン取得に失敗 (exit=" + exitCode + ")");
            }
            if (firstLine == null || firstLine.isBlank()) {
                return new YtDlpVersionResult(false, null, "バージョン情報が空でした。");
            }
            return new YtDlpVersionResult(true, firstLine.trim(), "yt-dlpのバージョンを取得しました。");
        } catch (Exception e) {
            AppLogger.log("[DependencyManager] Failed to get yt-dlp version: " + e.getMessage());
            return new YtDlpVersionResult(false, null, "バージョン取得に失敗: " + e.getMessage());
        }
    }

    // yt-dlpのバージョン確認および、初期設定を行うメソッド
    public YtDlpUpdateResult updateYtDlp() {
        File binDir = new File(DownloadConfig.BIN_DIR);
        if (!binDir.exists() && !binDir.mkdirs()) {
            return new YtDlpUpdateResult(false, "binフォルダを作成できませんでした。");
        }
        File ytDlp = new File(DownloadConfig.getYtDlpPath());
        try {
            AppLogger.log("[DependencyManager] Updating yt-dlp to latest...");
            downloadYtDlp(ytDlp);

            // === ffmpeg も同時にチェックして復元する ===
            File ffmpeg = new File(DownloadConfig.getFfmpegPath());
            if (!ffmpeg.exists() || !ffmpeg.canExecute()) {
                AppLogger.log("[DependencyManager] ffmpeg missing or invalid during update. Restoring...");
                copyFfmpegFromResources(ffmpeg);
            }
            // ============================================

            return new YtDlpUpdateResult(true, "yt-dlpを更新しました。");
        } catch (IOException e) {
            AppLogger.log("[DependencyManager] Failed to update yt-dlp: " + e.getMessage());
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

        AppLogger.log("[DependencyManager] Downloading yt-dlp via curl from " + downloadUrl);

        // JavaのSSL機能を使わず、macOS標準のcurlコマンドに委譲する
        ProcessBuilder pb = new ProcessBuilder("curl", "-L", "-o", destination.getAbsolutePath(), downloadUrl);
        pb.redirectErrorStream(true);

        try {
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                while (reader.readLine() != null) {
                    // discard output to avoid buffer filling
                }
            }
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException("curlによるダウンロードに失敗しました。Exit code: " + exitCode);
            }

            makeExecutable(destination.toPath());
            AppLogger.log("[DependencyManager] yt-dlp ready: " + destination.getAbsolutePath());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("ダウンロードが中断されました", e);
        }
    }

    private void copyFfmpegFromResources(File destination) throws IOException {
        // src/main/resources/bin/ffmpeg を参照します
        AppLogger.log("[DependencyManager] Copying bundled ffmpeg to " + destination.getAbsolutePath());
        try (InputStream in = getClass().getResourceAsStream("/bin/ffmpeg")) {
            if (in == null) {
                throw new FileNotFoundException("FFmpeg binary not found in resources! Please put 'ffmpeg' in src/main/resources/bin/");
            }
            Files.copy(in, destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
            makeExecutable(destination.toPath());
            AppLogger.log("[DependencyManager] ffmpeg ready: " + destination.getAbsolutePath());
        }
    }

    private void makeExecutable(Path path) throws IOException {
        AppLogger.log("[DependencyManager] Applying executable permission to " + path);
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
