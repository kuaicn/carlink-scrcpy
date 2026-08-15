package com.genymobile.scrcpy.control;

import com.genymobile.scrcpy.SessionProgressListener;
import com.genymobile.scrcpy.util.Ln;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public final class DeviceMessageSender {

    private static final long JOIN_TIMEOUT_MS = 2000;

    private final ControlChannel controlChannel;
    private final SessionProgressListener progressListener;

    private Thread thread;
    private final BlockingQueue<DeviceMessage> queue = new ArrayBlockingQueue<>(16);

    public DeviceMessageSender(ControlChannel controlChannel, SessionProgressListener progressListener) {
        this.controlChannel = controlChannel;
        this.progressListener = progressListener;
    }

    public void send(DeviceMessage msg) {
        if (!queue.offer(msg)) {
            Ln.w("Device message dropped: " + msg.getType());
        }
    }

    private void loop() throws IOException, InterruptedException {
        while (!Thread.currentThread().isInterrupted()) {
            DeviceMessage msg = queue.take();
            controlChannel.send(msg);
            // Stamp only after the write completed: an enqueued message says nothing about the link, a written one does
            progressListener.onSessionProgress();
        }
    }

    public void start() {
        thread = new Thread(() -> {
            try {
                loop();
            } catch (IOException | InterruptedException e) {
                // this is expected on close
            } catch (Throwable t) {
                // In-process hosting: never let an Error escape the thread, it would kill the hosting app process
                Ln.e("Fatal device message sender error", t);
            } finally {
                Ln.d("Device message sender stopped");
            }
        }, "control-send");
        thread.start();
    }

    public void stop() {
        if (thread != null) {
            thread.interrupt();
        }
    }

    public void join() throws InterruptedException {
        if (thread != null) {
            // Bounded wait: a stuck send must not block the whole session teardown forever
            thread.join(JOIN_TIMEOUT_MS);
            if (thread.isAlive()) {
                Ln.e("Device message sender thread did not terminate within " + JOIN_TIMEOUT_MS + "ms, giving up");
            }
        }
    }
}
