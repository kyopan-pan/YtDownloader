package com.kyopan_pan.ytdownloader;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * アプリ内で表示できるよう、セッション中のログをメモリに保持するロガー。
 * 以前の挙動を保つため標準出力にも同時に出力する。
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

    public static void logError(String message, Throwable throwable) {
        if (throwable == null) {
            log(message);
            return;
        }
        StringBuilder builder = new StringBuilder();
        if (message != null && !message.isBlank()) {
            builder.append(message);
        } else {
            builder.append("想定外の例外");
        }
        builder.append(" (").append(throwable.getClass().getSimpleName()).append(')');
        String errorMessage = throwable.getMessage();
        if (errorMessage != null && !errorMessage.isBlank()) {
            builder.append(": ").append(errorMessage);
        }
        log(builder.toString());
        logStackTrace(throwable);
    }

    private static void logStackTrace(Throwable throwable) {
        logThrowable(throwable, "");
    }

    private static void logThrowable(Throwable throwable, String prefix) {
        log(prefix + throwable);
        for (StackTraceElement element : throwable.getStackTrace()) {
            log(prefix + "\tat " + element);
        }
        for (Throwable suppressed : throwable.getSuppressed()) {
            log(prefix + "Suppressed: " + suppressed);
            logThrowable(suppressed, prefix + "\t");
        }
        Throwable cause = throwable.getCause();
        if (cause != null && cause != throwable) {
            log(prefix + "Caused by: " + cause);
            logThrowable(cause, prefix + "\t");
        }
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
