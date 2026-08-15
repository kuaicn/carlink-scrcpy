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
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

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

        // Values mirror the @SystemApi android.view.WindowManager#DISPLAY_IME_POLICY_* constants (they are the protocol of
        // WindowManagerService.setDisplayImePolicy(), so they are stable by design)
        /** The IME appears on the virtual display itself, next to the app requesting it. */
        public static final int DISPLAY_IME_POLICY_LOCAL = 0;
        /** The IME appears on the phone default display (the platform default for a virtual display). */
        public static final int DISPLAY_IME_POLICY_FALLBACK_DISPLAY = 1;
        /** The IME never appears for windows on the virtual display (soft input is fully disabled there). */
        public static final int DISPLAY_IME_POLICY_HIDE = 2;

        /**
         * The default is {@link #DISPLAY_IME_POLICY_LOCAL}, not the platform default ({@code FALLBACK_DISPLAY}): the head unit
         * is the only screen the user interacts with, so an IME popping up on the phone display would make text input from
         * the car impossible.
         */
        public static final int DEFAULT_DISPLAY_IME_POLICY = DISPLAY_IME_POLICY_LOCAL;

        private final int width;
        private final int height;
        private final int densityDpi;
        private final int bitRate;
        private final String codec;
        private final float maxFps;
        private final int iFrameIntervalSec;
        private final int videoPort;
        private final int displayImePolicy;

        private Config(Builder builder) {
            this.width = builder.width;
            this.height = builder.height;
            this.densityDpi = builder.densityDpi;
            this.bitRate = builder.bitRate;
            this.codec = builder.codec;
            this.maxFps = builder.maxFps;
            this.iFrameIntervalSec = builder.iFrameIntervalSec;
            this.videoPort = builder.videoPort;
            this.displayImePolicy = builder.displayImePolicy;
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

        public int getDisplayImePolicy() {
            return displayImePolicy;
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
            private int displayImePolicy = DEFAULT_DISPLAY_IME_POLICY;

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

            /**
             * Set where the IME is shown when an input field of an app on the virtual display gains focus: one of
             * {@link Config#DISPLAY_IME_POLICY_LOCAL}, {@link Config#DISPLAY_IME_POLICY_FALLBACK_DISPLAY} or
             * {@link Config#DISPLAY_IME_POLICY_HIDE}. See {@link Config#DEFAULT_DISPLAY_IME_POLICY} for the default.
             * <p>
             * Note: the policy is applied via {@code WindowManagerService.setDisplayImePolicy()}, which requires a trusted
             * virtual display; it is therefore ignored below Android 13 (the virtual display cannot be trusted there).
             */
            public Builder displayImePolicy(int displayImePolicy) {
                if (displayImePolicy != DISPLAY_IME_POLICY_LOCAL
                        && displayImePolicy != DISPLAY_IME_POLICY_FALLBACK_DISPLAY
                        && displayImePolicy != DISPLAY_IME_POLICY_HIDE) {
                    throw new IllegalArgumentException("Invalid display IME policy: " + displayImePolicy);
                }
                this.displayImePolicy = displayImePolicy;
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

    /** Interval between session watchdog checks (see {@link #startWatchdog()}). */
    private static final int WATCHDOG_CHECK_INTERVAL_MS = 2000;

    /**
     * The session watchdog force-terminates the session when no progress (any successful channel I/O, see
     * {@link SessionProgressListener}) was observed for this long. The heartbeat the Controller queues every 10s keeps a
     * healthy but idle session (static screen, no input) fed; on a dead peer (head unit powered off, network black hole)
     * the heartbeat write blocks or fails, progress stops and the watchdog fires.
     */
    private static final int SESSION_STALL_TIMEOUT_MS = 30000;

    /**
     * Overall timeout for waiting for the video connection. The poll-based dead-control detection only catches a peer that
     * actually closed (FIN/RST received): a half-open dead control channel (head unit powered off or unplugged, no FIN ever
     * sent) is undetectable that way, so without an upper bound the session would wait forever, holding the single library
     * instance and rejecting every later connection as busy.
     */
    private static final int VIDEO_ACCEPT_TIMEOUT_MS = 30000;

    // Linux UAPI value of POLLRDHUP (peer shutdown/close notification for poll()): OsConstants does not expose it (it is not
    // part of the public SDK), but the value is fixed by the kernel ABI
    private static final int POLLRDHUP = 0x2000;

    private final Options options;
    private final Socket controlSocket;
    private final Listener listener;
    private final ServerSocket videoServerSocket;

    private final HandlerThread sessionThread = new HandlerThread("carlink-scrcpy");
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final CountDownLatch terminationLatch = new CountDownLatch(1);
    // Written by a processor thread terminating with an error (first error wins), read by the session thread to report it via
    // Listener.onError() before onStopped()
    private final AtomicReference<Throwable> sessionErrorCause = new AtomicReference<>();
    // Stamped by the session I/O threads via onSessionProgress(), read by the watchdog thread
    private final AtomicLong lastProgressMs = new AtomicLong();

    // Only accessed on the session thread (except where noted)
    private CarLinkConnection connection;
    private Thread watchdogThread;
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

        // Bind synchronously so that getVideoPort() is valid as soon as start() returns, and bind failures are reported to the caller.
        // SO_REUSEADDR: a fixed port must be immediately rebindable by the next session, since the accepted sockets of the
        // previous session may still be in TIME_WAIT on this local port after an onStopped() -> start() restart
        ServerSocket videoServerSocket = null;
        try {
            videoServerSocket = new ServerSocket();
            videoServerSocket.setReuseAddress(true);
            videoServerSocket.bind(new InetSocketAddress(config.getVideoPort()));
        } catch (IOException e) {
            if (videoServerSocket != null) {
                try {
                    videoServerSocket.close();
                } catch (IOException closeException) {
                    // ignore
                }
            }
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
        args.add("send_frame_meta=true");
        args.add("display_ime_policy=" + displayImePolicyArg(config.getDisplayImePolicy()));
        if (config.getMaxFps() > 0) {
            args.add("max_fps=" + config.getMaxFps());
        }
        if (config.getIFrameIntervalSec() != Config.DEFAULT_I_FRAME_INTERVAL_SEC) {
            args.add("i_frame_interval=" + config.getIFrameIntervalSec());
        }
        return Options.parse(args.toArray(new String[0]));
    }

    /** Map a {@link Config} DISPLAY_IME_POLICY_* value to the {@code display_ime_policy} option value. */
    private static String displayImePolicyArg(int displayImePolicy) {
        switch (displayImePolicy) {
            case Config.DISPLAY_IME_POLICY_LOCAL:
                return "local";
            case Config.DISPLAY_IME_POLICY_FALLBACK_DISPLAY:
                return "fallback";
            case Config.DISPLAY_IME_POLICY_HIDE:
                return "hide";
            default:
                // Unreachable: Config.Builder validates the value
                throw new AssertionError("Invalid display IME policy: " + displayImePolicy);
        }
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

            // A processor terminated with an error: report it (onStopped() will follow from the finally block)
            Throwable cause = sessionErrorCause.get();
            if (cause != null && running.get()) {
                reportError("CarLink session terminated unexpectedly", cause);
            }
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
     * <p>
     * The wait is additionally bounded by {@link #VIDEO_ACCEPT_TIMEOUT_MS}: the dead-control poll cannot detect a
     * half-open control channel (peer gone without FIN/RST), so only an overall timeout guarantees that the session
     * (and the single library instance) is always released.
     *
     * @return the connected video socket, or {@code null} if the session must end before any video connection
     */
    private Socket acceptVideo() throws IOException {
        videoServerSocket.setSoTimeout(VIDEO_ACCEPT_POLL_MS);
        long acceptDeadline = SystemClock.uptimeMillis() + VIDEO_ACCEPT_TIMEOUT_MS;
        while (running.get()) {
            try {
                Socket socket = videoServerSocket.accept();
                if (!running.get()) {
                    // stop() was requested while accept() was blocked: do not start processors for a session already stopping
                    try {
                        socket.close();
                    } catch (IOException e) {
                        // ignore
                    }
                    return null;
                }
                // The video port is reachable by any device on the hotspot: accept only the peer holding the control
                // channel. A foreign connection accepted first would otherwise be streamed to, while the real head
                // unit sits in the listen backlog staring at a frozen screen. (Accepted sockets are never null here,
                // the control socket is connected by contract, so no null check is needed.)
                if (!socket.getInetAddress().equals(controlSocket.getInetAddress())) {
                    Ln.w("Rejecting video connection from " + socket.getRemoteSocketAddress()
                            + ": not the control channel peer (" + controlSocket.getInetAddress() + ")");
                    try {
                        socket.close();
                    } catch (IOException e) {
                        // ignore
                    }
                    continue;
                }
                Ln.i("Video connection accepted from " + socket.getRemoteSocketAddress() + " on port " + socket.getLocalPort());
                return socket;
            } catch (SocketTimeoutException e) {
                if (isControlSocketDead()) {
                    Ln.w("Control channel closed while waiting for the video connection; ending session");
                    return null;
                }
                if (SystemClock.uptimeMillis() >= acceptDeadline) {
                    Ln.w("Timed out waiting for the video connection (" + VIDEO_ACCEPT_TIMEOUT_MS + " ms); ending session");
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
        if (controlSocket.isClosed()) {
            return true;
        }
        // InputStream.available() cannot detect a peer close (FIONREAD only reports queued bytes, never EOF), and read() would
        // consume protocol bytes. Poll the raw fd instead: a peer FIN/RST shows up as POLLRDHUP/POLLERR/POLLHUP, while pending
        // data shows up as plain POLLIN (alive).
        ParcelFileDescriptor pfd;
        try {
            pfd = ParcelFileDescriptor.fromSocket(controlSocket);
        } catch (RuntimeException e) {
            return true;
        }
        if (pfd == null) {
            // The socket has no fd (i.e. it is not connected)
            return true;
        }
        try {
            StructPollfd pollfd = new StructPollfd();
            pollfd.fd = pfd.getFileDescriptor();
            pollfd.events = (short) (OsConstants.POLLIN | POLLRDHUP);
            StructPollfd[] pollfds = {pollfd};
            Os.poll(pollfds, 0);
            return (pollfd.revents & (POLLRDHUP | OsConstants.POLLERR | OsConstants.POLLHUP)) != 0;
        } catch (ErrnoException e) {
            return true;
        } finally {
            try {
                pfd.close();
            } catch (IOException e) {
                // ignore
            }
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
            // The control socket was handed over to the library by start(): close it too, since terminate() cannot do it via
            // connection.close() (the connection was never created)
            try {
                controlSocket.close();
            } catch (IOException closeException) {
                // ignore
            }
            throw e;
        }

        ControlChannel controlChannel = connection.getControlChannel();
        Controller controller = new Controller(controlChannel, options, this::onSessionProgress);
        asyncProcessors.add(controller);

        // sendStreamMeta=false: no session meta packets on the wire (CarLink protocol); the 4-byte codec id is always written.
        // sendFrameMeta=true is enforced through the options built by buildOptions().
        Streamer videoStreamer = new Streamer(connection.getVideoFd(), options.getVideoCodec(), false, options.getSendFrameMeta(),
                this::onSessionProgress);

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
            asyncProcessor.start((fatalError, cause) -> {
                if (cause != null) {
                    // Reported via Listener.onError() by the session thread once it wakes up
                    sessionErrorCause.compareAndSet(null, cause);
                }
                terminationLatch.countDown();
            });
        }

        startWatchdog();
    }

    // Called by the session I/O threads whenever bytes were actually exchanged with the head unit (a video packet or a device
    // message written, a control message received). Timestamps only move forward, so a plain set() keeps the max semantics.
    private void onSessionProgress() {
        lastProgressMs.set(SystemClock.uptimeMillis());
    }

    /**
     * Start the session watchdog: a daemon thread which force-terminates the session once no progress (see
     * {@link #onSessionProgress()}) was observed for {@link #SESSION_STALL_TIMEOUT_MS}. This is what always reclaims a session
     * whose peer vanished without a FIN (head unit powered off or unplugged: such a half-open connection surfaces no I/O error
     * for a very long time), instead of holding the single library instance forever and rejecting every later connection as busy.
     * <p>
     * A healthy but idle session is never killed: the Controller heartbeat (every 10s) is written successfully and counts as
     * progress. On a dead peer the write blocks or fails, progress stops, and the watchdog shuts both sockets down (failing
     * every blocked write, e.g. the video encoder's or the device message sender's) and then takes the same termination path as
     * a fatal processor error: {@code terminationLatch} wakes the session thread, which reports the error and runs the full
     * {@link #terminate()} cleanup.
     */
    private void startWatchdog() {
        // The session starts healthy: without this baseline a session that never gets going (e.g. the encoder never produces
        // its first frame) would trip the timeout before having had a chance
        lastProgressMs.set(SystemClock.uptimeMillis());
        // Captured for the watchdog thread: connection is otherwise confined to the session thread and never reassigned
        CarLinkConnection watchdogConnection = connection;
        watchdogThread = new Thread(() -> {
            try {
                while (true) {
                    Thread.sleep(WATCHDOG_CHECK_INTERVAL_MS);
                    long stalledMs = SystemClock.uptimeMillis() - lastProgressMs.get();
                    if (stalledMs > SESSION_STALL_TIMEOUT_MS) {
                        Ln.w("CarLink session stalled: no progress for " + stalledMs + " ms, forcing termination");
                        watchdogConnection.shutdown();
                        sessionErrorCause.compareAndSet(null, new IOException("Session stalled: no progress for " + stalledMs + " ms"));
                        terminationLatch.countDown();
                        return;
                    }
                }
            } catch (InterruptedException e) {
                // stopWatchdog() interrupted the sleep: normal session teardown
            } catch (Throwable t) {
                // In-process hosting: never let an Error escape the thread, it would kill the hosting app process
                Ln.e("Session watchdog error", t);
            } finally {
                Ln.d("Session watchdog stopped");
            }
        }, "session-watchdog");
        watchdogThread.setDaemon(true);
        watchdogThread.start();
    }

    private void stopWatchdog() {
        if (watchdogThread != null) {
            watchdogThread.interrupt();
            watchdogThread = null;
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

        // The watchdog must not fire while the teardown it may have triggered is already running
        stopWatchdog();

        for (AsyncProcessor asyncProcessor : asyncProcessors) {
            asyncProcessor.stop();
        }

        if (connection != null) {
            connection.shutdown();
        }

        // Join every processor even if this thread carries an interrupted status (stop() interrupts the session thread): a single
        // interrupted join() must not skip the remaining joins and the OpenGLRunner shutdown
        boolean interrupted = Thread.interrupted(); // clear the status so that join() actually waits
        for (AsyncProcessor asyncProcessor : asyncProcessors) {
            try {
                asyncProcessor.join();
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        try {
            OpenGLRunner.shutdown();
        } catch (InterruptedException e) {
            interrupted = true;
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }

        if (connection != null) {
            connection.close();
        } else {
            // The session ended before the connection was created (e.g. aborted while waiting for the video channel): the
            // control socket handed over to start() must still be closed (idempotent if startProcessors() already closed it)
            try {
                controlSocket.close();
            } catch (IOException e) {
                // ignore
            }
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
