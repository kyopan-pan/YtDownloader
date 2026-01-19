package com.kyopan_pan.ytdownloader;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
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

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.shape.SVGPath;

public class DownloadExecutor {

    private static final String ANIME_THEMES_HOST = "animethemes.moe";
    private static final Pattern PERCENT_PATTERN = Pattern.compile("(\\d{1,3}(?:\\.\\d+)?)%");
    private static final Pattern POST_PROCESSING_PATTERN = Pattern.compile(
            "(?i)(\\[merger\\]|\\[ffmpeg\\]|\\[extractaudio\\]|\\[postprocess\\]|\\[video(?:convertor|converter)\\]|\\[audio(?:convertor|converter)\\]|\\[fixup\\w*\\]|Merging formats into|Post-process)"
    );
    /** og:video または video src から .webm 直リンクを抽出 */
    private static final Pattern ANIME_THEMES_OG_VIDEO = Pattern.compile(
            "(?:name=\"og:video\" content=\"|video src=\")(https://[^\"]+\\.webm)\""
    );
    private static final String CURL_USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private final Consumer<ProgressUpdate> progressConsumer;
    private final Object processLock = new Object();
    private final List<Process> activeProcesses = new ArrayList<>();
    private volatile long downloadStartNanos;
    private volatile boolean downloadActive;
    private volatile boolean progressStarted;
    private volatile boolean cancelRequested;
    private volatile boolean postProcessing;
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

    public void stopDownload(Button btn) {
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
            AppLogger.logError("[DownloadExecutor] ダウンロード処理中に例外が発生しました", ex);
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
        logStep("yt-dlpを通常モード(H.264優先)で起動準備: URL=" + url + ", 出力テンプレート=" + outputTemplate);

        // --js-runtimes: GUI起動時でもJS runtime未検出問題を回避するため、同梱Denoの絶対パスを渡す
        ProcessBuilder pb = prepareProcess(new ProcessBuilder(
                DownloadConfig.getYtDlpPath(),
                "--no-playlist",
                "--extractor-args", "youtube:player_client=web",
                "--extractor-args", "youtube:skip=translated_subs",
                "--concurrent-fragments", "4",
                "-S", "vcodec:h264,res,acodec:m4a",
                "--match-filter", "vcodec~='(?i)^(avc|h264)'",
                "--merge-output-format", "mp4",
                "--ffmpeg-location", DownloadConfig.getFfmpegPath(),
                "--js-runtimes", DownloadConfig.getDenoPath(),
                "-o", outputTemplate,
                url
        ), true);

        Process process = pb.start();
        TrackedProcess tracked = monitorProcess("yt-dlp（H.264優先）", process, true, false, "yt-dlp");
        int exitCode = awaitProcess(tracked);
        
        if (succeeded(exitCode)) {
            return true;
        }
        
        if (cancelRequested) {
            return false;
        }
        
        logStep("H.264形式が見つからないため、互換モード(720p以下+GPU変換)で再試行します。");
        
        // --js-runtimes: 互換モードでも同様に同梱Denoを使用
        ProcessBuilder pbFallback = prepareProcess(new ProcessBuilder(
                DownloadConfig.getYtDlpPath(),
                "--no-playlist",
                "--extractor-args", "youtube:player_client=web",
                "--extractor-args", "youtube:skip=translated_subs",
                "--concurrent-fragments", "4",
                "-f", "bv*[height<=720]+ba/b[height<=720]",
                "--recode-video", "mp4",
                "--postprocessor-args", "VideoConvertor:-c:v h264_videotoolbox -b:v 5M -pix_fmt yuv420p",
                "--ffmpeg-location", DownloadConfig.getFfmpegPath(),
                "--js-runtimes", DownloadConfig.getDenoPath(),
                "-o", outputTemplate,
                url
        ), true);
        
        Process processFallback = pbFallback.start();
        TrackedProcess trackedFallback = monitorProcess("yt-dlp（互換モード）", processFallback, true, false, "yt-dlp");
        int exitCodeFallback = awaitProcess(trackedFallback);
        return succeeded(exitCodeFallback);
    }

