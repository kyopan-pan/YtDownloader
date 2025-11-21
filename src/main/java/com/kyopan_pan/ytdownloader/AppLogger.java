package com.kyopan_pan.ytdownloader;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Session-scoped logger that keeps logs in memory so they can be shown inside the app.
 * Logs are also echoed to stdout to preserve the previous behavior.
 */
public final class AppLogger {

    private static final int MAX_ENTRIES = 1000;
    private static final ObservableList<String> LOGS = FXCollections.observableArrayList();
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private AppLogger() {
    }

    public static ObservableList<String> getLogs() {
        return LOGS;
    }

    public static void clear() {
        runOnFxThread(LOGS::clear);
    }

    public static void log(String message) {
        if (message == null) {
            return;
        }
        String entry = "[" + LocalTime.now().format(TIME_FORMAT) + "] " + message;
        runOnFxThread(() -> append(entry));
        System.out.println(entry);
    }

    private static void append(String entry) {
        LOGS.add(entry);
        int overflow = LOGS.size() - MAX_ENTRIES;
        if (overflow > 0) {
            LOGS.remove(0, overflow);
        }
    }

    private static void runOnFxThread(Runnable task) {
        if (Platform.isFxApplicationThread()) {
            task.run();
        } else {
            Platform.runLater(task);
        }
    }
}
