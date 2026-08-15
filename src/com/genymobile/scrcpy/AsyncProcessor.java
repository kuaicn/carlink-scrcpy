package com.genymobile.scrcpy;

public interface AsyncProcessor {
    interface TerminationListener {
        /**
         * Notify processor termination
         *
         * @param fatalError {@code true} if this must cause the termination of the whole session.
         * @param cause      the error which caused the termination, or {@code null} if the processor stopped without error (on request or
         *                   because the peer closed the channel); reported via {@code CarLinkServer.Listener.onError()}
         */
        void onTerminated(boolean fatalError, Throwable cause);
    }

    void start(TerminationListener listener);

    void stop();

    void join() throws InterruptedException;
}
