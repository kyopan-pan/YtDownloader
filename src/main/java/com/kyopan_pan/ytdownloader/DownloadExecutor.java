package com.kyopan_pan.ytdownloader;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.shape.SVGPath;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

public class DownloadExecutor {

    public void download(String url, Button btn, SVGPath downloadIcon, Runnable onSuccess) {
        ProgressIndicator spinner = buildSpinner();
        btn.setDisable(true);
        btn.setGraphic(spinner);
        btn.getStyleClass().removeAll("success", "error");
        if (!btn.getStyleClass().contains("busy")) {
            btn.getStyleClass().add("busy");
        }

        new Thread(() -> runDownload(url, btn, downloadIcon, onSuccess)).start();
    }

    private void runDownload(String url, Button btn, SVGPath downloadIcon, Runnable onSuccess) {
        try {
            // 変更: Configから動的パスを取得し、--ffmpeg-location を追加
            ProcessBuilder pb = new ProcessBuilder(
                    DownloadConfig.getYtDlpPath(),
                    "--no-playlist",
                    "-f", "bv+ba/b",
                    "--merge-output-format", "mp4",
                    "--ffmpeg-location", DownloadConfig.getFfmpegPath(), // ここが重要
                    "-o", DownloadConfig.DOWNLOAD_DIR + "/%(title)s.%(ext)s",
                    url
            );

            // PATH環境変数の操作は不要になったため削除しても良いですが、念のため残すなら以下のように
            // 自分の管理するBIN_DIRを含めるようにします
            String currentPath = System.getenv("PATH");
            pb.environment().put("PATH", DownloadConfig.BIN_DIR + File.pathSeparator + (currentPath != null ? currentPath : ""));

            pb.redirectErrorStream(true);
            Process process = pb.start();
            logProcessOutput(process);

            int exitCode = process.waitFor();
            Platform.runLater(() -> handleFinish(exitCode == 0, btn, downloadIcon, onSuccess));
        } catch (Exception ex) {
            ex.printStackTrace();
            Platform.runLater(() -> handleFinish(false, btn, downloadIcon, null));
        }
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

    private void logProcessOutput(Process process) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
