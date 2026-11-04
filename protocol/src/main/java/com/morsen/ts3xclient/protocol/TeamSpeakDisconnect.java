package com.morsen.ts3xclient.protocol;

import java.util.concurrent.*;

/** Leaving locally must not depend on receiving another server notification. */
public final class TeamSpeakDisconnect {
    private TeamSpeakDisconnect() { }
    public interface Transport {
        boolean isConnected();
        void requestDisconnect() throws Exception;
        void close() throws Exception;
    }
    /** Returns whether the remote disconnect completed; local close errors still propagate. */
    public static boolean close(Transport transport, long timeoutMillis) throws Exception {
        FutureTask<Void> request = null;
        boolean confirmed = false;
        try {
            if (transport.isConnected()) {
                request = new FutureTask<>(() -> { transport.requestDisconnect(); return null; });
                Thread thread = new Thread(request, "TS3X-Disconnect");
                thread.setDaemon(true);
                thread.start();
                try { request.get(timeoutMillis, TimeUnit.MILLISECONDS); confirmed = true; }
                catch (ExecutionException | TimeoutException ignored) { /* best effort remote leave */ }
            }
        } finally {
            try { transport.close(); }
            finally { if (request != null) request.cancel(true); }
        }
        return confirmed;
    }
}
