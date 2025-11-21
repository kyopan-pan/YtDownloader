package com.kyopan_pan.ytdownloader;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.stage.DirectoryChooser;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.io.File;
import java.net.URL;
import java.util.Collections;

public class HelloApplication extends Application {

    private Stage primaryStage;
    private UserSettings settings;
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
        this.primaryStage = primaryStage;
        settings = UserSettings.load();
        DownloadConfig.setDownloadDir(settings.getDownloadDirectory());
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
        MenuBar menuBar = buildMenuBar();

        HBox inputRow = new HBox(12, urlInput, downloadBtn);
        inputRow.getStyleClass().add("input-row");
        HBox.setHgrow(urlInput, Priority.ALWAYS);

        buildProgressArea();

        fileListView = buildListView();
        refreshFileList();

        Label downloadsLabel = new Label("Downloads");
        downloadsLabel.getStyleClass().add("section-title");

        VBox mainContent = new VBox(14, inputRow, progressBox, downloadsLabel, fileListView);
        mainContent.getStyleClass().add("app");
        mainContent.setPadding(new Insets(16));
        VBox.setVgrow(fileListView, Priority.ALWAYS);

        BorderPane root = new BorderPane();
        root.setTop(menuBar);
        root.setCenter(mainContent);

        downloadExecutor = new DownloadExecutor(this::handleProgressUpdate);
        downloadBtn.setOnAction(e -> handleDownload(urlInput));
        urlInput.textProperty().addListener((obs, oldValue, newValue) -> {
            if (!downloadExecutor.isDownloadActive()) {
                resetDownloadButton();
            }
        });

        Scene scene = new Scene(root, settings.getWindowWidth(), settings.getWindowHeight());
        URL stylesheet = getClass().getResource("styles.css");
        if (stylesheet != null) {
            scene.getStylesheets().add(stylesheet.toExternalForm());
        } else {
            System.err.println("styles.css not found on classpath; skipping stylesheet load.");
        }
        primaryStage.setTitle("YT Downloader");
        primaryStage.setScene(scene);
        primaryStage.setAlwaysOnTop(true);
        primaryStage.setWidth(settings.getWindowWidth());
        primaryStage.setHeight(settings.getWindowHeight());
        primaryStage.widthProperty().addListener((obs, oldValue, newValue) -> settings.setWindowWidth(newValue.doubleValue()));
        primaryStage.heightProperty().addListener((obs, oldValue, newValue) -> settings.setWindowHeight(newValue.doubleValue()));
        primaryStage.setOnCloseRequest(event -> settings.save());
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

