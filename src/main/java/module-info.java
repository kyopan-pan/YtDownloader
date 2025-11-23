module com.kyopan_pan.ytdownloader {
    requires javafx.controls;
    requires javafx.fxml;
    
    opens com.kyopan_pan.ytdownloader to javafx.fxml;
    exports com.kyopan_pan.ytdownloader;
}
