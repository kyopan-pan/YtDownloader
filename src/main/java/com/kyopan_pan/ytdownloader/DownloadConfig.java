package com.kyopan_pan.ytdownloader;

public final class DownloadConfig {

    // Macの「ムービー」フォルダへのパス
    public static final String DOWNLOAD_DIR = System.getProperty("user.home") + "/Movies/YtDlpDownloads";

    // 【重要】手順1で調べたパスに書き換えてください (例: /opt/homebrew/bin/yt-dlp)
    // ここが単に "yt-dlp" だとMacのGUIアプリからは動かないことが多いです
    public static final String YT_DLP_PATH = "/usr/local/bin/yt-dlp";

    private DownloadConfig() {
    }
}
