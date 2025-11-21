package com.kyopan_pan.ytdownloader;

import javafx.application.Platform;
import javafx.geometry.Orientation;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollBar;
import javafx.scene.input.ScrollEvent;

public final class ScrollUtil {

    public static void disableHorizontalScroll(ListView<?> listView) {
        listView.skinProperty().addListener((obs, oldSkin, newSkin) -> {
            if (newSkin == null) {
                return;
            }
            Platform.runLater(() -> listView.lookupAll(".scroll-bar").stream()
                    .filter(node -> node instanceof ScrollBar)
                    .map(node -> (ScrollBar) node)
                    .filter(sb -> sb.getOrientation() == Orientation.HORIZONTAL)
                    .forEach(sb -> {
                        sb.setManaged(false);
                        sb.setVisible(false);
                        sb.setDisable(true);
                        sb.setOpacity(0);
                        sb.setPrefHeight(0);
                    }));
        });

        listView.addEventFilter(ScrollEvent.SCROLL, event -> {
            if (event.getDeltaX() != 0) {
                event.consume();
            }
        });
    }

    private ScrollUtil() {
    }
}
