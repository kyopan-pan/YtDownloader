module com.kyopan_pan.ytdownloader {
    requires javafx.controls;
    requires javafx.fxml;

    // SSL通信（GitHubからのダウンロード）のために必要
    requires jdk.crypto.ec;
    
    opens com.kyopan_pan.ytdownloader to javafx.fxml;
    exports com.kyopan_pan.ytdownloader;
}
