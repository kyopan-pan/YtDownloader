package com.kyopan_pan.ytdownloader;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.OverrunStyle;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.scene.shape.SVGPath;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class HelloApplication extends Application {

    // Macの「ムービー」フォルダへのパス
    private static final String DOWNLOAD_DIR = System.getProperty("user.home") + "/Movies/YtDlpDownloads";

    // 【重要】手順1で調べたパスに書き換えてください (例: /opt/homebrew/bin/yt-dlp)
    // ここが単に "yt-dlp" だとMacのGUIアプリからは動かないことが多いです
    private static final String YT_DLP_PATH = "/usr/local/bin/yt-dlp";

    private ListView<File> fileListView;

    @Override
    public void start(Stage primaryStage) {
        new File(DOWNLOAD_DIR).mkdirs();

        TextField urlInput = new TextField();
        urlInput.setPromptText("YouTube URL...");
        urlInput.getStyleClass().add("url-input");

        SVGPath downloadIcon = buildDownloadIcon();

        Button downloadBtn = new Button();
        downloadBtn.setAccessibleText("Download");
        downloadBtn.setGraphic(downloadIcon);
        downloadBtn.getStyleClass().add("download-btn");
        downloadBtn.setPrefWidth(56);
        downloadBtn.setPrefHeight(48);
        downloadBtn.setMinHeight(48);

        HBox inputRow = new HBox(12, urlInput, downloadBtn);
        inputRow.getStyleClass().add("input-row");
        HBox.setHgrow(urlInput, Priority.ALWAYS);

        fileListView = new ListView<>();
        fileListView.getStyleClass().add("downloads-list");
        disableHorizontalScroll(fileListView);
        updateFileList();

        Label downloadsLabel = new Label("Downloads");
        downloadsLabel.getStyleClass().add("section-title");

        VBox root = new VBox(14, inputRow, downloadsLabel, fileListView);
        root.getStyleClass().add("app");
        root.setPadding(new Insets(16));
        VBox.setVgrow(fileListView, Priority.ALWAYS);

        downloadBtn.setOnAction(e -> {
            String url = urlInput.getText();
            if (url != null && !url.isEmpty()) {
                downloadVideo(url, downloadBtn, downloadIcon);
                urlInput.clear();
            }
        });

        // VDMXへのドラッグ＆ドロップ用
        fileListView.setOnDragDetected(event -> {
            File selectedFile = fileListView.getSelectionModel().getSelectedItem();
            if (selectedFile != null) {
                Dragboard db = fileListView.startDragAndDrop(TransferMode.COPY);
                ClipboardContent content = new ClipboardContent();
                content.putFiles(Collections.singletonList(selectedFile));
                db.setContent(content);
                event.consume();
            }
        });

        fileListView.setCellFactory(param -> new ListCell<>() {
            private final Label nameLabel = new Label();
            private final Button deleteBtn = new Button();
            private final Region spacer = new Region();
            private final HBox container = new HBox(10, nameLabel, spacer, deleteBtn);

            {
                nameLabel.getStyleClass().add("file-name");
                nameLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
                nameLabel.setEllipsisString("...");
                nameLabel.setMaxWidth(Double.MAX_VALUE);
                nameLabel.setMinWidth(0);
                HBox.setHgrow(nameLabel, Priority.ALWAYS);
                HBox.setHgrow(spacer, Priority.ALWAYS);
                container.setAlignment(Pos.CENTER_LEFT);
                container.getStyleClass().add("file-row");
                container.setMaxWidth(Double.MAX_VALUE);
                prefWidthProperty().bind(fileListView.widthProperty().subtract(16));
                setMaxWidth(Double.MAX_VALUE);

                deleteBtn.setGraphic(buildDeleteIcon());
                deleteBtn.getStyleClass().add("delete-btn");
                deleteBtn.setFocusTraversable(false);
                deleteBtn.setOnAction(event -> {
                    File target = getItem();
                    if (target != null && target.exists()) {
                        boolean removed = target.delete();
                        if (removed) {
                            fileListView.getItems().remove(target);
                        } else {
                            System.err.println("Failed to delete file: " + target.getAbsolutePath());
                        }
                    }
                    event.consume();
                });
            }

            @Override
            protected void updateItem(File item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    nameLabel.setText(item.getName());
                    setText(null);
                    setGraphic(container);
                }
            }
        });

        Scene scene = new Scene(root, 360, 600);
        scene.getStylesheets().add(getClass().getResource("styles.css").toExternalForm());
        primaryStage.setTitle("YT Downloader");
        primaryStage.setScene(scene);
        primaryStage.setAlwaysOnTop(true);
        primaryStage.show();
    }

    private void downloadVideo(String url, Button btn, SVGPath downloadIcon) {
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setPrefSize(18, 18);
        spinner.setMaxSize(18, 18);
        spinner.getStyleClass().add("button-spinner");

        btn.setDisable(true);
        btn.setGraphic(spinner);
        btn.getStyleClass().removeAll("success", "error");
        if (!btn.getStyleClass().contains("busy")) {
            btn.getStyleClass().add("busy");
        }

        new Thread(() -> {
            try {
                // シェルスクリプトと同じオプションを指定
                ProcessBuilder pb = new ProcessBuilder(
                        YT_DLP_PATH,
                        "--no-playlist",                // プレイリスト全体ではなく単体のみ
                        "-f", "bv+ba/b",                // 最高画質+最高音質、なければbest
                        "--merge-output-format", "mp4", // ffmpegを使ってmp4に結合
                        "-o", DOWNLOAD_DIR + "/%(title)s.%(ext)s", // 出力ファイル名規則
                        url
                );

                // 【重要】ffmpeg を認識させるためにPATHを通す
                // MacのHomebrewやIntel Mac標準のパスを環境変数に追加します
                String currentPath = System.getenv("PATH");
                if (currentPath == null) currentPath = "";
                pb.environment().put("PATH", currentPath + ":/usr/local/bin:/opt/homebrew/bin");

                pb.redirectErrorStream(true);
                Process process = pb.start();

                // ログ出力（デバッグ用）
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        System.out.println(line);
                    }
                }

                int exitCode = process.waitFor();

                Platform.runLater(() -> {
                    btn.getStyleClass().remove("busy");
                    if (exitCode == 0) {
                        btn.getStyleClass().remove("error");
                        if (!btn.getStyleClass().contains("success")) {
                            btn.getStyleClass().add("success");
                        }
                        updateFileList(); // リスト更新
                    } else {
                        btn.getStyleClass().remove("success");
                        if (!btn.getStyleClass().contains("error")) {
                            btn.getStyleClass().add("error");
                        }
                    }
                    // ボタン復帰処理
                    btn.setGraphic(downloadIcon);
                    new Thread(() -> {
                        try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                        Platform.runLater(() -> {
                            btn.setDisable(false);
                            btn.getStyleClass().removeAll("success", "error");
                            btn.setGraphic(downloadIcon);
                        });
                    }).start();
                });

            } catch (Exception ex) {
                ex.printStackTrace();
                Platform.runLater(() -> {
                    btn.getStyleClass().remove("busy");
                    if (!btn.getStyleClass().contains("error")) {
                        btn.getStyleClass().add("error");
                    }
                    btn.setGraphic(downloadIcon);
                    btn.setDisable(false);
                });
            }
        }).start();
    }

    private void updateFileList() {
        try {
            List<File> files = Files.list(Paths.get(DOWNLOAD_DIR))
                    .filter(Files::isRegularFile)
                    .map(Path::toFile)
                    .filter(f -> f.getName().endsWith(".mp4"))
                    .collect(Collectors.toList());
            Collections.reverse(files);
            fileListView.getItems().setAll(files);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        launch();
    }

    private SVGPath buildDownloadIcon() {
        SVGPath icon = new SVGPath();
        icon.setContent("M12 2v10h4l-6 6-6-6h4V2h4zm-8 18v2h16v-2H4z");
        icon.getStyleClass().add("download-icon");
        icon.setScaleX(1.05);
        icon.setScaleY(1.05);
        return icon;
    }

    private SVGPath buildDeleteIcon() {
        SVGPath icon = new SVGPath();
        icon.setContent("M18.3 5.71a1 1 0 00-1.41 0L12 10.59 7.11 5.7A1 1 0 105.7 7.11L10.59 12l-4.9 4.89a1 1 0 101.41 1.41L12 13.41l4.89 4.9a1 1 0 001.41-1.41L13.41 12l4.9-4.89a1 1 0 000-1.4z");
        icon.getStyleClass().add("delete-icon");
        icon.setScaleX(0.92);
        icon.setScaleY(0.92);
        return icon;
    }

    private void disableHorizontalScroll(ListView<?> listView) {
        listView.skinProperty().addListener((obs, oldSkin, newSkin) -> {
            if (newSkin == null) {
                return;
            }
            Platform.runLater(() -> listView.lookupAll(".scroll-bar").stream()
                    .filter(node -> node instanceof ScrollBar)
                    .map(node -> (ScrollBar) node)
                    .filter(sb -> sb.getOrientation() == Orientation.HORIZONTAL)
                    .forEach(sb -> {
                        sb.setManaged(false);
                        sb.setVisible(false);
                        sb.setDisable(true);
                        sb.setOpacity(0);
                        sb.setPrefHeight(0);
                    }));
        });

        listView.addEventFilter(ScrollEvent.SCROLL, event -> {
            if (event.getDeltaX() != 0) {
                event.consume();
            }
        });
    }
}
