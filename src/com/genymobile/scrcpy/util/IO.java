package com.genymobile.scrcpy.util;

import com.genymobile.scrcpy.AndroidVersions;
import com.genymobile.scrcpy.BuildConfig;

import android.os.Build;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Scanner;

public final class IO {
    private IO() {
        // not instantiable
    }

    private static int write(FileDescriptor fd, ByteBuffer from) throws IOException {
        while (true) {
            try {
                int w = Os.write(fd, from);
                if (w == 0 && from.hasRemaining()) {
                    // Cannot happen on a blocking fd (it writes >0 bytes or fails with an errno), but a 0-byte write would
                    // make writeFully() spin forever
                    throw new IOException("Os.write() wrote 0 bytes");
                }
                return w;
            } catch (ErrnoException e) {
                if (e.errno != OsConstants.EINTR) {
                    throw new IOException(e);
                }
            }
        }
    }

    public static void writeFully(FileDescriptor fd, ByteBuffer from) throws IOException {
        if (Build.VERSION.SDK_INT >= AndroidVersions.API_23_ANDROID_6_0) {
            while (from.hasRemaining()) {
                write(fd, from);
            }
        } else {
            // ByteBuffer position is not updated as expected by Os.write() on old Android versions, so
            // handle the position and the remaining bytes manually.
            // See <https://github.com/Genymobile/scrcpy/issues/291>.
            int position = from.position();
            int remaining = from.remaining();
            while (remaining > 0) {
                int w = write(fd, from);
                if (BuildConfig.DEBUG && w < 0) {
                    // w should not be negative, since an exception is thrown on error
                    throw new AssertionError("Os.write() returned a negative value (" + w + ")");
                }
                remaining -= w;
                position += w;
                from.position(position);
            }
        }
    }

    public static String toString(InputStream inputStream) {
        StringBuilder builder = new StringBuilder();
        Scanner scanner = new Scanner(inputStream);
        while (scanner.hasNextLine()) {
            builder.append(scanner.nextLine()).append('\n');
        }
        return builder.toString();
    }

    /**
     * @return {@code true} if the write failed because the peer is gone: {@code EPIPE} (write after an orderly close),
     * {@code ECONNRESET} (the peer sent a RST, e.g. the head unit crashed or rebooted) or {@code ETIMEDOUT} (the kernel
     * aborted the connection because unacknowledged data exceeded TCP_USER_TIMEOUT, see {@code CarLinkConnection}: the
     * peer is half-open dead — the first write on such a connection fails with ETIMEDOUT, later ones with EPIPE). All
     * are expected on session teardown and must terminate the stream cleanly, not trigger the encoder retry path.
     */
    public static boolean isBrokenPipe(IOException e) {
        Throwable cause = e.getCause();
        if (!(cause instanceof ErrnoException)) {
            return false;
        }
        int errno = ((ErrnoException) cause).errno;
        return errno == OsConstants.EPIPE || errno == OsConstants.ECONNRESET || errno == OsConstants.ETIMEDOUT;
    }

    public static boolean isBrokenPipe(Exception e) {
        return e instanceof IOException && isBrokenPipe((IOException) e);
    }
}
