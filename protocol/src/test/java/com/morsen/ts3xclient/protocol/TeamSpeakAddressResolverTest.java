package com.morsen.ts3xclient.protocol;

import org.junit.Test;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import javax.net.SocketFactory;
import static org.junit.Assert.*;

public class TeamSpeakAddressResolverTest {
    static final class Fake implements TeamSpeakAddressResolver.Backend {
        final Map<String, List<InetAddress>> addresses = new HashMap<>();
        final Map<String, List<TeamSpeakAddressResolver.Service>> records = new HashMap<>();
        final List<String> calls = new ArrayList<>();
        String response = "198.51.100.7:9738";
        boolean srvFailure;
        Fake address(String host, String ip) throws Exception {
            addresses.put(host, Collections.singletonList(InetAddress.getByName(ip))); return this;
        }
        Fake srv(String owner, int priority, int weight, int port, String target) {
            records.computeIfAbsent(owner, ignored -> new ArrayList<>())
                    .add(new TeamSpeakAddressResolver.Service(priority, weight, port, target)); return this;
        }
        public List<InetAddress> addresses(String host) throws IOException {
            calls.add("DNS " + host);
            List<InetAddress> result = addresses.get(host);
            if (result == null) throw new UnknownHostException(host);
            return result;
        }
        public List<TeamSpeakAddressResolver.Service> services(String name) throws IOException {
            calls.add("SRV " + name);
            if (srvFailure) throw new IOException("test DNS unavailable");
            return records.getOrDefault(name, Collections.emptyList());
        }
        public String tsdns(InetSocketAddress endpoint, String query) {
            calls.add("TSDNS " + query + " " + endpoint.getPort()); return response;
        }
        TeamSpeakAddressResolver resolver() { return new TeamSpeakAddressResolver(this, new Random(530)); }
    }
    private Fake alias() throws Exception {
        return new Fake().srv("_tsdns._tcp.cleanvoice.ru", 10, 0, 41144, "tsdns.cleanvoice.ru.")
                .address("tsdns.cleanvoice.ru", "192.0.2.20");
    }
    @Test public void parentTsdnsResolvesAliasWithoutAnAddressRecord() throws Exception {
        Fake backend = alias();
        TeamSpeakAddressResolver.Resolved result = backend.resolver().resolve("copper.cleanvoice.ru", 9987);
        assertEquals("198.51.100.7", result.getEndpoint().getAddress().getHostAddress());
        assertEquals(9738, result.getEndpoint().getPort());
        assertEquals("TSDNS", result.getMethod());
        assertTrue(backend.calls.contains("TSDNS copper.cleanvoice.ru 41144"));
        assertFalse(backend.calls.contains("SRV _tsdns._tcp.ru"));
    }
    @Test public void explicitPortAndFixedTsdnsDestinationAreBothRetained() throws Exception {
        Fake backend = alias(); backend.response = "198.51.100.7:1111";
        TeamSpeakAddressResolver.Resolved result = backend.resolver().resolve("copper.cleanvoice.ru", 9738);
        assertEquals(9738, result.getEndpoint().getPort());
        assertEquals(1111, result.getEndpoints().get(1).getPort());
        assertEquals(1111, result.preferring(result.getEndpoints().get(1)).getEndpoint().getPort());
        assertEquals(9738, result.getEndpoint().getPort());
    }
    @Test public void dollarPortAndBareIpKeepTheUserPort() throws Exception {
        Fake backend = alias(); backend.response = "198.51.100.7:$PORT";
        assertEquals(9738, backend.resolver().resolve("copper.cleanvoice.ru", 9738).getEndpoint().getPort());
        backend.response = "198.51.100.7";
        TeamSpeakAddressResolver.Resolved bare = backend.resolver().resolve("copper.cleanvoice.ru", 9738);
        assertEquals(9738, bare.getEndpoint().getPort());
        assertEquals(9987, bare.getEndpoints().get(1).getPort());
    }
    @Test public void literalIpSkipsAllDiscovery() throws Exception {
        Fake backend = new Fake();
        assertEquals(9994, backend.resolver().resolve("5.180.217.221", 9994).getEndpoint().getPort());
        assertTrue(backend.calls.isEmpty());
    }
    @Test public void dnsKeepsBothAddressesInsteadOfDiscardingFallbacks() throws Exception {
        Fake backend = new Fake();
        backend.addresses.put("voice.example.com", Arrays.asList(InetAddress.getByName("192.0.2.1"), InetAddress.getByName("2001:db8::1")));
        TeamSpeakAddressResolver.Resolved result = backend.resolver().resolve("voice.example.com", 9738);
        assertEquals(2, result.getEndpoints().size());
        assertTrue(result.getEndpoints().get(1).getAddress() instanceof Inet6Address);
    }
    @Test public void bracketedIpv6SkipsAllDiscovery() throws Exception {
        Fake backend = new Fake();
        assertTrue(backend.resolver().resolve("[2001:db8::1]", 9987).getEndpoint().getAddress() instanceof Inet6Address);
        assertTrue(backend.calls.isEmpty());
    }
    @Test public void existingCustomPortDnsProfileDoesNotGetRedirected() throws Exception {
        Fake backend = new Fake().address("voice.example.com", "192.0.2.1")
                .srv("_ts3._udp.voice.example.com", 0, 0, 4444, "other.example.com");
        assertEquals(9738, backend.resolver().resolve("voice.example.com", 9738).getEndpoint().getPort());
        assertEquals(Collections.singletonList("DNS voice.example.com"), backend.calls);
    }
    @Test public void directSrvFindsTargetAndPortWithoutOriginalARecord() throws Exception {
        Fake backend = new Fake().srv("_ts3._udp.voice.example.com", 0, 0, 4444, "real.example.com.")
                .address("real.example.com", "192.0.2.1");
        TeamSpeakAddressResolver.Resolved result = backend.resolver().resolve("voice.example.com", 9987);
        assertEquals("SRV", result.getMethod()); assertEquals(4444, result.getEndpoint().getPort());
        assertFalse(backend.calls.contains("DNS voice.example.com"));
    }
    @Test public void directSrvAlsoKeepsExplicitCustomPort() throws Exception {
        Fake backend = new Fake().srv("_ts3._udp.voice.example.com", 0, 0, 4444, "real.example.com")
                .address("real.example.com", "192.0.2.1");
        assertEquals(9738, backend.resolver().resolve("voice.example.com", 9738).getEndpoint().getPort());
    }
    @Test public void srvPriorityAndBrokenTargetFallbackAreRespected() throws Exception {
        Fake backend = new Fake().srv("_ts3._udp.voice.example.com", 20, 60000, 2222, "backup.example.com")
                .srv("_ts3._udp.voice.example.com", 0, 1, 1111, "broken.example.com")
                .address("backup.example.com", "192.0.2.2");
        assertEquals(2222, backend.resolver().resolve("voice.example.com", 9987).getEndpoint().getPort());
        assertTrue(backend.calls.indexOf("DNS broken.example.com") < backend.calls.indexOf("DNS backup.example.com"));
    }
    @Test public void ordinaryDnsStillWorksWhenNoServiceRecordsExist() throws Exception {
        Fake backend = new Fake().address("voice.example.com", "192.0.2.1");
        assertEquals("DNS", backend.resolver().resolve("voice.example.com", 9987).getMethod());
    }
    @Test public void serviceLookupErrorsDoNotBreakOrdinaryDns() throws Exception {
        Fake backend = new Fake().address("voice.example.com", "192.0.2.1"); backend.srvFailure = true;
        assertEquals("DNS", backend.resolver().resolve("voice.example.com", 9987).getMethod());
    }
    @Test public void malformedOrUnavailableServiceIsIgnored() throws Exception {
        Fake backend = new Fake().address("voice.example.com", "192.0.2.1")
                .srv("_ts3._udp.voice.example.com", 0, 0, 9987, ".")
                .srv("_ts3._udp.voice.example.com", 0, 0, 0, "invalid.example.com");
        assertEquals("DNS", backend.resolver().resolve("voice.example.com", 9987).getMethod());
        assertFalse(backend.calls.contains("DNS ."));
    }
    @Test public void tsdns404FallsBackToOrdinaryDns() throws Exception {
        Fake backend = alias().address("copper.cleanvoice.ru", "192.0.2.1"); backend.response = "404";
        assertEquals("DNS", backend.resolver().resolve("copper.cleanvoice.ru", 9987).getMethod());
    }
    @Test public void tsdnsSupportsDollarPortAndMultipleIpFamilies() throws Exception {
        List<InetSocketAddress> result = TeamSpeakAddressResolver.parseTsdns(
                "198.51.100.7:$PORT [2001:db8::1]:9738", 9994);
        assertEquals(2, result.size()); assertEquals(9994, result.get(0).getPort());
        assertTrue(result.get(1).getAddress() instanceof Inet6Address);
        assertEquals(9738, result.get(1).getPort());
    }
    @Test public void tsdnsBareIpUsesDefaultPort() throws Exception {
        assertEquals(9987, TeamSpeakAddressResolver.parseTsdns("198.51.100.7", 9987).get(0).getPort());
    }
    @Test public void tsdnsRejectsNonNumericRedirectsAndInvalidPorts() throws Exception {
        for (String text : Arrays.asList("other.example.com:9738", "127.0.0.1:0", "127.0.0.1:65536",
                "127.0.0.1:-1", "404", "NORESPONSE", "", "x".repeat(1025))) {
            try { TeamSpeakAddressResolver.parseTsdns(text, 9987); fail(text); }
            catch (IOException expected) { }
        }
    }
    @Test public void unknownHostReportsAllDiscoveryMechanisms() throws Exception {
        try { new Fake().resolver().resolve("missing.example.com", 9987); fail(); }
        catch (UnknownHostException expected) { assertTrue(expected.getMessage().contains("SRV or TSDNS")); }
    }
    @Test public void hostnamesAreNormalizedAndIdnEncoded() throws Exception {
        Fake backend = new Fake().address("xn--e1afmkfd.xn--p1ai", "192.0.2.1");
        assertEquals("DNS", backend.resolver().resolve("Пример.РФ.", 9738).getMethod());
    }
    @Test public void invalidHostsCannotInjectTsdnsQueries() throws Exception {
        Fake backend = new Fake();
        for (String host : Arrays.asList("", ".", "foo\r\nbar.example.com", "a b.example.com", "https://example.com")) {
            try { backend.resolver().resolve(host, 9987); fail(host); }
            catch (IllegalArgumentException expected) { }
        }
        assertTrue(backend.calls.isEmpty());
    }
    @Test public void invalidRequestedPortIsRejectedBeforeNetworkUse() throws Exception {
        Fake backend = new Fake();
        for (int port : new int[]{0, -1, 65536}) {
            try { backend.resolver().resolve("voice.example.com", port); fail(); }
            catch (IllegalArgumentException expected) { }
        }
        assertTrue(backend.calls.isEmpty());
    }
    @Test public void tcpExchangeHandlesSplitRepliesAndSendsOnlyTheHostname() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            ExecutorService worker = Executors.newSingleThreadExecutor();
            Future<String> request = worker.submit(() -> {
                try (Socket socket = server.accept()) {
                    byte[] bytes = new byte["copper.cleanvoice.ru".length()];
                    new DataInputStream(socket.getInputStream()).readFully(bytes);
                    socket.getOutputStream().write("198.51.".getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                    Thread.sleep(30);
                    socket.getOutputStream().write("100.7:9738\r\n".getBytes(StandardCharsets.US_ASCII));
                    return new String(bytes, StandardCharsets.US_ASCII);
                }
            });
            try {
                String response = TeamSpeakAddressResolver.queryTsdns(SocketFactory.getDefault(),
                        new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getLocalPort()), "copper.cleanvoice.ru");
                assertEquals(9738, TeamSpeakAddressResolver.parseTsdns(response, 9987).get(0).getPort());
                assertEquals("copper.cleanvoice.ru", request.get(3, TimeUnit.SECONDS));
            } finally { worker.shutdownNow(); }
        }
    }
    @Test public void tcpExchangeRejectsOversizedReplies() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            ExecutorService worker = Executors.newSingleThreadExecutor();
            worker.submit(() -> {
                try (Socket socket = server.accept()) {
                    socket.getInputStream().read(new byte[253]);
                    socket.getOutputStream().write(new byte[1025]);
                } catch (IOException ignored) { }
            });
            try {
                TeamSpeakAddressResolver.queryTsdns(SocketFactory.getDefault(),
                        new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getLocalPort()), "voice.example.com");
                fail("Oversized reply accepted");
            } catch (IOException expected) { assertTrue(expected.getMessage().contains("limit")); }
            finally { worker.shutdownNow(); }
        }
    }
    @Test public void tcpExchangeDoesNotWaitIndefinitelyForAnUnresponsiveService() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            ExecutorService worker = Executors.newSingleThreadExecutor();
            worker.submit(() -> {
                try (Socket socket = server.accept()) {
                    socket.getInputStream().read(new byte[253]);
                    Thread.sleep(5000);
                } catch (IOException | InterruptedException ignored) { }
            });
            long start = System.nanoTime();
            try {
                TeamSpeakAddressResolver.queryTsdns(SocketFactory.getDefault(),
                        new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getLocalPort()), "voice.example.com");
                fail("Unresponsive TSDNS service did not time out");
            } catch (SocketTimeoutException expected) {
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 3000);
            } finally { worker.shutdownNow(); }
        }
    }

}
