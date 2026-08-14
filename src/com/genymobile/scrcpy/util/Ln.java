package com.genymobile.scrcpy.util;

import android.util.Log;

/**
 * Log to the Android logger (so that logs are visible in "adb logcat").
 * <p>
 * Unlike upstream scrcpy, there is no console output: this library runs inside a regular app process, not in a standalone process whose
 * stdout/stderr are piped to a terminal.
 */
public final class Ln {

    private static final String TAG = "scrcpy";

    public enum Level {
        VERBOSE, DEBUG, INFO, WARN, ERROR
    }

    private static Level threshold = Level.INFO;

    private Ln() {
        // not instantiable
    }

    /**
     * Initialize the log level.
     * <p>
     * Must be called before starting any new thread.
     *
     * @param level the log level
     */
    public static void initLogLevel(Level level) {
        threshold = level;
    }

    public static boolean isEnabled(Level level) {
        return level.ordinal() >= threshold.ordinal();
    }

    public static void v(String message) {
        if (isEnabled(Level.VERBOSE)) {
            Log.v(TAG, message);
        }
    }

    public static void d(String message) {
        if (isEnabled(Level.DEBUG)) {
            Log.d(TAG, message);
        }
    }

    public static void i(String message) {
        if (isEnabled(Level.INFO)) {
            Log.i(TAG, message);
        }
    }

    public static void w(String message, Throwable throwable) {
        if (isEnabled(Level.WARN)) {
            Log.w(TAG, message, throwable);
        }
    }

    public static void w(String message) {
        w(message, null);
    }

    public static void e(String message, Throwable throwable) {
        if (isEnabled(Level.ERROR)) {
            Log.e(TAG, message, throwable);
        }
    }

    public static void e(String message) {
        e(message, null);
    }
}
