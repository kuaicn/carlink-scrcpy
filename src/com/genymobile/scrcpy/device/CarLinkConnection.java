package com.genymobile.scrcpy.device;

import com.genymobile.scrcpy.control.ControlChannel;
import com.genymobile.scrcpy.util.Ln;

import android.os.ParcelFileDescriptor;

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
