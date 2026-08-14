package com.genymobile.scrcpy.util;

import android.content.Context;

/**
 * Holds the application Context of the hosting app (the "互联服务" privapp).
 * <p>
 * Unlike upstream scrcpy (which runs as a standalone shell process and needs a fake Context), this library runs in-process inside a regular
 * app, so a real Context is always available. It is initialized once by {@code CarLinkServer.start()}.
 */
public final class AppContext {

    private static volatile Context context;

    private AppContext() {
        // not instantiable
    }

    /**
     * Initialize the global application Context.
     * <p>
     * Must be called before any other component of this library is used. Subsequent calls are ignored (the first Context wins).
     *
     * @param appContext an application Context (not an Activity Context, to avoid leaks)
     */
    public static void init(Context appContext) {
        if (appContext == null) {
            throw new NullPointerException("appContext must not be null");
        }
        if (context == null) {
            synchronized (AppContext.class) {
                if (context == null) {
                    context = appContext;
                }
            }
        }
    }

    /**
     * @return the application Context
     * @throws IllegalStateException if called before {@link #init(Context)}
     */
    public static Context get() {
        Context result = context;
        if (result == null) {
            throw new IllegalStateException("AppContext is not initialized: CarLinkServer.start() must be called first");
        }
        return result;
    }
}
