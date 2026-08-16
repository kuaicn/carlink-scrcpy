package com.genymobile.scrcpy.device;

import com.genymobile.scrcpy.control.ControlChannel;
import com.genymobile.scrcpy.util.Ln;

import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;

/**
 * Holds the two TCP channels of a CarLink session:
 * <ul>
 *   <li>the video socket, accepted by this library from its own {@code ServerSocket} (see {@code CarLinkServer});</li>
 *   <li>the control socket, established by the caller (the "互联服务" app) and handed over after its handshake completed.</li>
 * </ul>
 * Unlike upstream (which connects over adb tunnel abstract sockets), there is no device-name meta and no dummy byte: the very first
 * bytes written on the video socket are the 4-byte codec id written by {@link Streamer#writeVideoHeader()}.
 */
public final class CarLinkConnection implements Closeable {

    // Linux UAPI value of TCP_USER_TIMEOUT: OsConstants does not expose it (it is not part of the public SDK), but the value
    // is fixed by the kernel ABI
    private static final int TCP_USER_TIMEOUT = 18;

    /**
     * Bound on how long unacknowledged data may linger before the kernel aborts the connection. Without it, a half-open
     * dead peer (head unit powered off or unplugged, no FIN/RST ever sent) keeps accepting our sparse writes — in
     * particular the 10s heartbeats, a few bytes each — into its kernel send buffer until tcp_retries2 gives up
     * (15-30 min): every such "successful" write feeds the CarLinkServer session watchdog, so the dead session would
     * never be reclaimed. The value must stay below the 30s watchdog window ({@code CarLinkServer.SESSION_STALL_TIMEOUT_MS})
     * so that writes to a dead peer start failing — stopping progress stamps — before the watchdog checks.
     */
    private static final int TCP_USER_TIMEOUT_MS = 20000;

    private final Socket videoSocket;
    // Dup of the video socket fd (ParcelFileDescriptor.fromSocket() dup()s it) so that Streamer can keep using the Os.write() path.
    // It owns an fd independent from the socket, so it must be closed in addition to the Socket itself.
    private final ParcelFileDescriptor videoPfd;

    private final Socket controlSocket;
    private final ControlChannel controlChannel;

    public CarLinkConnection(Socket videoSocket, Socket controlSocket) throws IOException {
        this.videoSocket = videoSocket;
        this.controlSocket = controlSocket;
        try {
            // Low-latency interactive streams: disable Nagle
            videoSocket.setTcpNoDelay(true);
            controlSocket.setTcpNoDelay(true);
        } catch (SocketException e) {
            throw new IOException(e);
        }
        setTcpUserTimeout(videoSocket);
        setTcpUserTimeout(controlSocket);

        ParcelFileDescriptor pfd = ParcelFileDescriptor.fromSocket(videoSocket);
        if (pfd == null) {
            // fromSocket() returns null if the socket has no fd (i.e. it is not connected)
            throw new IOException("Video socket is not connected");
        }
        try {
            controlChannel = new ControlChannel(controlSocket.getInputStream(), controlSocket.getOutputStream());
        } catch (IOException | RuntimeException e) {
            // Do not leak the dup'ed fd if the control channel setup fails
            try {
                pfd.close();
            } catch (IOException closeException) {
                // ignore
            }
            throw e;
        }
        videoPfd = pfd;
    }

    /**
     * Set TCP_USER_TIMEOUT on the socket (see {@link #TCP_USER_TIMEOUT_MS}). Best effort: if the kernel rejects it, the
     * session watchdog still catches stalls of a streaming video channel, only half-open detection of an idle session
     * degrades back to the TCP retransmit timeout.
     */
    private static void setTcpUserTimeout(Socket socket) {
        // Os.setsockoptInt() needs a FileDescriptor: fromSocket() dup()s it, and the sockopt applies to the shared
        // underlying socket, so closing the dup right away is fine
        ParcelFileDescriptor pfd;
        try {
            pfd = ParcelFileDescriptor.fromSocket(socket);
        } catch (RuntimeException e) {
            Ln.w("Could not dup socket fd for TCP_USER_TIMEOUT: " + e.getMessage());
            return;
        }
        if (pfd == null) {
            return;
        }
        try {
            Os.setsockoptInt(pfd.getFileDescriptor(), OsConstants.IPPROTO_TCP, TCP_USER_TIMEOUT, TCP_USER_TIMEOUT_MS);
        } catch (ErrnoException | RuntimeException e) {
            Ln.w("Could not set TCP_USER_TIMEOUT: " + e.getMessage());
        } finally {
            try {
                pfd.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }

    public FileDescriptor getVideoFd() {
        return videoPfd.getFileDescriptor();
    }

    public ControlChannel getControlChannel() {
        return controlChannel;
    }

    /**
     * Shutdown both directions of both sockets, so that threads blocked on read/write fail fast.
     */
    public void shutdown() {
        try {
            videoSocket.shutdownOutput();
        } catch (IOException e) {
            // already closed/shutdown, ignore
        }
        try {
            videoSocket.shutdownInput();
        } catch (IOException e) {
            // already closed/shutdown, ignore
        }
        try {
            controlSocket.shutdownOutput();
        } catch (IOException e) {
            // already closed/shutdown, ignore
        }
        try {
            controlSocket.shutdownInput();
        } catch (IOException e) {
            // already closed/shutdown, ignore
        }
    }

    @Override
    public void close() {
        try {
            // Closes the underlying socket fd as well
            videoPfd.close();
        } catch (IOException e) {
            Ln.w("Could not close video pfd: " + e.getMessage());
        }
        try {
            videoSocket.close();
        } catch (IOException e) {
            Ln.w("Could not close video socket: " + e.getMessage());
        }
        try {
            controlSocket.close();
        } catch (IOException e) {
            Ln.w("Could not close control socket: " + e.getMessage());
        }
    }
}
