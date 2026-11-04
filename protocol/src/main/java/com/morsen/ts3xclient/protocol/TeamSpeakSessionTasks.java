package com.morsen.ts3xclient.protocol;

import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Optional initialization must not delay a usable voice session or hide a dead transport. */
public final class TeamSpeakSessionTasks {
    public interface Session {
        boolean isConnected();
        /** Name/icon probes use initserver and must not subscribe to every client. */
        default boolean requiresSubscription() { return true; }
        void subscribe() throws Exception;
        void publishAvatar() throws Exception;
    }
    private static final class Ticket {
        final Session session;
        final AtomicBoolean cancelled = new AtomicBoolean();
        volatile Future<?> initialization, watch;
        Ticket(Session session) { this.session = session; }
        void setWatch(Future<?> value) { watch = value; if (cancelled.get()) value.cancel(false); }
        void setInitialization(Future<?> value) { initialization = value; if (cancelled.get()) value.cancel(true); }
        void cancel() {
            cancelled.set(true);
            Future<?> init = initialization, monitor = watch;
            if (init != null) init.cancel(true);
            if (monitor != null) monitor.cancel(false);
        }
    }
    private final AtomicReference<Ticket> active = new AtomicReference<>();
    // Reuse bounded daemon pools across short-lived monitoring backends.
    // A new probe must not leave two executor threads behind after disconnect.
    private static final ExecutorService INITIALIZATION = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "TS3X-SessionMetadata"); thread.setDaemon(true); return thread;
    });
    private static final ScheduledThreadPoolExecutor MONITOR = new ScheduledThreadPoolExecutor(2, runnable -> {
        Thread thread = new Thread(runnable, "TS3X-TransportWatch"); thread.setDaemon(true); return thread;
    });
    static { MONITOR.setRemoveOnCancelPolicy(true); }

    public void start(Session session, Consumer<Throwable> warning, Runnable lost) throws IOException {
        stop();
        if (!session.isConnected()) throw new IOException("TeamSpeak transport disconnected before ready");
        Ticket ticket = new Ticket(session);
        active.set(ticket);
        ticket.setWatch(MONITOR.scheduleWithFixedDelay(() -> {
            if (active.get() != ticket) return;
            if (!session.isConnected() && active.compareAndSet(ticket, null)) {
                ticket.cancel();
                lost.run();
            }
        }, 500, 500, TimeUnit.MILLISECONDS));
        ticket.setInitialization(!session.requiresSubscription() ? CompletableFuture.completedFuture(null)
                : INITIALIZATION.submit(() -> {
            if (!current(ticket)) return;
            try { session.subscribe(); }
            catch (Exception error) { if (current(ticket)) warning.accept(error); }
            if (!current(ticket)) return;
            try { session.publishAvatar(); }
            catch (Exception error) { if (current(ticket)) warning.accept(error); }
        }));
    }
    /** Background monitoring needs a complete snapshot before counting users. */
    public boolean awaitInitialization(long timeoutMillis) throws InterruptedException, ExecutionException {
        Ticket ticket = active.get();
        if (ticket == null || ticket.initialization == null) return false;
        try {
            ticket.initialization.get(timeoutMillis, TimeUnit.MILLISECONDS);
            return active.get() == ticket && ticket.session.isConnected();
        } catch (TimeoutException | CancellationException incomplete) { return false; }
    }

    private boolean current(Ticket ticket) {
        return active.get() == ticket && !Thread.currentThread().isInterrupted() && ticket.session.isConnected();
    }
    public void stop() {
        Ticket previous = active.getAndSet(null);
        if (previous != null) previous.cancel();
    }
}
