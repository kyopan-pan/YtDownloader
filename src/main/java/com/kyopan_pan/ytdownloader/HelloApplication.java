package com.kyopan_pan.ytdownloader;

import java.io.File;
import java.net.URL;
import java.time.Duration;
import java.util.Collections;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
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
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.stage.DirectoryChooser;
import javafx.stage.Screen;
import javafx.stage.Stage;

public class HelloApplication extends Application {

    private Stage primaryStage;
    private UserSettings settings;
    private final DownloadsManager downloadsManager = new DownloadsManager();
    private final DependencyManager dependencyManager = new DependencyManager();
    private DownloadExecutor downloadExecutor;
    private Button downloadBtn;
    private SVGPath stopIcon;
    private SVGPath successIcon;
    private ListView<File> fileListView;
    private VBox progressBox;
    private Label progressLabel;
    private ProgressBar progressBar;
    private Stage logStage;
    private ListView<String> logListView;
    private String stylesheetUrl;
    private Dialog<ButtonType> setupDialog;

    @Override
    public void start(Stage primaryStage) {
        this.primaryStage = primaryStage;
        settings = UserSettings.load();
        DownloadConfig.setDownloadDir(settings.getDownloadDirectory());
        downloadsManager.ensureDownloadDirectory();

        // === 追加部分: バイナリの準備 ===
        // UIブロックを避けるため別スレッドで実行するか、
        // 本来はスプラッシュスクリーン等で待機させるべきですが、簡易的にここで呼び出します
        new Thread(dependencyManager::ensureBinaries).start();

        stopIcon = IconFactory.createStopIcon();
        successIcon = IconFactory.createSuccessIcon();

        downloadBtn = buildDownloadButton();
        MenuBar menuBar = buildMenuBar();

        buildProgressArea();

        fileListView = buildListView();
        refreshFileList();

        Label downloadsLabel = new Label("Downloads");
        downloadsLabel.getStyleClass().add("section-title");

        VBox mainContent = new VBox(14, downloadBtn, progressBox, downloadsLabel, fileListView);
        mainContent.getStyleClass().add("app");
        mainContent.setPadding(new Insets(16));
        VBox.setVgrow(fileListView, Priority.ALWAYS);

        BorderPane root = new BorderPane();
        root.setTop(menuBar);
        root.setCenter(mainContent);

        downloadExecutor = new DownloadExecutor(this::handleProgressUpdate);
        downloadBtn.setOnAction(e -> handleDownload());

        Scene scene = new Scene(root, settings.getWindowWidth(), settings.getWindowHeight());
        URL stylesheet = getClass().getResource("styles.css");
        if (stylesheet != null) {
            stylesheetUrl = stylesheet.toExternalForm();
            scene.getStylesheets().add(stylesheetUrl);
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
        Platform.runLater(this::maybeShowInitialSetupUi);
    }

    private void snapWindowToRight(Stage stage) {
        Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
        double margin = 12;
        double x = bounds.getMaxX() - stage.getWidth() - margin;
        double y = bounds.getMinY() + margin;
        stage.setX(x);
        stage.setY(Math.max(bounds.getMinY(), y));
    }

    private Button buildDownloadButton() {
        Button btn = new Button("Download");
        btn.setAccessibleText("Download");
        btn.getStyleClass().add("download-btn");
        btn.setPrefHeight(48);
        btn.setMinHeight(48);
        btn.setMaxWidth(Double.MAX_VALUE);
        return btn;
    }

    private MenuBar buildMenuBar() {
        MenuBar menuBar = new MenuBar();
        menuBar.setUseSystemMenuBar(true);

        Menu appMenu = new Menu("YT Downloader");

        MenuItem settingsItem = new MenuItem("設定...");
        settingsItem.setAccelerator(new KeyCodeCombination(KeyCode.COMMA, KeyCombination.META_DOWN));
        settingsItem.setOnAction(event -> openSettingsDialog());

        MenuItem logsItem = new MenuItem("ログ...");
        logsItem.setAccelerator(new KeyCodeCombination(KeyCode.L, KeyCombination.META_DOWN));
        logsItem.setOnAction(event -> openLogWindow());

        MenuItem quitItem = new MenuItem("終了");
        quitItem.setAccelerator(new KeyCodeCombination(KeyCode.Q, KeyCombination.META_DOWN));
        quitItem.setOnAction(event -> {
            settings.save();
            Platform.exit();
        });

        appMenu.getItems().addAll(settingsItem, logsItem, new SeparatorMenuItem(), quitItem);
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

    private void handleDownload() {
        if (downloadExecutor.isDownloadActive()) {
            downloadExecutor.stopDownload(downloadBtn);
            return;
        }
        String url = getUrlFromClipboard();
        if (url != null && !url.isEmpty()) {
            if (!ensureYtDlpConfigured()) {
                return;
            }
            downloadExecutor.download(url, downloadBtn, null, stopIcon, successIcon, this::refreshFileList);
        }
    }

    private String getUrlFromClipboard() {
        Clipboard clipboard = Clipboard.getSystemClipboard();
        if (clipboard.hasString()) {
            return clipboard.getString().trim();
        }
        return null;
    }

    private void resetDownloadButton() {
        downloadBtn.setDisable(false);
        downloadBtn.getStyleClass().removeAll("busy", "stop", "success", "error");
        downloadBtn.setGraphic(null);
        downloadBtn.setText("Download");
        downloadBtn.setAccessibleText("Download");
    }

    private void maybeShowInitialSetupUi() {
        if (!isYtDlpConfigured()) {
            AppLogger.log("[HelloApplication] yt-dlp not configured. Showing initial setup dialog.");
            showInitialSetupDialog();
        }
    }

    private boolean ensureYtDlpConfigured() {
        if (isYtDlpConfigured()) {
            return true;
        }
        AppLogger.log("[HelloApplication] Download blocked until initial setup completes.");
        showInitialSetupDialog();
        return false;
    }

    private boolean isYtDlpConfigured() {
        File ytDlp = new File(DownloadConfig.getYtDlpPath());
        return ytDlp.exists() && ytDlp.canExecute();
    }

    private void showInitialSetupDialog() {
        if (setupDialog != null && setupDialog.isShowing()) {
            AppLogger.log("[HelloApplication] Initial setup dialog is already open; focusing existing window.");
            if (setupDialog.getDialogPane().getScene() != null && setupDialog.getDialogPane().getScene().getWindow() != null) {
                if (setupDialog.getDialogPane().getScene().getWindow() instanceof Stage stage) {
                    stage.toFront();
                    stage.requestFocus();
                }
            }
            return;
        }

        AppLogger.log("[HelloApplication] Opening initial setup dialog.");

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("初回セットアップ");
        dialog.initOwner(primaryStage);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.getDialogPane().getStyleClass().add("glass-dialog");
        dialog.getDialogPane().setPrefWidth(540);
        if (stylesheetUrl != null) {
            dialog.getDialogPane().getStylesheets().add(stylesheetUrl);
        }

        Label heading = new Label("yt-dlpのセットアップ");
        heading.getStyleClass().add("dialog-heading");

        Label description = new Label("初回起動ではyt-dlpのダウンロードと実行権限の付与が必要です。ボタン一つで最新を取得して、すぐにダウンロードを開始できます。");
        description.setWrapText(true);
        description.getStyleClass().add("dialog-subtitle");

        StackPane heroIcon = new StackPane();
        heroIcon.getStyleClass().add("hero-icon");
        Label heroGlyph = new Label("YD");
        heroGlyph.getStyleClass().add("hero-glyph");
        heroIcon.getChildren().add(heroGlyph);

        Label badge = new Label("必須");
        badge.getStyleClass().addAll("pill", "pill-warning");

        Region heroSpacer = new Region();
        HBox.setHgrow(heroSpacer, Priority.ALWAYS);
        VBox heroText = new VBox(6, heading, description);
        heroText.setAlignment(Pos.CENTER_LEFT);
        HBox heroRow = new HBox(14, heroIcon, heroText, heroSpacer, badge);
        heroRow.setAlignment(Pos.CENTER_LEFT);
        heroRow.getStyleClass().add("dialog-hero");

        // yt-dlp セクション
        VersionControls ytDlpSetupControls = createVersionControls("yt-dlpの状態を確認中...", "自動セットアップ");
        Label ytDlpVersionLabel = ytDlpSetupControls.versionLabel();
        Label ytDlpStatusLabel = ytDlpSetupControls.statusLabel();
        ProgressIndicator ytDlpSpinner = ytDlpSetupControls.spinner();
        Button ytDlpInstallButton = ytDlpSetupControls.actionButton();
        ytDlpInstallButton.setOnAction(event -> {
            AppLogger.log("[HelloApplication] Initial setup: yt-dlp auto setup triggered.");
            updateYtDlpAsync(dependencyManager, ytDlpVersionLabel, ytDlpStatusLabel, ytDlpSpinner, ytDlpInstallButton);
        });

        Label ytDlpStatusHeading = new Label("yt-dlpの状態");
        ytDlpStatusHeading.getStyleClass().add("info-title");
        HBox ytDlpVersionRow = new HBox(8, ytDlpVersionLabel, ytDlpSpinner, ytDlpInstallButton);
        ytDlpVersionRow.setAlignment(Pos.CENTER_LEFT);

        GridPane ytDlpStatusGrid = buildStatusGrid(ytDlpVersionRow, ytDlpStatusLabel, 12);

        VBox ytDlpCard = new VBox(10, ytDlpStatusHeading, ytDlpStatusGrid);
        ytDlpCard.getStyleClass().add("info-card");

        // Deno セクション
        VersionControls denoSetupControls = createVersionControls("Denoの状態を確認中...", "自動セットアップ");
        Label denoVersionLabel = denoSetupControls.versionLabel();
        Label denoStatusLabel = denoSetupControls.statusLabel();
        ProgressIndicator denoSpinner = denoSetupControls.spinner();
        Button denoInstallButton = denoSetupControls.actionButton();
        denoInstallButton.setOnAction(event -> {
            AppLogger.log("[HelloApplication] Initial setup: Deno auto setup triggered.");
            updateDenoAsync(dependencyManager, denoVersionLabel, denoStatusLabel, denoSpinner, denoInstallButton);
        });

        Label denoStatusHeading = new Label("Denoの状態");
        denoStatusHeading.getStyleClass().add("info-title");
        HBox denoVersionRow = new HBox(8, denoVersionLabel, denoSpinner, denoInstallButton);
        denoVersionRow.setAlignment(Pos.CENTER_LEFT);

        GridPane denoStatusGrid = buildStatusGrid(denoVersionRow, denoStatusLabel, 12);

        VBox denoCard = new VBox(10, denoStatusHeading, denoStatusGrid);
        denoCard.getStyleClass().add("info-card");

        Button openSettingsButton = new Button("設定を開く");
        openSettingsButton.getStyleClass().add("ghost-btn");
        openSettingsButton.setOnAction(event -> openSettingsDialog());

        Region actionsSpacer = new Region();
        HBox.setHgrow(actionsSpacer, Priority.ALWAYS);
        HBox actionsRow = new HBox(10, actionsSpacer, openSettingsButton);
        actionsRow.setAlignment(Pos.CENTER_RIGHT);

        VBox content = new VBox(16, heroRow, ytDlpCard, denoCard, actionsRow);
        content.getStyleClass().add("dialog-content");
        content.setPadding(new Insets(12, 6, 6, 6));

        dialog.getDialogPane().setContent(content);
        dialog.setResizable(false);
        dialog.setOnHidden(event -> setupDialog = null);

        refreshYtDlpVersion(dependencyManager, ytDlpVersionLabel, ytDlpStatusLabel, ytDlpSpinner, ytDlpInstallButton);
        refreshDenoVersion(dependencyManager, denoVersionLabel, denoStatusLabel, denoSpinner, denoInstallButton);

        setupDialog = dialog;
        dialog.show();
    }

    private void openSettingsDialog() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("設定");
        dialog.initOwner(primaryStage);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.getDialogPane().getStyleClass().add("glass-dialog");
        dialog.getDialogPane().setPrefWidth(600);
        if (stylesheetUrl != null) {
            dialog.getDialogPane().getStylesheets().add(stylesheetUrl);
        }

        TextField widthField = new TextField(String.valueOf((int) Math.round(settings.getWindowWidth())));
        TextField heightField = new TextField(String.valueOf((int) Math.round(settings.getWindowHeight())));
        TextField outputField = new TextField(settings.getDownloadDirectory());
        CheckBox useBrowserCookiesCheck = new CheckBox("ブラウザのクッキーを使う（bot確認対策）");
        TextField cookieBrowserField = new TextField(settings.getCookiesBrowser());
        TextField cookieProfileField = new TextField(settings.getCookiesProfile());
        widthField.getStyleClass().add("settings-field");
        heightField.getStyleClass().add("settings-field");
        outputField.getStyleClass().add("settings-field");
        cookieBrowserField.getStyleClass().add("settings-field");
        cookieProfileField.getStyleClass().add("settings-field");
        outputField.setPrefColumnCount(22);
        useBrowserCookiesCheck.setSelected(settings.isUseBrowserCookies());
        cookieBrowserField.setPromptText("例: chrome / firefox / safari");
        cookieProfileField.setPromptText("例: Default / Profile 1");

        Runnable syncCookieFields = () -> {
            boolean enabled = useBrowserCookiesCheck.isSelected();
            cookieBrowserField.setDisable(!enabled);
            cookieProfileField.setDisable(!enabled);
        };
        syncCookieFields.run();
        useBrowserCookiesCheck.selectedProperty().addListener((obs, oldValue, newValue) -> syncCookieFields.run());

        Button browseBtn = new Button("フォルダを選択");
        browseBtn.getStyleClass().add("ghost-btn");
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
        errorLabel.getStyleClass().add("form-error");

        // yt-dlp セクション
        VersionControls ytDlpControls = createVersionControls("yt-dlpのバージョンを確認中...", "最新を取得");
        Label ytDlpVersionLabel = ytDlpControls.versionLabel();
        ProgressIndicator ytDlpSpinner = ytDlpControls.spinner();
        Label ytDlpStatusLabel = ytDlpControls.statusLabel();
        Button updateYtDlpBtn = ytDlpControls.actionButton();
        updateYtDlpBtn.setOnAction(event -> updateYtDlpAsync(dependencyManager, ytDlpVersionLabel, ytDlpStatusLabel, ytDlpSpinner, updateYtDlpBtn));

        HBox ytDlpVersionRow = new HBox(8, ytDlpVersionLabel, ytDlpSpinner, updateYtDlpBtn);
        ytDlpVersionRow.setAlignment(Pos.CENTER_LEFT);

        GridPane ytDlpStatusGrid = buildStatusGrid(ytDlpVersionRow, ytDlpStatusLabel, 10);

        Label ytDlpTitle = new Label("yt-dlp");
        ytDlpTitle.getStyleClass().add("info-title");
        VBox ytDlpBox = new VBox(10, ytDlpTitle, ytDlpStatusGrid);
        ytDlpBox.getStyleClass().add("info-card");

        // Deno セクション
        VersionControls denoControls = createVersionControls("Denoのバージョンを確認中...", "最新を取得");
        Label denoVersionLabel = denoControls.versionLabel();
        ProgressIndicator denoSpinner = denoControls.spinner();
        Label denoStatusLabel = denoControls.statusLabel();
        Button updateDenoBtn = denoControls.actionButton();
        updateDenoBtn.setOnAction(event -> updateDenoAsync(dependencyManager, denoVersionLabel, denoStatusLabel, denoSpinner, updateDenoBtn));

        HBox denoVersionRow = new HBox(8, denoVersionLabel, denoSpinner, updateDenoBtn);
        denoVersionRow.setAlignment(Pos.CENTER_LEFT);

        GridPane denoStatusGrid = buildStatusGrid(denoVersionRow, denoStatusLabel, 10);

        Label denoTitle = new Label("Deno");
        denoTitle.getStyleClass().add("info-title");
        VBox denoBox = new VBox(10, denoTitle, denoStatusGrid);
        denoBox.getStyleClass().add("info-card");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(12);
        Label widthLabel = new Label("画面幅");
        Label heightLabel = new Label("画面高さ");
        Label folderLabel = new Label("出力先フォルダ");
        widthLabel.getStyleClass().add("muted-label");
        heightLabel.getStyleClass().add("muted-label");
        folderLabel.getStyleClass().add("muted-label");
        grid.addRow(0, widthLabel, widthField);
        grid.addRow(1, heightLabel, heightField);
        grid.add(folderLabel, 0, 2);
        HBox outputRow = new HBox(8, outputField, browseBtn);
        outputRow.setAlignment(Pos.CENTER_LEFT);
        grid.add(outputRow, 1, 2);
        grid.getStyleClass().add("settings-grid");

        Label heading = new Label("アプリ設定");
        heading.getStyleClass().add("dialog-heading");
        Label subtitle = new Label("ウィンドウサイズ、保存先、依存ツールの状態をまとめて管理します。");
        subtitle.setWrapText(true);
        subtitle.getStyleClass().add("dialog-subtitle");

        VBox windowSection = new VBox(8, grid);
        windowSection.getStyleClass().add("settings-section");

        Label cookiesHeading = new Label("YouTube認証");
        cookiesHeading.getStyleClass().add("info-title");
        Label cookiesSubtitle = new Label("bot確認が出る場合のみ有効化してください。ブラウザ名とプロファイルはyt-dlpの--cookies-from-browserに渡されます。");
        cookiesSubtitle.setWrapText(true);
        cookiesSubtitle.getStyleClass().add("muted-label");

        GridPane cookieGrid = new GridPane();
        cookieGrid.setHgap(10);
        cookieGrid.setVgap(12);
        Label cookieBrowserLabel = new Label("ブラウザ名");
        Label cookieProfileLabel = new Label("プロファイル");
        cookieBrowserLabel.getStyleClass().add("muted-label");
        cookieProfileLabel.getStyleClass().add("muted-label");
        cookieGrid.addRow(0, cookieBrowserLabel, cookieBrowserField);
        cookieGrid.addRow(1, cookieProfileLabel, cookieProfileField);
        cookieGrid.getStyleClass().add("settings-grid");

        VBox cookiesSection = new VBox(8, cookiesHeading, cookiesSubtitle, useBrowserCookiesCheck, cookieGrid);
        cookiesSection.getStyleClass().add("settings-section");

        VBox content = new VBox(14, heading, subtitle, windowSection, cookiesSection, ytDlpBox, denoBox, errorLabel);
        content.getStyleClass().add("dialog-content");
        content.setPadding(new Insets(8, 6, 6, 6));

        dialog.getDialogPane().setContent(content);

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

            boolean useCookies = useBrowserCookiesCheck.isSelected();
            String cookieBrowserInput = cookieBrowserField.getText() == null ? "" : cookieBrowserField.getText().trim();
            String cookieProfileInput = cookieProfileField.getText() == null ? "" : cookieProfileField.getText().trim();
            if (useCookies && cookieBrowserInput.isEmpty()) {
                errorLabel.setText("ブラウザ名を入力してください。");
                event.consume();
                return;
            }

            errorLabel.setText("");
            settings.setWindowWidth(width);
            settings.setWindowHeight(height);
            settings.setDownloadDirectory(dir.getAbsolutePath());
            settings.setUseBrowserCookies(useCookies);
            settings.setCookiesBrowser(cookieBrowserInput);
            settings.setCookiesProfile(cookieProfileInput);
            settings.save();
            downloadsManager.ensureDownloadDirectory();
            refreshFileList();
            primaryStage.setWidth(settings.getWindowWidth());
            primaryStage.setHeight(settings.getWindowHeight());
        });

