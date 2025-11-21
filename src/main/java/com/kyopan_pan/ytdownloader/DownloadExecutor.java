package com.kyopan_pan.ytdownloader;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.shape.SVGPath;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DownloadExecutor {

    private static final String ANIME_THEMES_HOST = "animethemes.moe";
    private static final Pattern PERCENT_PATTERN = Pattern.compile("(\\d{1,3}(?:\\.\\d+)?)%");
    private final Consumer<ProgressUpdate> progressConsumer;

    public DownloadExecutor() {
        this(null);
    }

    public DownloadExecutor(Consumer<ProgressUpdate> progressConsumer) {
        this.progressConsumer = progressConsumer;
    }

    public void download(String url, Button btn, SVGPath downloadIcon, Runnable onSuccess) {
        logStep("URL入力を受信: " + url);
        ProgressIndicator spinner = buildSpinner();
        btn.setDisable(true);
        btn.setGraphic(spinner);
        btn.getStyleClass().removeAll("success", "error");
        if (!btn.getStyleClass().contains("busy")) {
            btn.getStyleClass().add("busy");
        }
        sendProgress(ProgressUpdate.infoLoading());

        new Thread(() -> runDownload(url, btn, downloadIcon, onSuccess)).start();
    }

    private void runDownload(String url, Button btn, SVGPath downloadIcon, Runnable onSuccess) {
        try {
            logStep("バックグラウンド処理を開始。URL判定中...");
            boolean animeThemes = isAnimeThemesUrl(url);
            logStep(animeThemes ? "AnimeThemes URLと判定。専用パイプラインを使用します。" : "通常のyt-dlpダウンロードを使用します。");

            boolean success = animeThemes
                    ? runAnimeThemesPipeline(url)
                    : runStandardDownload(url);
            Platform.runLater(() -> handleFinish(success, btn, downloadIcon, onSuccess));
        } catch (Exception ex) {
            ex.printStackTrace();
            Platform.runLater(() -> handleFinish(false, btn, downloadIcon, null));
        }
    }

    private boolean runStandardDownload(String url) throws Exception {
        String outputTemplate = DownloadConfig.DOWNLOAD_DIR + "/%(title)s.%(ext)s";
        logStep("yt-dlpを通常モードで起動準備: URL=" + url + ", 出力テンプレート=" + outputTemplate);
        long start = System.nanoTime();

        ProcessBuilder pb = new ProcessBuilder(
                DownloadConfig.getYtDlpPath(),
                "--no-playlist",
                "-f", "bv+ba/b",
                "--merge-output-format", "mp4",
                "--ffmpeg-location", DownloadConfig.getFfmpegPath(),
                "-o", outputTemplate,
                url
        );

        addBinDirToPath(pb);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        consumeStream(process.getInputStream(), true);
        int exitCode = process.waitFor();
        logStep("yt-dlp（通常モード）終了。exit=" + exitCode + " / " + formatDuration(System.nanoTime() - start));
        return exitCode == 0;
    }

    private boolean runAnimeThemesPipeline(String url) throws Exception {
        logStep("AnimeThemesモード: ファイル名を取得します。");
        String originalName = fetchFilename(url);
        String mp4Name = toMp4Name(originalName);
        Path outputPath = Paths.get(DownloadConfig.DOWNLOAD_DIR, mp4Name);
        logStep("AnimeThemesモード: 取得した名前=" + originalName + " / 出力ファイル=" + outputPath);

        // 1. yt-dlp: 標準出力(-)にデータを流す設定
        ProcessBuilder ytDlp = new ProcessBuilder(
                DownloadConfig.getYtDlpPath(),
                "--no-playlist",
                "-f", "bv+ba/b", // ベスト画質+ベスト音質
                "-o", "-",       // 標準出力へ
                url
        );

        // 2. ffmpeg: パイプからの入力を強化設定で受け取る
        ProcessBuilder ffmpeg = new ProcessBuilder(
                DownloadConfig.getFfmpegPath(),
                "-loglevel", "error",

                // 【重要】パイプ入力の解析バッファを増やす設定
                "-analyzeduration", "100M", // 解析にかける時間/データ量(100MB分)
                "-probesize", "100M",       // フォーマット検出に使うデータ量(100MB)

                // 入力フォーマットを明示（誤検知防止）
                "-f", "webm",
                "-i", "pipe:0",

                // 変換設定
                "-c:v", "libx264",
                "-preset", "veryfast",
                "-c:a", "aac",
                "-b:a", "192k",

                // エラー許容設定（軽微なパケット破損を無視して続行させる）
                "-ignore_unknown",

                "-movflags", "+faststart",
                "-f", "mp4",
                "-y",
                outputPath.toString()
        );

        addBinDirToPath(ytDlp);
        addBinDirToPath(ffmpeg);

        // パイプラインの実行（ダウンロードと変換を同時に行うため高速）
        logStep("AnimeThemesモード: yt-dlp→ffmpegパイプラインを起動します。");
        long ytStart = System.nanoTime();
        long ffStart = ytStart;
        List<Process> pipeline = ProcessBuilder.startPipeline(List.of(ytDlp, ffmpeg));
        Process ytProcess = pipeline.get(0);
        Process ffmpegProcess = pipeline.get(1);

        // ログ出力のハンドリング
        // yt-dlpの進捗は標準エラーに出るため、それを監視
        Thread ytLogs = consumeAsync(ytProcess.getErrorStream(), true);
        // ffmpegのエラーログも監視
        Thread ffLogs = consumeAsync(ffmpegProcess.getErrorStream(), false);

        int ytExit = ytProcess.waitFor();
        logStep("yt-dlp（AnimeThemes）終了。exit=" + ytExit + " / " + formatDuration(System.nanoTime() - ytStart));
        int ffExit = ffmpegProcess.waitFor();
        logStep("ffmpeg（AnimeThemes）終了。exit=" + ffExit + " / " + formatDuration(System.nanoTime() - ffStart));

        ytLogs.join();
        ffLogs.join();

        // 両方のプロセスが正常終了(0)していれば成功
        return ytExit == 0 && ffExit == 0;
    }

    private String fetchFilename(String url) throws Exception {
        logStep("ファイル名を yt-dlp --get-filename で取得中...");
        long start = System.nanoTime();
        ProcessBuilder pb = new ProcessBuilder(
                DownloadConfig.getYtDlpPath(),
                "--no-playlist",
                "-f", "bv+ba/b",
                "-o", "%(title)s.%(ext)s",
                "--get-filename",
                url
        );
        addBinDirToPath(pb);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        List<String> lines = new ArrayList<>();
        String name = null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
                String trimmed = line.trim();
                if (looksLikeFilename(trimmed)) {
                    name = trimmed;
                } else if (name == null && !trimmed.isBlank() && !trimmed.startsWith("WARNING")) {
                    // pick the first non-warning non-empty line as fallback candidate
                    name = trimmed;
                }
            }
        }
        int exitCode = process.waitFor();
        if (exitCode != 0 || name == null || name.isBlank()) {
            logStep("ファイル名の取得に失敗。fallback名を生成します。(exit=" + exitCode + ")");
            System.err.println("Failed to resolve filename from yt-dlp (exit=" + exitCode + "). Output:\n" + String.join("\n", lines));
            return fallbackFilename(url);
        }
        logStep("ファイル名の取得に成功: " + name);
        logStep("ファイル名取得の所要時間: " + formatDuration(System.nanoTime() - start));
        return name;
    }

    private boolean looksLikeFilename(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("WARNING") || trimmed.startsWith("[") || trimmed.contains("warning:")) {
            return false;
        }
        return trimmed.matches("(?i).+\\.(mp4|mkv|webm|m4a|mp3|wav|flac|opus|avi|mov|wmv)$");
    }

    private String fallbackFilename(String url) {
        String timestamp = String.valueOf(System.currentTimeMillis());
        try {
            URI uri = new URI(url);
            String hostPart = Optional.ofNullable(uri.getHost()).orElse("video");
            String path = Optional.ofNullable(uri.getPath()).orElse("");
            String lastSegment = Paths.get(path).getFileName() != null ? Paths.get(path).getFileName().toString() : "clip";
            String sanitized = (hostPart + "-" + lastSegment).replaceAll("[^a-zA-Z0-9-_\\.]", "_");
            return sanitized + "-" + timestamp + ".mp4";
        } catch (Exception ignored) {
            return "video-" + timestamp + ".mp4";
        }
    }

    private String toMp4Name(String filename) {
        String justName = Paths.get(filename).getFileName().toString();
        int dot = justName.lastIndexOf('.');
        String base = dot > 0 ? justName.substring(0, dot) : justName;
        return base + ".mp4";
    }

    private void addBinDirToPath(ProcessBuilder pb) {
        String currentPath = System.getenv("PATH");
        logStep("PATHにbinディレクトリを追加: " + DownloadConfig.BIN_DIR);
        pb.environment().put("PATH", DownloadConfig.BIN_DIR + File.pathSeparator + (currentPath != null ? currentPath : ""));
    }

    private boolean isAnimeThemesUrl(String url) {
        return url != null && url.toLowerCase().contains(ANIME_THEMES_HOST);
    }

    private void handleFinish(boolean success, Button btn, SVGPath downloadIcon, Runnable onSuccess) {
        btn.getStyleClass().remove("busy");
        if (success) {
            btn.getStyleClass().remove("error");
            if (!btn.getStyleClass().contains("success")) {
                btn.getStyleClass().add("success");
            }
            if (onSuccess != null) {
                onSuccess.run();
            }
        } else {
            btn.getStyleClass().remove("success");
            if (!btn.getStyleClass().contains("error")) {
                btn.getStyleClass().add("error");
            }
        }
        btn.setGraphic(downloadIcon);
        resetButtonStateLater(btn, downloadIcon);
        sendProgress(ProgressUpdate.hidden());
    }

    private ProgressIndicator buildSpinner() {
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setPrefSize(18, 18);
        spinner.setMaxSize(18, 18);
        spinner.getStyleClass().add("button-spinner");
        return spinner;
    }

    private void resetButtonStateLater(Button btn, SVGPath downloadIcon) {
        new Thread(() -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ignored) {
            }
            Platform.runLater(() -> {
                btn.setDisable(false);
                btn.getStyleClass().removeAll("success", "error");
                btn.setGraphic(downloadIcon);
            });
        }).start();
    }

    private Thread consumeAsync(InputStream stream, boolean parseProgress) {
        Thread t = new Thread(() -> consumeStream(stream, parseProgress));
        t.setDaemon(true);
        t.start();
        return t;
    }

    private void consumeStream(InputStream stream, boolean parseProgress) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
                if (parseProgress) {
                    Double percent = extractPercent(line);
                    if (percent != null) {
                        sendProgress(ProgressUpdate.downloading(percent));
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private Double extractPercent(String line) {
        Matcher matcher = PERCENT_PATTERN.matcher(line);
        if (matcher.find()) {
            try {
                return Double.parseDouble(matcher.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private void sendProgress(ProgressUpdate update) {
        if (progressConsumer == null || update == null) {
            return;
        }
        Platform.runLater(() -> progressConsumer.accept(update));
    }

    public record ProgressUpdate(String message, double progress, boolean visible) {
        public boolean indeterminate() {
            return progress < 0;
        }

        public static ProgressUpdate infoLoading() {
            return new ProgressUpdate("動画読み込み中...", ProgressIndicator.INDETERMINATE_PROGRESS, true);
        }

        public static ProgressUpdate downloading(double percent) {
            double clamped = Math.max(0, Math.min(percent, 100));
            return new ProgressUpdate(String.format("ダウンロード中... %.1f%%", clamped), clamped / 100.0, true);
        }

        public static ProgressUpdate hidden() {
            return new ProgressUpdate("", 0, false);
        }
    }

    private void logStep(String message) {
        System.out.println("[DownloadExecutor] " + message);
    }

    private String formatDuration(long nanos) {
        double millis = nanos / 1_000_000.0;
        if (millis >= 1000) {
            return String.format("%.2f s", millis / 1000.0);
        }
        return String.format("%.1f ms", millis);
    }
}
