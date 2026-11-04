package com.morsen.ts3xclient.protocol;

import org.junit.Test;
import java.net.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

public class TeamSpeakEndpointCacheTest {
    private static TeamSpeakAddressResolver.Resolved endpoint(String ip, int port) throws Exception {
        return new TeamSpeakAddressResolver.Resolved(new InetSocketAddress(InetAddress.getByName(ip), port), "TSDNS");
    }
    @Test public void repeatConnectsReuseDiscoveryButVpnAndExplicitPortsDoNotShareAnswers() throws Exception {
        AtomicLong clock = new AtomicLong();
        TeamSpeakEndpointCache cache = new TeamSpeakEndpointCache(4, 60_000, clock::get);
        TeamSpeakAddressResolver.Resolved answer = endpoint("127.0.0.1", 9738);
        cache.put("wifi", "Copper.Example", 9738, answer);
        assertSame(answer, cache.get("wifi", " copper.example ", 9738));
        assertNull(cache.get("vpn", "copper.example", 9738));
        assertNull(cache.get("wifi", "copper.example", 9987));
        clock.set(TimeUnit.SECONDS.toNanos(60));
        assertNull(cache.get("wifi", "copper.example", 9738));
    }
    @Test public void failedHandshakeInvalidatesAnAnswerAndCapacityIsBounded() throws Exception {
        TeamSpeakEndpointCache cache = new TeamSpeakEndpointCache(2, 60_000);
        TeamSpeakAddressResolver.Resolved answer = endpoint("127.0.0.1", 9987);
        cache.put("wifi", "a", 9987, answer);
        cache.put("wifi", "b", 9987, answer);
        assertSame(answer, cache.get("wifi", "a", 9987));
        cache.put("wifi", "c", 9987, answer);
        assertNull(cache.get("wifi", "b", 9987));
        cache.invalidate("wifi", "a", 9987);
        assertNull(cache.get("wifi", "a", 9987));
        assertSame(answer, cache.get("wifi", "c", 9987));
    }
}