    private boolean runAnimeThemesPipeline(String url) throws Exception {
        // ファイル名はURLパスから即時生成（curlによるtitle取得は行わず遅延を削減）
        String mp4Name = quickAnimeThemesFilename(url);
        Path outputPath = Paths.get(DownloadConfig.getDownloadDir(), mp4Name);
        logStep("AnimeThemesモード: 即時生成した出力ファイル=" + outputPath);

        // ページの og:video / video src から .webm 直リンクを取得できれば、yt-dlp を介さず curl|ffmpeg で高速化
        String directUrl = fetchAnimeThemesDirectVideoUrl(url);
        if (directUrl != null) {
            logStep("AnimeThemes: 直リンクを取得。curl→ffmpeg でダウンロードします。");
            return runAnimeThemesDirectPipeline(directUrl, outputPath);
        }
        logStep("AnimeThemes: 直リンク取得に失敗。yt-dlp パイプラインにフォールバックします。");

        // 1. yt-dlp: 標準出力(-)にデータを流す設定
        // generic エクストラクターはJS不要。--js-runtimes と PATH の bin 追加を省き起動を高速化
        ProcessBuilder ytDlp = prepareProcess(new ProcessBuilder(
                DownloadConfig.getYtDlpPath(),
                "--no-playlist",
                "--concurrent-fragments", "4",
                "-f", "bv+ba/b", // ベスト画質+ベスト音質
                "--ffmpeg-location", DownloadConfig.getFfmpegPath(),
                "-o", "-",       // 標準出力へ
                url
        ), false, false);

        // 2. ffmpeg: パイプからの入力を強化設定で受け取る（絶対パス実行のため PATH はデフォルトでよい）
        ProcessBuilder ffmpeg = prepareProcess(new ProcessBuilder(
                DownloadConfig.getFfmpegPath(),
                "-loglevel", "error",

                // 【重要】パイプ入力の解析バッファを増やす設定
                "-analyzeduration", "100M", // 解析にかける時間/データ量(100MB分)
                "-probesize", "100M",       // フォーマット検出に使うデータ量(100MB)

                // 入力フォーマットを明示（誤検知防止）
                "-f", "webm",
                "-i", "pipe:0",

                // 変換設定（Apple Silicon GPU: VideoToolbox使用）
                "-c:v", "h264_videotoolbox",
                "-b:v", "5M",
                "-pix_fmt", "yuv420p",
                "-c:a", "aac",
                "-b:a", "192k",

                // エラー許容設定（軽微なパケット破損を無視して続行させる）
                "-ignore_unknown",

                "-movflags", "+faststart",
                "-f", "mp4",
                "-y",
                outputPath.toString()
        ), false);

        // パイプラインの実行（ダウンロードと変換を同時に行うため高速）
        logStep("AnimeThemesモード: yt-dlp→ffmpegパイプラインを起動します。");
        List<Process> pipeline = ProcessBuilder.startPipeline(List.of(ytDlp, ffmpeg));
        Process ytProcess = pipeline.get(0);
        Process ffmpegProcess = pipeline.get(1);

        TrackedProcess ytMonitor = monitorProcess("yt-dlp（AnimeThemes）", ytProcess, true, true, "yt-dlp");
        TrackedProcess ffMonitor = monitorProcess("ffmpeg（AnimeThemes）", ffmpegProcess, false, true, "ffmpeg");

        int ytExit = awaitProcess(ytMonitor);
        int ffExit = awaitProcess(ffMonitor);

        // 両方のプロセスが正常終了(0)していれば成功
        return succeeded(ytExit) && succeeded(ffExit);
    }

