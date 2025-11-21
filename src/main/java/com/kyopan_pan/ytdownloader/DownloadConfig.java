package com.kyopan_pan.ytdownloader;

import java.io.File;

public final class DownloadConfig {

    // Mac標準の作法に近い隠しフォルダ、またはApplication Support推奨ですが、今回はシンプルに隠しフォルダにします
    public static final String APP_DATA_DIR = System.getProperty("user.home") + File.separator + ".ytdownloader";
    public static final String BIN_DIR = APP_DATA_DIR + File.separator + "bin";

    // ダウンロード保存先（変更なし）
    public static final String DOWNLOAD_DIR = System.getProperty("user.home") + "/Movies/YtDlpDownloads";

    // バイナリのパスを動的に生成
    public static String getYtDlpPath() {
        return new File(BIN_DIR, "yt-dlp").getAbsolutePath();
    }

    public static String getFfmpegPath() {
        return new File(BIN_DIR, "ffmpeg").getAbsolutePath();
    }

    private DownloadConfig() {
    }
}
