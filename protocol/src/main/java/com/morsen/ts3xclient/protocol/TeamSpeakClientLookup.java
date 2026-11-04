package com.morsen.ts3xclient.protocol;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Full-client UID/DBID lookups return notifications, whereas Query may return direct data. */
public final class TeamSpeakClientLookup implements AutoCloseable {
    private static final class Pending {
        final String event, matchKey, matchValue;
        final CompletableFuture<Map<String, ?>> reply = new CompletableFuture<>();
        Pending(String event, String matchKey, String matchValue) {
            this.event = event; this.matchKey = matchKey; this.matchValue = matchValue;
        }
    }
    private final TeamSpeakFileTransfers.CommandSender sender;
    private final AtomicReference<Pending> pending = new AtomicReference<>();
    private volatile boolean closed;

    public TeamSpeakClientLookup(TeamSpeakFileTransfers.CommandSender sender) { this.sender = sender; }

    public void onNotification(String event, Map<String, ?> values) {
        Pending request = pending.get();
        if (!closed && request != null && request.event.equals(event)
                && request.matchValue.equals(String.valueOf(values.get(request.matchKey))))
            request.reply.complete(new LinkedHashMap<>(values));
    }

    public synchronized String clientUid(int clientId) throws Exception {
        if (clientId <= 0) throw new IllegalArgumentException("Invalid client ID");
        Map<String, ?> values = query("clientgetuidfromclid", "notifyclientuidfromclid",
                "clid", Integer.toString(clientId), "cluid");
        String uid = String.valueOf(values.get("cluid"));
        TeamSpeakIdentity.avatarFileName(uid); // Reject null/invalid/truncated UIDs.
        return uid;
    }

    public synchronized long databaseId(String uid) throws Exception {
        TeamSpeakIdentity.avatarFileName(uid);
        Map<String, ?> values = query("clientgetdbidfromuid", "notifyclientdbidfromuid", "cluid", uid, "cldbid");
        try {
            long id = Long.parseLong(String.valueOf(values.get("cldbid")));
            if (id > 0) return id;
        } catch (NumberFormatException ignored) { }
        throw new IOException("Server returned an invalid client DBID");
    }

    private Map<String, ?> query(String command, String event, String matchKey, String matchValue,
                                 String resultKey) throws Exception {
        if (closed) throw new IOException("TeamSpeak lookup session is closed");
        Pending request = new Pending(event, matchKey, matchValue);
        pending.set(request); // The public methods serialize requests; callbacks never take this lock.
        Map<String, String> params = new LinkedHashMap<>(); params.put(matchKey, matchValue);
        try {
            if (closed) throw new IOException("TeamSpeak lookup session is closed");
            Map<String, ?> direct = sender.execute(command, params);
            if (direct != null && direct.containsKey(resultKey)) request.reply.complete(direct);
            return request.reply.get(5, TimeUnit.SECONDS);
        } catch (ExecutionException error) {
            if (error.getCause() instanceof Exception) throw (Exception) error.getCause();
            throw error;
        } finally { pending.compareAndSet(request, null); }
    }

    @Override public void close() {
        closed = true;
        Pending request = pending.getAndSet(null);
        if (request != null) request.reply.completeExceptionally(new IOException("TeamSpeak disconnected"));
    }
}
