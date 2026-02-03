package com.kyopan_pan.ytdownloader;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * 指定フォルダ内で動画ファイルをファイル名およびメタ情報（ffprobe の format_tags）で検索する。
 */
public final class MediaSearchManager {

    private static final int MAX_DEPTH = 8;
    private static final String[] VIDEO_EXTENSIONS = { ".mp4", ".webm", ".mkv", ".mov", ".m4v" };

    /**
     * 検索対象フォルダが未設定または存在しない場合は空リストを返す。
     * 検索はファイル名の部分一致（大文字小文字無視）と、ffprobe が利用可能な場合は
     * メタ情報（format_tags の全項目）の部分一致で行う。
     *
     * @param rootDir  検索ルート（外付けSSD等のパス）
     * @param query    検索文字列（空の場合は空リスト）
     * @param cancelled 検索キャンセル用。true になったら打ち切る
     * @return マッチした動画ファイルのリスト（重複なし、出現順）
     */
    public List<File> search(String rootDir, String query, AtomicBoolean cancelled) {
        if (rootDir == null || rootDir.isBlank() || query == null || query.isBlank()) {
            return Collections.emptyList();
        }
        Path root = Path.of(rootDir);
        if (!Files.isDirectory(root)) {
            return Collections.emptyList();
        }
        String q = query.trim().toLowerCase(Locale.ROOT);
        List<File> results = new ArrayList<>();
        boolean useFfprobe = canUseFfprobe();

        try (Stream<Path> stream = Files.walk(root, MAX_DEPTH)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                if (cancelled != null && cancelled.get()) {
                    return results;
                }
                if (!Files.isRegularFile(path)) {
                    continue;
                }
                String name = path.getFileName().toString();
                if (!isVideoFile(name)) {
                    continue;
                }
                File file = path.toFile();
                String nameLower = name.toLowerCase(Locale.ROOT);
                if (nameLower.contains(q)) {
                    if (!results.contains(file)) {
                        results.add(file);
                    }
                    continue;
                }
                if (useFfprobe) {
                    String metadata = readMetadataWithFfprobe(path.toAbsolutePath().toString());
                    if (metadata != null && metadata.toLowerCase(Locale.ROOT).contains(q)) {
                        if (!results.contains(file)) {
                            results.add(file);
                        }
                    }
                }
            }
        } catch (Exception e) {
            AppLogger.logError("[MediaSearchManager] Search failed: " + rootDir, e);
        }
        return results;
    }

    private static boolean isVideoFile(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : VIDEO_EXTENSIONS) {
            if (lower.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    private static boolean canUseFfprobe() {
        File ffprobe = new File(DownloadConfig.getFfprobePath());
        return ffprobe.exists() && ffprobe.canExecute();
    }

    /**
     * ffprobe で format_tags を取得。取得失敗時は null。
     */
    private static String readMetadataWithFfprobe(String filePath) {
        String ffprobePath = DownloadConfig.getFfprobePath();
        ProcessBuilder pb = new ProcessBuilder(
                ffprobePath,
                "-v", "quiet",
                "-show_entries", "format_tags",
                "-of", "default=noprint_wrappers=1",
                filePath
        );
        pb.redirectErrorStream(true);
        try {
            Process p = pb.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder metadata = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) {
                        if (metadata.length() > 0) {
                            metadata.append('\n');
                        }
                        metadata.append(trimmed);
                    }
                }
                p.waitFor();
                if (p.exitValue() != 0 || metadata.length() == 0) {
                    return null;
                }
                return metadata.toString();
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

}
