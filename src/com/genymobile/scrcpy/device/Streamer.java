package com.genymobile.scrcpy.device;

import com.genymobile.scrcpy.SessionProgressListener;
import com.genymobile.scrcpy.model.Codec;
import com.genymobile.scrcpy.util.IO;

import android.media.MediaCodec;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;

public final class Streamer {

    private static final long PACKET_FLAG_SESSION = 1L << 63;
    private static final long PACKET_FLAG_CONFIG = 1L << 62;
    private static final long PACKET_FLAG_KEY_FRAME = 1L << 61;

    private final FileDescriptor fd;
    private final Codec codec;
    private final boolean sendStreamMeta;
    private final boolean sendFrameMeta;
    private final SessionProgressListener progressListener;

    private final ByteBuffer headerBuffer = ByteBuffer.allocate(12);

    public Streamer(FileDescriptor fd, Codec codec, boolean sendCodecMeta, boolean sendFrameMeta, SessionProgressListener progressListener) {
        this.fd = fd;
        this.codec = codec;
        this.sendStreamMeta = sendCodecMeta;
        this.sendFrameMeta = sendFrameMeta;
        this.progressListener = progressListener;
    }

    public Codec getCodec() {
        return codec;
    }

    /**
     * Write the 4-byte codec id: these are the very first bytes of the video stream.
     * <p>
     * Unlike upstream, this is unconditional (not tied to {@code sendStreamMeta}): the CarLink protocol mandates the codec id header but
     * no session meta packets and no device-name meta.
     */
    public void writeVideoHeader() throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(4);
        buffer.putInt(codec.getId());
        buffer.flip();
        IO.writeFully(fd, buffer);
    }

    public void writePacket(ByteBuffer buffer, long pts, boolean config, boolean keyFrame) throws IOException {
        if (sendFrameMeta) {
            writeFrameMeta(fd, buffer.remaining(), pts, config, keyFrame);
        }

        IO.writeFully(fd, buffer);
        // Stamp only after the packet reached the socket: a blocked/failed write means no progress (session watchdog input)
        progressListener.onSessionProgress();
    }

    public void writePacket(ByteBuffer codecBuffer, MediaCodec.BufferInfo bufferInfo) throws IOException {
        long pts = bufferInfo.presentationTimeUs;
        boolean config = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
        boolean keyFrame = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
        writePacket(codecBuffer, pts, config, keyFrame);
    }

    public void writeSessionMeta(int width, int height, boolean isClientResize) throws IOException {
        if (sendStreamMeta) {
            headerBuffer.clear();

            int flags = (int) (PACKET_FLAG_SESSION >> 32); // set the first bit to 1
            if (isClientResize) {
                flags |= 1;
            }
            headerBuffer.putInt(flags);
            headerBuffer.putInt(width);
            headerBuffer.putInt(height);
            headerBuffer.flip();
            IO.writeFully(fd, headerBuffer);
        }
    }

    private void writeFrameMeta(FileDescriptor fd, int packetSize, long pts, boolean config, boolean keyFrame) throws IOException {
        headerBuffer.clear();

        long ptsAndFlags;
        if (config) {
            ptsAndFlags = PACKET_FLAG_CONFIG; // non-media data packet
        } else {
            ptsAndFlags = pts;
            if (keyFrame) {
                ptsAndFlags |= PACKET_FLAG_KEY_FRAME;
            }
        }

        headerBuffer.putLong(ptsAndFlags);
        headerBuffer.putInt(packetSize);
        headerBuffer.flip();
        IO.writeFully(fd, headerBuffer);
    }
}
