package com.morsen.ts3xclient.protocol;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Short-lived discovery cache, isolated by Android network and explicit voice port. */
public final class TeamSpeakEndpointCache {
    private static final class Entry {
        final TeamSpeakAddressResolver.Resolved address;
        final long expiresAt;
        Entry(TeamSpeakAddressResolver.Resolved address, long expiresAt) {
            this.address = address; this.expiresAt = expiresAt;
        }
    }
    private final Map<String, Entry> entries = new LinkedHashMap<>(16, .75f, true);
    private final int capacity;
    private final long ttlNanos;
    private final LongSupplier clock;

    public TeamSpeakEndpointCache(int capacity, long ttlMillis) {
        this(capacity, ttlMillis, System::nanoTime);
    }
    TeamSpeakEndpointCache(int capacity, long ttlMillis, LongSupplier clock) {
        if (capacity < 1 || ttlMillis < 1) throw new IllegalArgumentException();
        this.capacity = capacity; this.ttlNanos = TimeUnit.MILLISECONDS.toNanos(ttlMillis);
        this.clock = clock;
    }
    private static String key(String network, String host, int port) {
        return network + '\0' + host.trim().toLowerCase(Locale.ROOT) + '\0' + port;
    }
    public synchronized TeamSpeakAddressResolver.Resolved get(String network, String host, int port) {
        String key = key(network, host, port);
        Entry entry = entries.get(key);
        if (entry == null) return null;
        if (clock.getAsLong() - entry.expiresAt >= 0) { entries.remove(key); return null; }
        return entry.address;
    }
    public synchronized void put(String network, String host, int port, TeamSpeakAddressResolver.Resolved address) {
        if (address == null || address.getEndpoint().isUnresolved()) throw new IllegalArgumentException();
        entries.put(key(network, host, port), new Entry(address, clock.getAsLong() + ttlNanos));
        while (entries.size() > capacity) entries.remove(entries.keySet().iterator().next());
    }
    public synchronized void invalidate(String network, String host, int port) {
        entries.remove(key(network, host, port));
    }
}
