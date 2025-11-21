package com.kyopan_pan.ytdownloader;

import java.io.File;

public final class DownloadConfig {

    // Mac標準の作法に近い隠しフォルダ、またはApplication Support推奨ですが、今回はシンプルに隠しフォルダにします
    public static final String APP_DATA_DIR = System.getProperty("user.home") + File.separator + ".ytdownloader";
    public static final String BIN_DIR = APP_DATA_DIR + File.separator + "bin";

    private static final String DEFAULT_DOWNLOAD_DIR = System.getProperty("user.home") + "/Movies/YtDlpDownloads";
    private static String downloadDir = DEFAULT_DOWNLOAD_DIR;

    // バイナリのパスを動的に生成
    public static String getYtDlpPath() {
        return new File(BIN_DIR, "yt-dlp").getAbsolutePath();
    }

    public static String getFfmpegPath() {
        return new File(BIN_DIR, "ffmpeg").getAbsolutePath();
    }

    public static synchronized String getDownloadDir() {
        return downloadDir;
    }

    public static synchronized void setDownloadDir(String newDir) {
        if (newDir == null || newDir.isBlank()) {
            downloadDir = DEFAULT_DOWNLOAD_DIR;
            return;
        }
        downloadDir = new File(newDir).getAbsolutePath();
    }

    public static String getDefaultDownloadDir() {
        return DEFAULT_DOWNLOAD_DIR;
    }

    private DownloadConfig() {
    }
}
