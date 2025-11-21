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
import java.nio.charset.StandardCharsets;
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
    private final Object processLock = new Object();
    private final List<Process> activeProcesses = new ArrayList<>();
    private volatile long downloadStartNanos;
    private volatile boolean downloadActive;
    private volatile boolean progressStarted;
    private volatile boolean cancelRequested;
    private volatile Thread workerThread;
    private Thread loadingElapsedThread;

    public DownloadExecutor() {
        this(null);
    }

    public DownloadExecutor(Consumer<ProgressUpdate> progressConsumer) {
        this.progressConsumer = progressConsumer;
    }

    public void download(String url, Button btn, SVGPath downloadIcon, SVGPath stopIcon, SVGPath successIcon, Runnable onSuccess) {
        logStep("URL入力を受信: " + url);
        prepareStopButton(btn, stopIcon);
        markDownloadStart();
        sendProgress(buildLoadingProgress());
        startLoadingElapsedTicker();

        Thread thread = new Thread(() -> runDownload(url, btn, downloadIcon, successIcon, onSuccess));
        workerThread = thread;
        thread.start();
    }

    public boolean isDownloadActive() {
        return downloadActive;
    }

    public void stopDownload(Button btn, SVGPath downloadIcon) {
        if (!downloadActive) {
            return;
        }
        logStep("停止リクエストを受信。子プロセスを終了します。");
        cancelRequested = true;
        btn.setDisable(true);
        if (!btn.getStyleClass().contains("busy")) {
            btn.getStyleClass().add("busy");
        }
        btn.setGraphic(buildSpinner());
        sendProgress(new ProgressUpdate("キャンセル中...", ProgressIndicator.INDETERMINATE_PROGRESS, true));
        destroyActiveProcesses();
        Thread worker = workerThread;
        if (worker != null) {
            worker.interrupt();
        }
    }

    private void runDownload(String url, Button btn, SVGPath downloadIcon, SVGPath successIcon, Runnable onSuccess) {
        try {
            workerThread = Thread.currentThread();
            logStep("バックグラウンド処理を開始。URL判定中...");
            boolean animeThemes = isAnimeThemesUrl(url);
            logStep(animeThemes ? "AnimeThemes URLと判定。専用パイプラインを使用します。" : "通常のyt-dlpダウンロードを使用します。");

            boolean success = animeThemes
                    ? runAnimeThemesPipeline(url)
                    : runStandardDownload(url);
            if (cancelRequested) {
                Platform.runLater(() -> handleCancelled(btn, downloadIcon));
                return;
            }
            Platform.runLater(() -> handleFinish(success, btn, downloadIcon, successIcon, onSuccess));
        } catch (Exception ex) {
            ex.printStackTrace();
            if (cancelRequested) {
                Platform.runLater(() -> handleCancelled(btn, downloadIcon));
            } else {
                Platform.runLater(() -> handleFinish(false, btn, downloadIcon, successIcon, null));
            }
        } finally {
            workerThread = null;
        }
    }

    private boolean runStandardDownload(String url) throws Exception {
        String outputTemplate = DownloadConfig.getDownloadDir() + "/%(title)s.%(ext)s";
        logStep("yt-dlpを通常モードで起動準備: URL=" + url + ", 出力テンプレート=" + outputTemplate);
        long start = logProcessStart("yt-dlp（通常モード）");

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
        registerProcess(process);
        try {
            consumeStream(process.getInputStream(), true, "yt-dlp");
            int exitCode = waitForProcess(process);
            logProcessEnd("yt-dlp（通常モード）", start, exitCode);
            return exitCode == 0 && !cancelRequested;
        } finally {
            unregisterProcess(process);
        }
    }

    private boolean runAnimeThemesPipeline(String url) throws Exception {
        logStep("AnimeThemesモード: yt-dlpへのファイル名問い合わせをスキップします。");
        String mp4Name = animeThemesFilenameFromTitle(url);
        Path outputPath = Paths.get(DownloadConfig.getDownloadDir(), mp4Name);
        logStep("AnimeThemesモード: 即時生成した出力ファイル=" + outputPath);

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
        List<Process> pipeline = ProcessBuilder.startPipeline(List.of(ytDlp, ffmpeg));
        Process ytProcess = pipeline.get(0);
        Process ffmpegProcess = pipeline.get(1);
        registerProcess(ytProcess);
        registerProcess(ffmpegProcess);
        long ytStart = logProcessStart("yt-dlp（AnimeThemes）");
        long ffStart = logProcessStart("ffmpeg（AnimeThemes）");

        // ログ出力のハンドリング
        // yt-dlpの進捗は標準エラーに出るため、それを監視
        Thread ytLogs = consumeAsync(ytProcess.getErrorStream(), true, "yt-dlp");
        // ffmpegのエラーログも監視
        Thread ffLogs = consumeAsync(ffmpegProcess.getErrorStream(), false, "ffmpeg");

        int ytExit;
        int ffExit;
        try {
            ytExit = waitForProcess(ytProcess);
            logProcessEnd("yt-dlp（AnimeThemes）", ytStart, ytExit);
            ffExit = waitForProcess(ffmpegProcess);
            logProcessEnd("ffmpeg（AnimeThemes）", ffStart, ffExit);
        } finally {
            unregisterProcess(ytProcess);
            unregisterProcess(ffmpegProcess);
        }

        ytLogs.join();
        ffLogs.join();

        // 両方のプロセスが正常終了(0)していれば成功
        return ytExit == 0 && ffExit == 0 && !cancelRequested;
    }

    private String animeThemesFilenameFromTitle(String url) {
        String fallback = quickAnimeThemesFilename(url);
        String title = fetchTitleWithCurl(url);
        if (title == null || title.isBlank()) {
            logStep("title取得に失敗または空。URL由来の一時名を使用します: " + fallback);
            return fallback;
        }
        String normalized = title.replaceAll("\\s*\\|.*", "").trim();
        if (normalized.isBlank()) {
            normalized = title.trim();
        }
        String sanitized = normalized
                .replaceAll("[\\\\/:*?\"<>|]", "_")
                .replaceAll("\\s+", "_");
        if (sanitized.isBlank()) {
            sanitized = "animethemes";
        }
        String timestamp = String.valueOf(System.currentTimeMillis());
        return sanitized + "-" + timestamp + ".mp4";
    }

    private String fetchTitleWithCurl(String url) {
        logStep("curlでtitleタグ取得を試行中...");
        long start = logProcessStart("curl（title取得）");
        ProcessBuilder pb = new ProcessBuilder(
                "curl",
                "-Ls",
                "-m", "5",
                url
        );
        addBinDirToPath(pb);
        pb.redirectErrorStream(true);
        StringBuilder html = new StringBuilder();
        int exitCode;
        try {
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (html.length() < 12000) {
                        html.append(line).append('\n');
                    }
                }
            }
            exitCode = process.waitFor();
        } catch (Exception e) {
            logStep("curlの実行に失敗: " + e.getMessage());
            logProcessEnd("curl（title取得）", start, -1);
            return null;
        }
        logProcessEnd("curl（title取得）", start, exitCode);
        if (exitCode != 0) {
            logStep("curlが非0終了(exit=" + exitCode + ")。");
            return null;
        }
        String title = parseTitleFromHtml(html.toString());
        if (title != null) {
            logStep("curlでtitleを取得: " + title);
        } else {
            logStep("curlでtitleタグを検出できず。");
        }
        return title;
    }

    private String parseTitleFromHtml(String html) {
        if (html == null || html.isBlank()) {
            return null;
        }
        Matcher matcher = Pattern.compile("(?is)<title[^>]*>(.*?)</title>").matcher(html);
        if (matcher.find()) {
            String title = matcher.group(1).trim();
            if (title.isEmpty()) {
                return null;
            }
            // 最低限のデコード（よくあるエンティティのみ）
            return title
                    .replace("&amp;", "&")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&quot;", "\"")
                    .replace("&apos;", "'");
        }
        return null;
    }

    private String quickAnimeThemesFilename(String url) {
        String timestamp = String.valueOf(System.currentTimeMillis());
        try {
            URI uri = new URI(url);
            String path = Optional.ofNullable(uri.getPath()).orElse("");
            String[] segments = path.split("/");
            List<String> filtered = new ArrayList<>();
            for (String segment : segments) {
                if (segment == null) {
                    continue;
                }
                String trimmed = segment.trim();
                if (!trimmed.isEmpty()) {
                    filtered.add(trimmed);
                }
            }

            if (filtered.isEmpty()) {
                return "animethemes-" + timestamp + ".mp4";
            }

            List<String> picked = new ArrayList<>();
            for (int i = filtered.size() - 1; i >= 0 && picked.size() < 2; i--) {
                String seg = filtered.get(i);
                if (seg.equalsIgnoreCase("anime") && filtered.size() > 1) {
                    continue;
                }
                picked.addFirst(seg); // preserve original order
            }

            if (picked.isEmpty()) {
                picked.add(filtered.getLast());
            }

            String base = String.join("-", picked);
            String sanitized = base.replaceAll("[^a-zA-Z0-9-_.]", "_");
            if (sanitized.isBlank()) {
                sanitized = "animethemes";
            }
            return sanitized + "-" + timestamp + ".mp4";
        } catch (Exception ignored) {
            return "animethemes-" + timestamp + ".mp4";
        }
    }

    private void addBinDirToPath(ProcessBuilder pb) {
        String currentPath = System.getenv("PATH");
        logStep("PATHにbinディレクトリを追加: " + DownloadConfig.BIN_DIR);
        pb.environment().put("PATH", DownloadConfig.BIN_DIR + File.pathSeparator + (currentPath != null ? currentPath : ""));
    }

    private boolean isAnimeThemesUrl(String url) {
        return url != null && url.toLowerCase().contains(ANIME_THEMES_HOST);
    }

    private void handleFinish(boolean success, Button btn, SVGPath downloadIcon, SVGPath successIcon, Runnable onSuccess) {
        btn.setDisable(false);
        btn.getStyleClass().removeAll("busy", "stop");
        if (success) {
            btn.getStyleClass().remove("error");
            if (!btn.getStyleClass().contains("success")) {
                btn.getStyleClass().add("success");
            }
            btn.setGraphic(successIcon);
            btn.setAccessibleText("Download succeeded");
            if (onSuccess != null) {
                onSuccess.run();
            }
        } else {
            btn.getStyleClass().remove("success");
            if (!btn.getStyleClass().contains("error")) {
                btn.getStyleClass().add("error");
            }
            btn.setGraphic(downloadIcon);
            btn.setAccessibleText("Download");
        }
        clearDownloadStart();
        sendProgress(ProgressUpdate.hidden());
    }

    private void handleCancelled(Button btn, SVGPath downloadIcon) {
        logStep("ダウンロードをキャンセルしました。");
        btn.setDisable(false);
        btn.getStyleClass().removeAll("busy", "stop", "success", "error");
        btn.setGraphic(downloadIcon);
        btn.setAccessibleText("Download");
        clearDownloadStart();
        sendProgress(ProgressUpdate.hidden());
    }

    private void prepareStopButton(Button btn, SVGPath stopIcon) {
        btn.setDisable(false);
        btn.getStyleClass().removeAll("success", "error");
        if (!btn.getStyleClass().contains("stop")) {
            btn.getStyleClass().add("stop");
        }
        btn.getStyleClass().remove("busy");
        btn.setGraphic(stopIcon);
        btn.setAccessibleText("Stop download");
    }

    private ProgressIndicator buildSpinner() {
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setPrefSize(18, 18);
        spinner.setMaxSize(18, 18);
        spinner.getStyleClass().add("button-spinner");
        return spinner;
    }

    private void registerProcess(Process process) {
        synchronized (processLock) {
            activeProcesses.add(process);
        }
    }

    private void unregisterProcess(Process process) {
        synchronized (processLock) {
            activeProcesses.remove(process);
        }
    }

    private void destroyActiveProcesses() {
        List<Process> snapshot;
        synchronized (processLock) {
            snapshot = new ArrayList<>(activeProcesses);
        }
        for (Process process : snapshot) {
            try {
                process.destroy();
            } catch (Exception ignored) {
            }
        }
        for (Process process : snapshot) {
            try {
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
            } catch (Exception ignored) {
            }
        }
    }

    private int waitForProcess(Process process) {
        try {
            return process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    private Thread consumeAsync(InputStream stream, boolean parseProgress, String sourceLabel) {
        Thread t = new Thread(() -> consumeStream(stream, parseProgress, sourceLabel));
        t.setDaemon(true);
        t.start();
        return t;
    }

    private void consumeStream(InputStream stream, boolean parseProgress, String sourceLabel) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String labeled = (sourceLabel == null || sourceLabel.isBlank()) ? line : "[" + sourceLabel + "] " + line;
                AppLogger.log(labeled);
                if (parseProgress) {
                    Double percent = extractPercent(line);
                    if (percent != null) {
                        markProgressStarted();
                        sendProgress(buildDownloadingProgress(percent));
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

    private long logProcessStart(String label) {
        long start = System.nanoTime();
        logStep(label + " を開始");
        return start;
    }

    private void logProcessEnd(String label, long startNanos, int exitCode) {
        logStep(label + " 終了。exit=" + exitCode + " / " + formatDuration(System.nanoTime() - startNanos));
    }

    public record ProgressUpdate(String message, double progress, boolean visible) {
        public boolean indeterminate() {
            return progress < 0;
        }

        public static ProgressUpdate infoLoading(String elapsed) {
            String elapsedPart = (elapsed == null || elapsed.isBlank()) ? "" : String.format(" (経過: %s)", elapsed);
            return new ProgressUpdate("動画読み込み中..." + elapsedPart, ProgressIndicator.INDETERMINATE_PROGRESS, true);
        }

        public static ProgressUpdate downloading(double percent, String elapsed) {
            double clamped = Math.max(0, Math.min(percent, 100));
            String elapsedPart = (elapsed == null || elapsed.isBlank()) ? "" : String.format(" (経過: %s)", elapsed);
            return new ProgressUpdate(String.format("ダウンロード中... %.1f%%%s", clamped, elapsedPart), clamped / 100.0, true);
        }

        public static ProgressUpdate hidden() {
            return new ProgressUpdate("", 0, false);
        }
    }

    private void logStep(String message) {
        AppLogger.log("[DownloadExecutor] " + message);
    }

    private ProgressUpdate buildDownloadingProgress(double percent) {
        String elapsed = formatElapsedForUi();
        return ProgressUpdate.downloading(percent, elapsed);
    }

    private ProgressUpdate buildLoadingProgress() {
        return ProgressUpdate.infoLoading(formatElapsedForUi());
    }

    private void markDownloadStart() {
        downloadStartNanos = System.nanoTime();
        downloadActive = true;
        progressStarted = false;
        cancelRequested = false;
    }

    private void clearDownloadStart() {
        downloadStartNanos = 0;
        downloadActive = false;
        progressStarted = false;
        cancelRequested = false;
        Thread ticker = loadingElapsedThread;
        if (ticker != null) {
            ticker.interrupt();
        }
    }

    private String formatDuration(long nanos) {
        double millis = nanos / 1_000_000.0;
        if (millis >= 1000) {
            return String.format("%.2f s", millis / 1000.0);
        }
        return String.format("%.1f ms", millis);
    }

    private String formatElapsedForUi() {
        long start = downloadStartNanos;
        if (start <= 0) {
            return "00:00";
        }
        long elapsedSeconds = Math.max(0, (System.nanoTime() - start) / 1_000_000_000L);
        long hours = elapsedSeconds / 3600;
        long minutes = (elapsedSeconds % 3600) / 60;
        long seconds = elapsedSeconds % 60;
        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format("%02d:%02d", minutes, seconds);
    }

    private void startLoadingElapsedTicker() {
        Thread existing = loadingElapsedThread;
        if (existing != null && existing.isAlive()) {
            existing.interrupt();
        }
        loadingElapsedThread = new Thread(() -> {
            try {
                while (downloadActive && !progressStarted) {
                    sendProgress(buildLoadingProgress());
                    Thread.sleep(1000);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                loadingElapsedThread = null;
            }
        });
        loadingElapsedThread.setDaemon(true);
        loadingElapsedThread.start();
    }

    private void markProgressStarted() {
        progressStarted = true;
    }
}
