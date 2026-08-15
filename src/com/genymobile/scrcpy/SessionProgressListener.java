package com.genymobile.scrcpy;

/**
 * Sink for session liveness progress, fed by the session I/O threads and consumed by the {@code CarLinkServer} session watchdog.
 * <p>
 * A stamp means "bytes were actually exchanged with the head unit just now": a video packet written to the video socket, a control
 * message read from the control socket, or a device message (e.g. a heartbeat) written to the control socket. Work that was only
 * enqueued does NOT count: on a dead peer, socket writes block or fail, so only a completed I/O operation proves the session alive.
 * <p>
 * Implementations must be thread-safe and non-blocking: stamping happens on hot I/O paths (every video packet).
 */
public interface SessionProgressListener {
    void onSessionProgress();
}