    /**
     * ページ HTML の og:video または video src から .webm の直リンクを抽出する。
     * 取得できなければ null。curl はブラウザ UA で先頭約 30KB のみ取得（タイムアウト 8 秒）。
     */
    private String fetchAnimeThemesDirectVideoUrl(String pageUrl) {
        logStep("AnimeThemes: 直リンク取得を試行（HTML から .webm URL を抽出）");
        ProcessBuilder pb = new ProcessBuilder(
                "curl", "-sL", "-m", "8", "-A", CURL_USER_AGENT, pageUrl
        );
        pb.redirectErrorStream(true);
        Process process = null;
        try {
            process = pb.start();
            registerProcess(process);
            ByteArrayOutputStream buf = new ByteArrayOutputStream(30_000);
            byte[] b = new byte[4096];
            int r;
            InputStream in = process.getInputStream();
            while (buf.size() < 30_000 && (r = in.read(b)) != -1) {
                buf.write(b, 0, r);
            }
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            process.waitFor();
            String html = buf.toString(StandardCharsets.UTF_8);
            Matcher m = ANIME_THEMES_OG_VIDEO.matcher(html);
            if (m.find()) {
                String url = m.group(1);
                logStep("AnimeThemes: 直リンクを取得: " + url);
                return url;
            }
        } catch (Exception e) {
            AppLogger.log("[DownloadExecutor] AnimeThemes 直リンク取得で例外: " + e.getMessage());
        } finally {
            if (process != null) {
                unregisterProcess(process);
            }
        }
        return null;
    }

