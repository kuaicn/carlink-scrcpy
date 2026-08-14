package com.genymobile.scrcpy;

import com.genymobile.scrcpy.control.ControlChannel;
import com.genymobile.scrcpy.control.Controller;
import com.genymobile.scrcpy.device.CarLinkConnection;
import com.genymobile.scrcpy.device.Device;
import com.genymobile.scrcpy.device.Streamer;
import com.genymobile.scrcpy.opengl.OpenGLRunner;
import com.genymobile.scrcpy.util.AppContext;
import com.genymobile.scrcpy.util.Ln;
import com.genymobile.scrcpy.video.NewDisplayCapture;
import com.genymobile.scrcpy.video.SurfaceCapture;
import com.genymobile.scrcpy.video.SurfaceEncoder;
import com.genymobile.scrcpy.video.VirtualDisplayListener;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CarLink entry point: runs the scrcpy capture/encode/inject pipeline in-process, inside the hosting privapp (the "互联服务").
 * <p>
 * Usage:
 * <ol>
 *   <li>the caller accepts a TCP connection from the car head unit and performs the CarLink handshake on it (see docs/carlink-protocol.md);</li>
 *   <li>the caller hands the connected control socket to {@link #start(Context, Config, Socket, Listener)};</li>
 *   <li>the library binds a {@code ServerSocket} for the video channel ({@link #getVideoPort()} is valid as soon as {@code start()} returns)
 *       and waits for the head unit to connect;</li>
 *   <li>once the virtual display is created, {@link Listener#onVirtualDisplayReady(int)} reports its display id, so the caller may launch
 *       activities on it.</li>
 * </ol>
 * Only one session may be active at a time.
 * <p>
 * Threading: the whole session (video accept, processors startup, event pump) runs on a dedicated {@code HandlerThread} named
 * "carlink-scrcpy" with its own Looper; only this Looper is ever quit, never the app main Looper. All listener callbacks are invoked on
 * this library's internal threads; callers must switch threads themselves if they need to.
 */
public final class CarLinkServer {

    /**
     * Session configuration (use {@link Builder}).
     */
    public static final class Config {

        public static final String CODEC_H264 = "h264";
        public static final String CODEC_H265 = "h265";

        public static final int DEFAULT_BIT_RATE = 8000000;
        public static final String DEFAULT_CODEC = CODEC_H264;
        public static final float DEFAULT_MAX_FPS = 0; // unlimited
        public static final int DEFAULT_I_FRAME_INTERVAL_SEC = 10;
        public static final int DEFAULT_VIDEO_PORT = 0; // auto-allocated

        private final int width;
        private final int height;
        private final int densityDpi;
        private final int bitRate;
        private final String codec;
        private final float maxFps;
        private final int iFrameIntervalSec;
        private final int videoPort;

        private Config(Builder builder) {
            this.width = builder.width;
            this.height = builder.height;
            this.densityDpi = builder.densityDpi;
            this.bitRate = builder.bitRate;
            this.codec = builder.codec;
            this.maxFps = builder.maxFps;
            this.iFrameIntervalSec = builder.iFrameIntervalSec;
            this.videoPort = builder.videoPort;
        }

        public int getWidth() {
            return width;
        }

        public int getHeight() {
            return height;
        }

        public int getDensityDpi() {
            return densityDpi;
        }

        public int getBitRate() {
            return bitRate;
        }

        public String getCodec() {
            return codec;
        }

        public float getMaxFps() {
            return maxFps;
        }

        public int getIFrameIntervalSec() {
            return iFrameIntervalSec;
        }

        public int getVideoPort() {
            return videoPort;
        }

        public static final class Builder {
            private final int width;
            private final int height;
            private final int densityDpi;

            private int bitRate = DEFAULT_BIT_RATE;
            private String codec = DEFAULT_CODEC;
            private float maxFps = DEFAULT_MAX_FPS;
            private int iFrameIntervalSec = DEFAULT_I_FRAME_INTERVAL_SEC;
            private int videoPort = DEFAULT_VIDEO_PORT;

            /**
             * @param width      the width of the car head unit screen, in pixels (mandatory)
             * @param height     the height of the car head unit screen, in pixels (mandatory)
             * @param densityDpi the density of the car head unit screen (mandatory)
             */
            public Builder(int width, int height, int densityDpi) {
                if (width <= 0 || height <= 0) {
                    throw new IllegalArgumentException("Invalid display size: " + width + "x" + height);
                }
                if (densityDpi <= 0) {
                    throw new IllegalArgumentException("Invalid display density: " + densityDpi);
                }
                this.width = width;
                this.height = height;
                this.densityDpi = densityDpi;
            }

            public Builder bitRate(int bitRate) {
                if (bitRate <= 0) {
                    throw new IllegalArgumentException("Invalid bit rate: " + bitRate);
                }
                this.bitRate = bitRate;
                return this;
            }

            public Builder codec(String codec) {
                if (!CODEC_H264.equals(codec) && !CODEC_H265.equals(codec)) {
                    throw new IllegalArgumentException("Unsupported codec: \"" + codec + "\" (expected \"" + CODEC_H264 + "\" or \"" + CODEC_H265
                            + "\")");
                }
                this.codec = codec;
                return this;
            }

            public Builder maxFps(float maxFps) {
                if (maxFps < 0) {
                    throw new IllegalArgumentException("Invalid max fps: " + maxFps);
                }
                this.maxFps = maxFps;
                return this;
            }

            public Builder iFrameIntervalSec(int iFrameIntervalSec) {
                if (iFrameIntervalSec < 0) {
                    throw new IllegalArgumentException("Invalid i-frame interval: " + iFrameIntervalSec);
                }
                this.iFrameIntervalSec = iFrameIntervalSec;
                return this;
            }

            public Builder videoPort(int videoPort) {
                if (videoPort < 0 || videoPort > 65535) {
                    throw new IllegalArgumentException("Invalid video port: " + videoPort);
                }
                this.videoPort = videoPort;
                return this;
            }

            public Config build() {
                return new Config(this);
            }
        }
    }

    /**
     * Session events listener. All callbacks are invoked on this library's internal threads.
     */
    public interface Listener {
        /**
         * The virtual display has been created; activities may be launched on it (e.g. via
         * {@code ActivityOptions.setLaunchDisplayId(displayId)}).
         */
        void onVirtualDisplayReady(int displayId);

        /**
         * The session failed to set up or died unexpectedly. Always followed by {@link #onStopped()}.
         */
        void onError(String message, Throwable cause);

        /**
         * The session is completely over (all resources released, including the virtual display).
         */
        void onStopped();
    }

    private static CarLinkServer instance;

    /** Poll interval while waiting for the video connection, so that a dead control channel is detected. */
    private static final int VIDEO_ACCEPT_POLL_MS = 2000;

    private final Options options;
    private final Socket controlSocket;
    private final Listener listener;
    private final ServerSocket videoServerSocket;

    private final HandlerThread sessionThread = new HandlerThread("carlink-scrcpy");
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final CountDownLatch terminationLatch = new CountDownLatch(1);

    // Only accessed on the session thread (except where noted)
    private CarLinkConnection connection;
    private final List<AsyncProcessor> asyncProcessors = new ArrayList<>();
    // Only accessed on the video encoder thread (which is the only caller of the VirtualDisplayListener)
    private int lastNotifiedDisplayId = Device.DISPLAY_ID_NONE;

    private CarLinkServer(Options options, Socket controlSocket, Listener listener, ServerSocket videoServerSocket) {
        this.options = options;
        this.controlSocket = controlSocket;
        this.listener = listener;
        this.videoServerSocket = videoServerSocket;
    }

    /**
     * Start a CarLink session.
     *
     * @param context       any Context of the hosting app (its application Context is used internally)
     * @param config        the session configuration
     * @param controlSocket an already-connected TCP socket to the car head unit, on which the CarLink handshake has already been performed
     *                      by the caller; from now on it carries the scrcpy control message stream (docs/control.md)
     * @param listener      the session events listener
     * @return the running session handle
     * @throws IllegalStateException    if a session is already running
     * @throws IllegalArgumentException if the configuration is invalid
     * @throws UncheckedIOException     if the video ServerSocket could not be bound
     */
    public static synchronized CarLinkServer start(Context context, Config config, Socket controlSocket, Listener listener) {
        if (instance != null) {
            throw new IllegalStateException("A CarLink session is already running");
        }
        if (context == null || config == null || controlSocket == null || listener == null) {
            throw new NullPointerException("context, config, controlSocket and listener must not be null");
        }
        AppContext.init(context.getApplicationContext());

        // Parse/validate now so that configuration errors are reported synchronously to the caller
        Options options = buildOptions(config);

        Ln.initLogLevel(options.getLogLevel());

        // Bind synchronously so that getVideoPort() is valid as soon as start() returns, and bind failures are reported to the caller
        ServerSocket videoServerSocket;
        try {
            videoServerSocket = new ServerSocket(config.getVideoPort());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not bind video server socket on port " + config.getVideoPort(), e);
        }

        CarLinkServer server = new CarLinkServer(options, controlSocket, listener, videoServerSocket);
        instance = server;
        try {
            server.startSession();
        } catch (Throwable t) {
            // Roll back so that a later start() is not blocked by a dead instance
            instance = null;
            try {
                videoServerSocket.close();
            } catch (IOException e) {
                // ignore
            }
            throw t;
        }
        return server;
    }

    /**
     * Build the upstream {@code key=value} arguments and let {@link Options#parse(String...)} produce the Options (zero reimplementation
     * of the parsing logic). The first element must be the version to satisfy the version check.
     */
    private static Options buildOptions(Config config) {
        List<String> args = new ArrayList<>();
        args.add(BuildConfig.VERSION_NAME);
        args.add("new_display=" + config.getWidth() + "x" + config.getHeight() + "/" + config.getDensityDpi());
        args.add("video_bit_rate=" + config.getBitRate());
        args.add("video_codec=" + config.getCodec());
        args.add("control=true");
        args.add("send_frame_meta=true");
        if (config.getMaxFps() > 0) {
            args.add("max_fps=" + config.getMaxFps());
        }
        if (config.getIFrameIntervalSec() != Config.DEFAULT_I_FRAME_INTERVAL_SEC) {
            args.add("i_frame_interval=" + config.getIFrameIntervalSec());
        }
        return Options.parse(args.toArray(new String[0]));
    }

    private void startSession() {
        sessionThread.start();
        // The whole session body is dispatched by the session thread's own Looper
        Handler sessionHandler = new Handler(sessionThread.getLooper());
        sessionHandler.post(this::runSession);
    }

    /**
     * @return the local port of the video channel the car head unit must connect to; valid as soon as {@link #start} returns
     */
    public int getVideoPort() {
        return videoServerSocket.getLocalPort();
    }

    public boolean isRunning() {
        return running.get();
    }

    /**
     * Request the session to stop. May be called from any thread, is idempotent and does not block: it unblocks the pending video
     * accept(), signals termination and quits the session Looper; the full cleanup chain (stop/join of all processors, sockets shutdown
     * and close, virtual display release) then runs on the session thread, and {@link Listener#onStopped()} is invoked once done.
     */
    public void stop() {
        if (!running.getAndSet(false)) {
            return; // already stopped or stopping
        }
        // Unblock a pending accept()
        try {
            videoServerSocket.close();
        } catch (IOException e) {
            // ignore
        }
        // Unblock the termination wait (the session thread then runs the cleanup chain)
        terminationLatch.countDown();
        sessionThread.interrupt();
        Looper looper = sessionThread.getLooper();
        if (looper != null) {
            looper.quitSafely();
        }
    }

    // -------------------- session thread --------------------

    private void runSession() {
        // Runs on the session thread ("carlink-scrcpy"), dispatched by its own Looper which acts as the event pump
        try {
            Ln.i("Device: [" + Build.MANUFACTURER + "] " + Build.BRAND + " " + Build.MODEL + " (Android " + Build.VERSION.RELEASE + ")");

            Socket videoSocket = acceptVideo();
            if (videoSocket == null) {
                // stop() was requested, or the control channel died before the video connection was established
                return;
            }

            startProcessors(videoSocket);

            // Block until any processor terminates (the peer closed a channel, or a fatal error occurred) or stop() is called
            terminationLatch.await();
        } catch (InterruptedException e) {
            // stop() interrupted the wait; fall through to the cleanup
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            if (running.get()) {
                reportError("CarLink session failed", t);
            }
            // Otherwise: expected failure caused by stop() (e.g. "Socket closed" from accept())
        } finally {
            terminate();
        }
    }

    /**
     * Wait for the head unit to connect the video channel, polling so that a control channel which dies in the
     * meantime does not leave the session stuck forever (also unblocked by {@link #stop()} closing the ServerSocket).
     *
     * @return the connected video socket, or {@code null} if the session must end before any video connection
     */
    private Socket acceptVideo() throws IOException {
        videoServerSocket.setSoTimeout(VIDEO_ACCEPT_POLL_MS);
        while (running.get()) {
            try {
                return videoServerSocket.accept();
            } catch (SocketTimeoutException e) {
                if (isControlSocketDead()) {
                    Ln.w("Control channel closed while waiting for the video connection; ending session");
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * @return {@code true} if the control socket is closed locally or its peer closed the connection, without
     * consuming any protocol bytes
     */
    private boolean isControlSocketDead() {
        try {
            // SocketInputStream.available() returns -1 once the peer's FIN has been received
            return controlSocket.isClosed() || controlSocket.getInputStream().available() < 0;
        } catch (IOException e) {
            return true;
        }
    }

    private void startProcessors(Socket videoSocket) throws IOException {
        try {
            connection = new CarLinkConnection(videoSocket, controlSocket);
        } catch (IOException | RuntimeException e) {
            // Do not leak the accepted video socket if the connection setup fails
            try {
                videoSocket.close();
            } catch (IOException closeException) {
                // ignore
            }
            throw e;
        }

        ControlChannel controlChannel = connection.getControlChannel();
        Controller controller = new Controller(controlChannel, options);
        asyncProcessors.add(controller);

        // sendStreamMeta=false: no session meta packets on the wire (CarLink protocol); the 4-byte codec id is always written.
        // sendFrameMeta=true is enforced through the options built by buildOptions().
        Streamer videoStreamer = new Streamer(connection.getVideoFd(), options.getVideoCodec(), false, options.getSendFrameMeta());

        // Forward the virtual display notification both to the Controller (input events routing) and to the session listener
        VirtualDisplayListener vdListener = (displayId, positionMapper) -> {
            controller.onNewVirtualDisplay(displayId, positionMapper);
            notifyVirtualDisplayReady(displayId);
        };
        SurfaceCapture surfaceCapture = new NewDisplayCapture(vdListener, options);
        SurfaceEncoder surfaceEncoder = new SurfaceEncoder(surfaceCapture, videoStreamer, options);
        asyncProcessors.add(surfaceEncoder);
        controller.setSurfaceCapture(surfaceCapture);

        // Both processors always report termination as "fatal": any of them stopping ends the whole session
        // (this is the upstream Completion semantics, simplified: the first termination wins)
        for (AsyncProcessor asyncProcessor : asyncProcessors) {
            asyncProcessor.start((fatalError) -> terminationLatch.countDown());
        }
    }

    // Called on the video encoder thread (from NewDisplayCapture)
    private void notifyVirtualDisplayReady(int displayId) {
        if (displayId != lastNotifiedDisplayId) {
            lastNotifiedDisplayId = displayId;
            try {
                listener.onVirtualDisplayReady(displayId);
            } catch (Throwable t) {
                Ln.e("onVirtualDisplayReady listener threw", t);
            }
        }
    }

    // Upstream Server.scrcpy() finally-chain semantics: stop, shutdown, join, release
    private void terminate() {
        running.set(false);

        for (AsyncProcessor asyncProcessor : asyncProcessors) {
            asyncProcessor.stop();
        }

        if (connection != null) {
            connection.shutdown();
        }

        try {
            for (AsyncProcessor asyncProcessor : asyncProcessors) {
                asyncProcessor.join();
            }

            OpenGLRunner.shutdown();
        } catch (InterruptedException e) {
            // ignore
            Thread.currentThread().interrupt();
        }

        if (connection != null) {
            connection.close();
        }
        try {
            videoServerSocket.close();
        } catch (IOException e) {
            // ignore
        }

        // Quit the session Looper (never the app main Looper)
        sessionThread.quitSafely();

        // Allow a new session before notifying, so that onStopped() may start one
        synchronized (CarLinkServer.class) {
            if (instance == this) {
                instance = null;
            }
        }

        try {
            listener.onStopped();
        } catch (Throwable t) {
            Ln.e("onStopped listener threw", t);
        }
    }

    private void reportError(String message, Throwable cause) {
        Ln.e(message, cause);
        try {
            listener.onError(message + ": " + cause.getMessage(), cause);
        } catch (Throwable t) {
            Ln.e("onError listener threw", t);
        }
    }
}
