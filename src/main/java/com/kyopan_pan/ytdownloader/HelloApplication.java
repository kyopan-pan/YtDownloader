package com.kyopan_pan.ytdownloader;

import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.stage.Stage;

import java.io.File;
import java.util.Collections;

public class HelloApplication extends Application {

    private final DownloadsManager downloadsManager = new DownloadsManager();
    private final DownloadExecutor downloadExecutor = new DownloadExecutor();
    private ListView<File> fileListView;

    @Override
    public void start(Stage primaryStage) {
        downloadsManager.ensureDownloadDirectory();

        TextField urlInput = new TextField();
        urlInput.setPromptText("YouTube URL...");
        urlInput.getStyleClass().add("url-input");

        SVGPath downloadIcon = IconFactory.createDownloadIcon();
        Button downloadBtn = buildDownloadButton(downloadIcon);

        HBox inputRow = new HBox(12, urlInput, downloadBtn);
        inputRow.getStyleClass().add("input-row");
        HBox.setHgrow(urlInput, Priority.ALWAYS);

        fileListView = buildListView();
        refreshFileList();

        Label downloadsLabel = new Label("Downloads");
        downloadsLabel.getStyleClass().add("section-title");

        VBox root = new VBox(14, inputRow, downloadsLabel, fileListView);
        root.getStyleClass().add("app");
        root.setPadding(new Insets(16));
        VBox.setVgrow(fileListView, Priority.ALWAYS);

        downloadBtn.setOnAction(e -> handleDownload(urlInput, downloadBtn, downloadIcon));

        Scene scene = new Scene(root, 360, 600);
        scene.getStylesheets().add(getClass().getResource("styles.css").toExternalForm());
        primaryStage.setTitle("YT Downloader");
        primaryStage.setScene(scene);
        primaryStage.setAlwaysOnTop(true);
        primaryStage.show();
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

    private void handleDownload(TextField urlInput, Button downloadBtn, SVGPath downloadIcon) {
        String url = urlInput.getText();
        if (url != null && !url.isEmpty()) {
            downloadExecutor.download(url, downloadBtn, downloadIcon, this::refreshFileList);
            urlInput.clear();
        }
    }

    private void handleDelete(File target) {
        if (downloadsManager.deleteFile(target)) {
            refreshFileList();
        }
    }

    private void refreshFileList() {
        fileListView.getItems().setAll(downloadsManager.loadRecentVideos());
    }

    public static void main(String[] args) {
        launch();
    }
}
