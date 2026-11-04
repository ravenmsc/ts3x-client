package com.morsen.ts3xclient.protocol;

import com.github.manevolent.ts3j.identity.LocalIdentity;
import com.github.manevolent.ts3j.protocol.Packet;
import com.github.manevolent.ts3j.protocol.ProtocolRole;
import com.github.manevolent.ts3j.protocol.header.ClientPacketHeader;
import com.github.manevolent.ts3j.protocol.header.ServerPacketHeader;
import com.github.manevolent.ts3j.protocol.packet.*;
import com.github.manevolent.ts3j.protocol.socket.client.LocalTeamspeakClientSocket;
import org.junit.Test;
import javax.net.SocketFactory;
import java.math.BigInteger;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** An alias with a nondefault input port can redirect to another UDP port before INIT. */
public class TeamSpeakTsdnsHandshakeTest {
    @Test public void followsTheAdvertisedPortAndReachesTheCookieExchange() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try (ServerSocket tsdns = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             DatagramSocket silent = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
             DatagramSocket voice = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
             LocalTeamspeakClientSocket client = new LocalTeamspeakClientSocket()) {
            int inputPort = silent.getLocalPort();
            Future<?> answering = workers.submit(() -> {
                try (Socket query = tsdns.accept()) {
                    query.setSoTimeout(2000);
                    byte[] host = new byte["alias.example.test".length()];
                    new java.io.DataInputStream(query.getInputStream()).readFully(host);
                    assertEquals("alias.example.test", new String(host, StandardCharsets.US_ASCII));
                    query.getOutputStream().write(("127.0.0.1:" + voice.getLocalPort()).getBytes(StandardCharsets.US_ASCII));
                } catch (Exception error) { throw new RuntimeException(error); }
            });
            TeamSpeakAddressResolver.Backend backend = new TeamSpeakAddressResolver.Backend() {
                public List<InetAddress> addresses(String host) throws java.io.IOException {
                    if (!host.equals("tsdns.example.test")) throw new UnknownHostException(host);
                    return Collections.singletonList(InetAddress.getByName("127.0.0.1"));
                }
                public List<TeamSpeakAddressResolver.Service> services(String host) {
                    return host.equals("_tsdns._tcp.example.test") ? Collections.singletonList(
                            new TeamSpeakAddressResolver.Service(0, 0, tsdns.getLocalPort(), "tsdns.example.test"))
                            : Collections.emptyList();
                }
                public String tsdns(InetSocketAddress server, String host) throws java.io.IOException {
                    return TeamSpeakAddressResolver.queryTsdns(SocketFactory.getDefault(), server, host);
                }
            };
            List<InetSocketAddress> destinations = new TeamSpeakAddressResolver(backend).resolve("alias.example.test", inputPort).getEndpoints();
            assertEquals(inputPort, destinations.get(0).getPort());
            assertEquals(voice.getLocalPort(), destinations.get(1).getPort());
            answering.get(2, TimeUnit.SECONDS);
            client.setIdentity(LocalIdentity.load(BigInteger.ONE));
            Future<TimeoutException> connecting = workers.submit(() -> {
                try { client.connect(destinations.get(0), null, 1800, 200); fail("The input port has no voice server"); }
                catch (TimeoutException expected) { assertTrue(client.wasLastConnectUnanswered()); }
                try { client.connect(destinations.get(1), null, 1800, 200); throw new AssertionError("Fixture stops before authentication"); }
                catch (TimeoutException expected) { assertFalse(client.wasLastConnectUnanswered()); return expected; }
            });
            voice.setSoTimeout(1500);
            DatagramPacket request = new DatagramPacket(new byte[1024], 1024);
            voice.receive(request);
            ByteBuffer incoming = ByteBuffer.wrap(request.getData(), 0, request.getLength());
            ClientPacketHeader header = new ClientPacketHeader(); header.read(incoming);
            PacketBody8Init1 first = new PacketBody8Init1(ProtocolRole.CLIENT); first.read(incoming);
            assertEquals(0, first.getStep().getNumber());
            byte[] nonce = ((PacketBody8Init1.Step0) first.getStep()).getRandom();
            PacketBody8Init1.Step1 cookie = new PacketBody8Init1.Step1();
            cookie.setA0reversed(new byte[]{nonce[3], nonce[2], nonce[1], nonce[0]});
            ServerPacketHeader replyHeader = new ServerPacketHeader();
            replyHeader.setType(PacketBodyType.INIT1); replyHeader.setPacketId(101); replyHeader.setPacketFlags(0x88);
            replyHeader.setMac("TS3INIT1".getBytes(StandardCharsets.US_ASCII));
            Packet reply = new Packet(ProtocolRole.SERVER, replyHeader);
            reply.setBody(new PacketBody8Init1(ProtocolRole.SERVER, cookie));
            ByteBuffer bytes = ByteBuffer.allocate(reply.getSize()); reply.write(bytes);
            voice.send(new DatagramPacket(bytes.array(), bytes.position(), request.getSocketAddress()));
            request.setLength(1024); voice.receive(request);
            ByteBuffer second = ByteBuffer.wrap(request.getData(), 0, request.getLength());
            new ClientPacketHeader().read(second);
            PacketBody8Init1 response = new PacketBody8Init1(ProtocolRole.CLIENT); response.read(second);
            assertEquals(2, response.getStep().getNumber());
            assertTrue(connecting.get(3, TimeUnit.SECONDS).getMessage().contains("initSent=2 initReceived=1"));
        } finally { workers.shutdownNow(); }
    }
}