    private MenuBar buildMenuBar() {
        MenuBar menuBar = new MenuBar();
        menuBar.setUseSystemMenuBar(true);

        Menu appMenu = new Menu("YT Downloader");

        MenuItem settingsItem = new MenuItem("設定...");
        settingsItem.setAccelerator(new KeyCodeCombination(KeyCode.COMMA, KeyCombination.META_DOWN));
        settingsItem.setOnAction(event -> openSettingsDialog());

        MenuItem quitItem = new MenuItem("終了");
        quitItem.setAccelerator(new KeyCodeCombination(KeyCode.Q, KeyCombination.META_DOWN));
        quitItem.setOnAction(event -> {
            settings.save();
            Platform.exit();
        });

        appMenu.getItems().addAll(settingsItem, quitItem);
        menuBar.getMenus().add(appMenu);
        return menuBar;
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

    private void openSettingsDialog() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("設定");
        dialog.initOwner(primaryStage);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        DependencyManager dependencyManager = new DependencyManager();
        TextField widthField = new TextField(String.valueOf((int) Math.round(settings.getWindowWidth())));
        TextField heightField = new TextField(String.valueOf((int) Math.round(settings.getWindowHeight())));
        TextField outputField = new TextField(settings.getDownloadDirectory());
        outputField.setPrefColumnCount(22);

        Button browseBtn = new Button("フォルダを選択");
        browseBtn.setOnAction(event -> {
            DirectoryChooser chooser = new DirectoryChooser();
            File current = new File(outputField.getText());
            if (current.exists()) {
                chooser.setInitialDirectory(current);
            }
            File selected = chooser.showDialog(primaryStage);
            if (selected != null) {
                outputField.setText(selected.getAbsolutePath());
            }
        });

        Label errorLabel = new Label();
        errorLabel.setStyle("-fx-text-fill: #f87171; -fx-font-size: 12px;");

        Label ytDlpVersionLabel = new Label("確認中...");
        ytDlpVersionLabel.setStyle("-fx-text-fill: #0ea5e9; -fx-font-weight: 700;");
        ProgressIndicator versionSpinner = new ProgressIndicator();
        versionSpinner.setPrefSize(16, 16);
        versionSpinner.setMaxSize(16, 16);
        versionSpinner.setVisible(false);
        versionSpinner.setManaged(false);
        Label versionStatusLabel = new Label("yt-dlpのバージョンを確認中...");
        versionStatusLabel.setStyle("-fx-text-fill: #94a3b8; -fx-font-size: 12px;");
        Button updateYtDlpBtn = new Button("最新を取得");
        updateYtDlpBtn.setOnAction(event -> updateYtDlpAsync(dependencyManager, ytDlpVersionLabel, versionStatusLabel, versionSpinner, updateYtDlpBtn));

        HBox versionRow = new HBox(8, ytDlpVersionLabel, versionSpinner, updateYtDlpBtn);
        versionRow.setAlignment(Pos.CENTER_LEFT);
        VBox versionBox = new VBox(6, versionRow, versionStatusLabel);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(12);
        grid.addRow(0, new Label("画面幅"), widthField);
        grid.addRow(1, new Label("画面高さ"), heightField);
        grid.add(new Label("出力先フォルダ"), 0, 2);
        grid.add(new HBox(8, outputField, browseBtn), 1, 2);
        grid.add(new Label("yt-dlp"), 0, 3);
        grid.add(versionBox, 1, 3);

        dialog.getDialogPane().setContent(new VBox(10, grid, errorLabel));

        Button okBtn = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        okBtn.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            String dirInput = outputField.getText() == null ? "" : outputField.getText().trim();
            File dir = dirInput.isEmpty() ? new File(DownloadConfig.getDefaultDownloadDir()) : new File(dirInput);
            if (!dir.exists() && !dir.mkdirs()) {
                errorLabel.setText("フォルダを作成できませんでした: " + dir.getAbsolutePath());
                event.consume();
                return;
            }

            Double width = parseDimension(widthField.getText());
            Double height = parseDimension(heightField.getText());
            if (width == null || height == null) {
                errorLabel.setText("画面の幅/高さは数値で入力してください。");
                event.consume();
                return;
            }

            errorLabel.setText("");
            settings.setWindowWidth(width);
            settings.setWindowHeight(height);
            settings.setDownloadDirectory(dir.getAbsolutePath());
            settings.save();
            downloadsManager.ensureDownloadDirectory();
            refreshFileList();
            primaryStage.setWidth(settings.getWindowWidth());
            primaryStage.setHeight(settings.getWindowHeight());
        });

        refreshYtDlpVersion(dependencyManager, ytDlpVersionLabel, versionStatusLabel, versionSpinner, updateYtDlpBtn);
        dialog.showAndWait();
    }

    private Double parseDimension(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return null;
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

    private void refreshYtDlpVersion(DependencyManager dependencyManager, Label versionLabel, Label statusLabel, ProgressIndicator spinner, Button updateButton) {
        versionLabel.setText("確認中...");
        statusLabel.setText("yt-dlpのバージョンを確認中...");
        spinner.setVisible(true);
        spinner.setManaged(true);
        spinner.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
        updateButton.setDisable(true);

        Thread loader = new Thread(() -> {
            DependencyManager.YtDlpVersionResult result = dependencyManager.getYtDlpVersion();
            Platform.runLater(() -> {
                spinner.setVisible(false);
                spinner.setManaged(false);
                updateButton.setDisable(false);
                if (result.success() && result.version() != null) {
                    versionLabel.setText(result.version());
                } else if (result.version() != null) {
                    versionLabel.setText(result.version());
                } else {
                    versionLabel.setText("未インストール");
                }
                statusLabel.setText(result.message());
            });
        });
        loader.setDaemon(true);
        loader.start();
    }

    private void updateYtDlpAsync(DependencyManager dependencyManager, Label versionLabel, Label statusLabel, ProgressIndicator spinner, Button updateButton) {
        spinner.setVisible(true);
        spinner.setManaged(true);
        spinner.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
        updateButton.setDisable(true);
        statusLabel.setText("yt-dlpを更新中...");

        Thread updater = new Thread(() -> {
            DependencyManager.YtDlpUpdateResult updateResult = dependencyManager.updateYtDlp();
            DependencyManager.YtDlpVersionResult versionResult = dependencyManager.getYtDlpVersion();

            Platform.runLater(() -> {
                spinner.setVisible(false);
                spinner.setManaged(false);
                updateButton.setDisable(false);
                if (versionResult.version() != null) {
                    versionLabel.setText(versionResult.version());
                } else {
                    versionLabel.setText("未インストール");
                }
                String message = updateResult.message();
                if (updateResult.success() && versionResult.version() != null) {
                    message = updateResult.message() + " (現在: " + versionResult.version() + ")";
                }
                statusLabel.setText(message);
            });
        });
        updater.setDaemon(true);
        updater.start();
    }
}