    /** 直リンクを curl で取得し ffmpeg にパイプ。yt-dlp の起動遅延を避けて高速化。 */
    private boolean runAnimeThemesDirectPipeline(String directVideoUrl, Path outputPath) throws Exception {
        ProcessBuilder curl = new ProcessBuilder(
                "curl", "-L", "-m", "120", "--fail", "-o", "-", "-A", CURL_USER_AGENT, directVideoUrl
        );
        curl.redirectError(ProcessBuilder.Redirect.DISCARD);

        ProcessBuilder ffmpeg = prepareProcess(new ProcessBuilder(
                DownloadConfig.getFfmpegPath(),
                "-loglevel", "error",
                "-analyzeduration", "100M",
                "-probesize", "100M",
                "-f", "webm",
                "-i", "pipe:0",
                "-c:v", "h264_videotoolbox",
                "-b:v", "5M",
                "-pix_fmt", "yuv420p",
                "-c:a", "aac",
                "-b:a", "192k",
                "-ignore_unknown",
                "-movflags", "+faststart",
                "-f", "mp4",
                "-y",
                outputPath.toString()
        ), false);

        logStep("AnimeThemesモード: curl→ffmpeg パイプラインを起動します。");
        List<Process> pipeline = ProcessBuilder.startPipeline(List.of(curl, ffmpeg));
        Process curlProcess = pipeline.get(0);
        Process ffmpegProcess = pipeline.get(1);

        registerProcess(curlProcess);
        long curlStart = logProcessStart("curl（AnimeThemes直リンク）");
        TrackedProcess curlTracked = new TrackedProcess(curlProcess, "curl（AnimeThemes直リンク）", curlStart, null);
        TrackedProcess ffMonitor = monitorProcess("ffmpeg（AnimeThemes）", ffmpegProcess, false, true, "ffmpeg");

        int curlExit = awaitProcess(curlTracked);
        int ffExit = awaitProcess(ffMonitor);
        return succeeded(curlExit) && succeeded(ffExit);
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
                picked.addFirst(seg); // 元の順序を保つため先頭に追加する
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
                    if (!postProcessing && isPostProcessingLine(line)) {
                        markProgressStarted();
                        postProcessing = true;
                        logStep("後処理フェーズに移行します。");
                        sendProgress(buildPostProcessingProgress());
                        continue;
                    }
                    // 既に後処理中の場合は進捗更新をスキップ（変換中表示を維持）
                    if (postProcessing) {
                        continue;
                    }
                    Double percent = extractPercent(line);
                    if (percent != null) {
                        markProgressStarted();
                        sendProgress(buildDownloadingProgress(percent));
                    }
                }
            }
        } catch (Exception e) {
            String label = (sourceLabel == null || sourceLabel.isBlank()) ? "" : " (" + sourceLabel + ")";
            AppLogger.logError("[DownloadExecutor] プロセスストリームの読み取りで例外が発生しました" + label, e);
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

    private boolean isPostProcessingLine(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        return POST_PROCESSING_PATTERN.matcher(line).find();
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
            return new ProgressUpdate("動画読み込み中..." + formatElapsed(elapsed), ProgressIndicator.INDETERMINATE_PROGRESS, true);
        }

        public static ProgressUpdate downloading(double percent, String elapsed) {
            double clamped = Math.max(0, Math.min(percent, 100));
            return new ProgressUpdate(String.format("ダウンロード中... %.1f%%%s", clamped, formatElapsed(elapsed)), clamped / 100.0, true);
        }

        public static ProgressUpdate postProcessing(String elapsed) {
            return new ProgressUpdate("変換中..." + formatElapsed(elapsed), ProgressIndicator.INDETERMINATE_PROGRESS, true);
        }

        public static ProgressUpdate hidden() {
            return new ProgressUpdate("", 0, false);
        }

        private static String formatElapsed(String elapsed) {
            if (elapsed == null || elapsed.isBlank()) {
                return "";
            }
            return String.format(" (経過: %s)", elapsed);
        }
    }

    private void logStep(String message) {
        AppLogger.log("[DownloadExecutor] " + message);
    }

    private ProgressUpdate buildDownloadingProgress(double percent) {
        String elapsed = formatElapsedForUi();
        return ProgressUpdate.downloading(percent, elapsed);
    }

    private ProgressUpdate buildPostProcessingProgress() {
        String elapsed = formatElapsedForUi();
        return ProgressUpdate.postProcessing(elapsed);
    }

    private ProgressUpdate buildLoadingProgress() {
        return ProgressUpdate.infoLoading(formatElapsedForUi());
    }

    private void markDownloadStart() {
        downloadStartNanos = System.nanoTime();
        downloadActive = true;
        progressStarted = false;
        cancelRequested = false;
        postProcessing = false;
    }

    private void clearDownloadStart() {
        downloadStartNanos = 0;
        downloadActive = false;
        progressStarted = false;
        cancelRequested = false;
        postProcessing = false;
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

    private ProcessBuilder prepareProcess(ProcessBuilder builder, boolean redirectErrorStream) {
        return prepareProcess(builder, redirectErrorStream, true);
    }

    private ProcessBuilder prepareProcess(ProcessBuilder builder, boolean redirectErrorStream, boolean addBinToPath) {
        if (addBinToPath) {
            addBinDirToPath(builder);
        }
        builder.redirectErrorStream(redirectErrorStream);
        return builder;
    }

    private TrackedProcess monitorProcess(String label, Process process, boolean parseProgress, boolean useErrorStream, String sourceLabel) {
        registerProcess(process);
        long start = logProcessStart(label);
        InputStream logStream = useErrorStream ? process.getErrorStream() : process.getInputStream();
        Thread logThread = consumeAsync(logStream, parseProgress, sourceLabel);
        return new TrackedProcess(process, label, start, logThread);
    }

    private int awaitProcess(TrackedProcess tracked) {
        try {
            int exitCode = waitForProcess(tracked.process());
            logProcessEnd(tracked.label(), tracked.startNanos(), exitCode);
            joinQuietly(tracked.logThread());
            return exitCode;
        } finally {
            unregisterProcess(tracked.process());
        }
    }

    private boolean succeeded(int exitCode) {
        return exitCode == 0 && !cancelRequested;
    }

    private void joinQuietly(Thread thread) {
        if (thread == null) {
            return;
        }
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void markProgressStarted() {
        progressStarted = true;
    }

    private record TrackedProcess(Process process, String label, long startNanos, Thread logThread) {
    }
}
