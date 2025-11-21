package com.kyopan_pan.ytdownloader;

import javafx.scene.shape.SVGPath;

public final class IconFactory {

    public static SVGPath createDownloadIcon() {
        SVGPath icon = new SVGPath();
        icon.setContent("M12 2v10h4l-6 6-6-6h4V2h4zm-8 18v2h16v-2H4z");
        icon.getStyleClass().add("download-icon");
        icon.setScaleX(1.05);
        icon.setScaleY(1.05);
        return icon;
    }

    public static SVGPath createDeleteIcon() {
        SVGPath icon = new SVGPath();
        icon.setContent("M18.3 5.71a1 1 0 00-1.41 0L12 10.59 7.11 5.7A1 1 0 105.7 7.11L10.59 12l-4.9 4.89a1 1 0 101.41 1.41L12 13.41l4.89 4.9a1 1 0 001.41-1.41L13.41 12l4.9-4.89a1 1 0 000-1.4z");
        icon.getStyleClass().add("delete-icon");
        icon.setScaleX(0.92);
        icon.setScaleY(0.92);
        return icon;
    }

    private IconFactory() {
    }
}
