package com.kyopan_pan.ytdownloader;

import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.io.File;
import java.net.URL;
import java.util.Collections;

public class HelloApplication extends Application {

    private final DownloadsManager downloadsManager = new DownloadsManager();
    private DownloadExecutor downloadExecutor;
    private TextField urlInput;
    private Button downloadBtn;
    private SVGPath downloadIcon;
    private SVGPath stopIcon;
    private SVGPath successIcon;
    private ListView<File> fileListView;
    private VBox progressBox;
    private Label progressLabel;
    private ProgressBar progressBar;

    @Override
    public void start(Stage primaryStage) {
        downloadsManager.ensureDownloadDirectory();

        // === 追加部分: バイナリの準備 ===
        // UIブロックを避けるため別スレッドで実行するか、
        // 本来はスプラッシュスクリーン等で待機させるべきですが、簡易的にここで呼び出します
        new Thread(() -> new DependencyManager().ensureBinaries()).start();

        urlInput = new TextField();
        urlInput.setPromptText("YouTube URL...");
        urlInput.getStyleClass().add("url-input");

        downloadIcon = IconFactory.createDownloadIcon();
        stopIcon = IconFactory.createStopIcon();
        successIcon = IconFactory.createSuccessIcon();

        downloadBtn = buildDownloadButton(downloadIcon);

        HBox inputRow = new HBox(12, urlInput, downloadBtn);
        inputRow.getStyleClass().add("input-row");
        HBox.setHgrow(urlInput, Priority.ALWAYS);

        buildProgressArea();

        fileListView = buildListView();
        refreshFileList();

        Label downloadsLabel = new Label("Downloads");
        downloadsLabel.getStyleClass().add("section-title");

        VBox root = new VBox(14, inputRow, progressBox, downloadsLabel, fileListView);
        root.getStyleClass().add("app");
        root.setPadding(new Insets(16));
        VBox.setVgrow(fileListView, Priority.ALWAYS);

        downloadExecutor = new DownloadExecutor(this::handleProgressUpdate);
        downloadBtn.setOnAction(e -> handleDownload(urlInput));
        urlInput.textProperty().addListener((obs, oldValue, newValue) -> {
            if (!downloadExecutor.isDownloadActive()) {
                resetDownloadButton();
            }
        });

        Scene scene = new Scene(root, 300, 1000);
        URL stylesheet = getClass().getResource("styles.css");
        if (stylesheet != null) {
            scene.getStylesheets().add(stylesheet.toExternalForm());
        } else {
            System.err.println("styles.css not found on classpath; skipping stylesheet load.");
        }
        primaryStage.setTitle("YT Downloader");
        primaryStage.setScene(scene);
        primaryStage.setAlwaysOnTop(true);
        primaryStage.setOnShown(event -> snapWindowToRight(primaryStage));
        primaryStage.show();
    }

    private void snapWindowToRight(Stage stage) {
        Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
        double margin = 12;
        double x = bounds.getMaxX() - stage.getWidth() - margin;
        double y = bounds.getMinY() + margin;
        stage.setX(x);
        stage.setY(Math.max(bounds.getMinY(), y));
    }

    private Button buildDownloadButton(SVGPath downloadIcon) {
        Button downloadBtn = new Button();
        downloadBtn.setAccessibleText("Download");
        downloadBtn.setGraphic(downloadIcon);
        downloadBtn.getStyleClass().add("download-btn");
        downloadBtn.setPrefWidth(56);
        downloadBtn.setPrefHeight(48);
        downloadBtn.setMinHeight(48);
        return downloadBtn;
    }

    private ListView<File> buildListView() {
        ListView<File> listView = new ListView<>();
        listView.getStyleClass().add("downloads-list");
        ScrollUtil.disableHorizontalScroll(listView);

        listView.setOnDragDetected(event -> {
            File selectedFile = listView.getSelectionModel().getSelectedItem();
            if (selectedFile != null) {
                Dragboard db = listView.startDragAndDrop(TransferMode.COPY);
                ClipboardContent content = new ClipboardContent();
                content.putFiles(Collections.singletonList(selectedFile));
                db.setContent(content);
                event.consume();
            }
        });

        listView.setCellFactory(param -> new DownloadListCell(this::handleDelete));
        return listView;
    }

    private void buildProgressArea() {
        progressLabel = new Label("待機中...");
        progressLabel.getStyleClass().add("progress-label");

        progressBar = new ProgressBar(0);
        progressBar.getStyleClass().add("progress-bar");
        progressBar.setMaxWidth(Double.MAX_VALUE);

        progressBox = new VBox(6, progressLabel, progressBar);
        progressBox.getStyleClass().addAll("progress-box", "idle");
    }

    private void handleDownload(TextField urlInput) {
        if (downloadExecutor.isDownloadActive()) {
            downloadExecutor.stopDownload(downloadBtn, downloadIcon);
            return;
        }
        String url = urlInput.getText();
        if (url != null && !url.isEmpty()) {
            downloadExecutor.download(url, downloadBtn, downloadIcon, stopIcon, successIcon, this::refreshFileList);
            urlInput.clear();
        }
    }

    private void resetDownloadButton() {
        downloadBtn.setDisable(false);
        downloadBtn.getStyleClass().removeAll("busy", "stop", "success", "error");
        downloadBtn.setGraphic(downloadIcon);
        downloadBtn.setAccessibleText("Download");
    }

    private void handleDelete(File target) {
        if (downloadsManager.deleteFile(target)) {
            refreshFileList();
        }
    }

    private void refreshFileList() {
        fileListView.getItems().setAll(downloadsManager.loadRecentVideos());
    }

    private void handleProgressUpdate(DownloadExecutor.ProgressUpdate update) {
        if (update == null) {
            return;
        }
        boolean active = update.visible();
        if (active) {
            progressLabel.setText(update.message());
            progressBox.getStyleClass().remove("idle");
            if (update.indeterminate()) {
                progressBar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
            } else {
                progressBar.setProgress(update.progress());
            }
        } else {
            progressLabel.setText("待機中...");
            progressBar.setProgress(0);
            if (!progressBox.getStyleClass().contains("idle")) {
                progressBox.getStyleClass().add("idle");
            }
        }
    }

    public static void main(String[] args) {
        launch();
    }
}