        refreshYtDlpVersion(dependencyManager, ytDlpVersionLabel, ytDlpStatusLabel, ytDlpSpinner, updateYtDlpBtn);
        refreshDenoVersion(dependencyManager, denoVersionLabel, denoStatusLabel, denoSpinner, updateDenoBtn);
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

    private VersionControls createVersionControls(String initialStatusText, String actionText) {
        Label versionLabel = new Label("確認中...");
        versionLabel.getStyleClass().addAll("pill", "pill-accent");

        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setPrefSize(16, 16);
        spinner.setMaxSize(16, 16);
        spinner.setVisible(false);
        spinner.setManaged(false);

        Label statusLabel = new Label(initialStatusText);
        statusLabel.getStyleClass().add("muted-label");

        Button actionButton = new Button(actionText);
        actionButton.getStyleClass().add("primary-btn");

        return new VersionControls(versionLabel, statusLabel, spinner, actionButton);
    }

    private GridPane buildStatusGrid(Node versionRow, Label statusLabel, double hGap) {
        GridPane statusGrid = new GridPane();
        statusGrid.setHgap(hGap);
        statusGrid.setVgap(8);
        Label versionKey = new Label("バージョン");
        versionKey.getStyleClass().add("muted-label");
        Label statusKey = new Label("ステータス");
        statusKey.getStyleClass().add("muted-label");
        statusGrid.addRow(0, versionKey, versionRow);
        statusGrid.addRow(1, statusKey, statusLabel);
        return statusGrid;
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

    private void refreshDenoVersion(DependencyManager dependencyManager, Label versionLabel, Label statusLabel, ProgressIndicator spinner, Button updateButton) {
        versionLabel.setText("確認中...");
        statusLabel.setText("Denoのバージョンを確認中...");
        spinner.setVisible(true);
        spinner.setManaged(true);
        spinner.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
        updateButton.setDisable(true);

        Thread loader = new Thread(() -> {
            DependencyManager.DenoVersionResult result = dependencyManager.getDenoVersion();
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

    private void updateDenoAsync(DependencyManager dependencyManager, Label versionLabel, Label statusLabel, ProgressIndicator spinner, Button updateButton) {
        spinner.setVisible(true);
        spinner.setManaged(true);
        spinner.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
        updateButton.setDisable(true);
        statusLabel.setText("Denoを更新中...");

        Thread updater = new Thread(() -> {
            DependencyManager.DenoUpdateResult updateResult = dependencyManager.updateDeno();
            DependencyManager.DenoVersionResult versionResult = dependencyManager.getDenoVersion();

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

    private record VersionControls(Label versionLabel, Label statusLabel, ProgressIndicator spinner,
                                   Button actionButton) {
    }

    private void openLogWindow() {
        if (logStage == null) {
            logListView = new ListView<>();
            logListView.getStyleClass().add("log-list");
            logListView.setItems(AppLogger.getLogs());
            ScrollUtil.disableHorizontalScroll(logListView);

            AppLogger.getLogs().addListener((ListChangeListener<String>) change -> {
                if (logListView != null && !AppLogger.getLogs().isEmpty()) {
                    logListView.scrollTo(AppLogger.getLogs().size() - 1);
                }
            });

            Label header = new Label("ログ");
            header.getStyleClass().add("log-header");
            Label subtitle = new Label("アプリを終了するとログはクリアされます。");
            subtitle.getStyleClass().add("log-subtitle");

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            Button clearBtn = new Button("表示をクリア");
            clearBtn.getStyleClass().add("log-clear-btn");
            clearBtn.setOnAction(event -> AppLogger.clear());

            Button copyBtn = new Button("直近10分をコピー");
            copyBtn.getStyleClass().add("log-copy-btn");
            copyBtn.setOnAction(event -> {
                String recentLogs = AppLogger.buildRecentLogSnapshot(Duration.ofMinutes(10));
                ClipboardContent content = new ClipboardContent();
                content.putString(recentLogs);
                Clipboard.getSystemClipboard().setContent(content);
            });

            HBox actionRow = new HBox(10, subtitle, spacer, copyBtn, clearBtn);
            actionRow.setAlignment(Pos.CENTER_LEFT);

            VBox root = new VBox(10, header, logListView, actionRow);
            root.getStyleClass().add("log-root");
            root.setPadding(new Insets(12));
            VBox.setVgrow(logListView, Priority.ALWAYS);

            Scene logScene = new Scene(root, 760, 460);
            if (stylesheetUrl != null) {
                logScene.getStylesheets().add(stylesheetUrl);
            }

            logStage = new Stage();
            logStage.setTitle("ログ");
            logStage.initOwner(primaryStage);
            logStage.setScene(logScene);
        }
        logStage.show();
        logStage.toFront();
    }
}
