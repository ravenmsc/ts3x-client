package com.github.manevolent.ts3j;

import com.github.manevolent.ts3j.command.SingleCommand;
import com.github.manevolent.ts3j.identity.LocalIdentity;
import com.github.manevolent.ts3j.protocol.Packet;
import com.github.manevolent.ts3j.protocol.ProtocolRole;
import com.github.manevolent.ts3j.protocol.client.ClientConnectionState;
import com.github.manevolent.ts3j.protocol.header.ClientPacketHeader;
import com.github.manevolent.ts3j.protocol.header.ServerPacketHeader;
import com.github.manevolent.ts3j.protocol.packet.*;
import com.github.manevolent.ts3j.protocol.packet.handler.client.LocalClientHandlerConnecting;
import com.github.manevolent.ts3j.protocol.socket.client.LocalTeamspeakClientSocket;
import org.junit.Test;

import java.math.BigInteger;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.Assert.*;

public class HandshakeRegressionTest {
    @Test public void firstResponseDeadlineDoesNotCutOffAnAnsweringServersHandshake() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (DatagramSocket server = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
             LocalTeamspeakClientSocket socket = new LocalTeamspeakClientSocket()) {
            socket.setIdentity(LocalIdentity.load(BigInteger.ONE)); server.setSoTimeout(1500);
            Future<TimeoutException> connecting = worker.submit(() -> {
                try { socket.connect(new InetSocketAddress("127.0.0.1", server.getLocalPort()), null, 1600, 250); throw new AssertionError(); }
                catch (TimeoutException expected) { return expected; }
            });
            DatagramPacket wire = new DatagramPacket(new byte[1024], 1024);
            PacketBody8Init1 first = receive(server, wire); send(server, wire, cookie(first));
            assertEquals(2, receive(server, wire).getStep().getNumber());
            // The challenge arrives after the first-response deadline; it must still be handled.
            Thread.sleep(400);
            send(server, wire, puzzle());
            assertEquals(4, receive(server, wire).getStep().getNumber());
            assertTrue(connecting.get(3, TimeUnit.SECONDS).getMessage().contains("initSent=4 initReceived=3"));
            assertFalse(socket.wasLastConnectUnanswered());
        } finally { worker.shutdownNow(); }
    }
    @Test public void typedOptionsSupportSupertypesAndRejectWrongTypesWithoutUncheckedCasts() throws Exception {
        try (CapturingSocket socket = new CapturingSocket("127.0.0.1")) {
            socket.setOption("number", Integer.valueOf(7));
            assertEquals(7, socket.getOption("number", Number.class).intValue());
            assertEquals(Integer.valueOf(7), socket.getOption("number", Integer.class));
            assertNull(socket.getOption("missing", String.class));
            assertThrows(ClassCastException.class, () -> socket.getOption("number", String.class));
        }
    }
    @Test public void initVersionPassesCurrentPreCookieFiltersAndStaysStableAcrossSteps() throws Exception {
        long now = System.currentTimeMillis() / 1000L;
        long epoch = 1356998400L;
        // The previous hard-coded 2019 value is silently dropped by this filter.
        long minimum = now - TimeUnit.DAYS.toSeconds(365) - epoch;
        assertTrue(0x0cffd2feL < minimum);
        try (CapturingSocket socket = new CapturingSocket("127.0.0.1")) {
            LocalClientHandlerConnecting handler = new LocalClientHandlerConnecting(socket);
            handler.onAssigned();
            PacketBody8Init1 first = socket.outgoing.get(0);
            long version = Integer.toUnsignedLong(ByteBuffer.wrap(first.getVersion()).getInt());
            assertTrue("INIT must reach the cookie exchange", version >= minimum);
            assertTrue(Math.abs(version + epoch - now) <= 2);
            handler.handlePacket(incoming(new PacketBody8Init1(ProtocolRole.SERVER, cookie(first))));
            handler.handlePacket(incoming(new PacketBody8Init1(ProtocolRole.SERVER, puzzle())));
            for (PacketBody8Init1 packet : socket.outgoing) assertArrayEquals(first.getVersion(), packet.getVersion());
        }
    }
    private static class CapturingSocket extends LocalTeamspeakClientSocket {
        final InetSocketAddress endpoint;
        final List<PacketBody8Init1> outgoing = new ArrayList<>();
        CapturingSocket(String ip) throws Exception {
            endpoint = new InetSocketAddress(InetAddress.getByName(ip), 9738);
            setIdentity(LocalIdentity.load(BigInteger.ONE));
        }
        @Override public InetSocketAddress getRemoteSocketAddress() { return endpoint; }
        @Override public void writePacket(PacketBody body) { outgoing.add((PacketBody8Init1) body); }
        @Override public void close() { /* This fixture never creates a transport or starts workers. */ }
    }

    private static Packet incoming(PacketBody body) {
        Packet packet = new Packet(ProtocolRole.SERVER);
        packet.setBody(body);
        return packet;
    }

    private static PacketBody8Init1.Step1 cookie(PacketBody8Init1 first) {
        byte[] nonce = ((PacketBody8Init1.Step0) first.getStep()).getRandom();
        PacketBody8Init1.Step1 reply = new PacketBody8Init1.Step1();
        reply.setA0reversed(new byte[]{nonce[3], nonce[2], nonce[1], nonce[0]});
        return reply;
    }

    private static PacketBody8Init1.Step3 puzzle() {
        PacketBody8Init1.Step3 reply = new PacketBody8Init1.Step3();
        byte[] x = new byte[64], n = new byte[64];
        x[0] = 0x40; x[63] = 3;
        java.util.Arrays.fill(n, (byte) 0xff);
        reply.setX(x); reply.setN(n); reply.setLevel(0);
        return reply;
    }

    private static SingleCommand initCommand(PacketBody8Init1 packet) {
        byte[] data = ((PacketBody8Init1.Step4) packet.getStep()).getClientIVCommand();
        return new PacketBody2Command(ProtocolRole.CLIENT, new String(data, StandardCharsets.UTF_8))
                .parse().simplifyOne();
    }

    @Test public void clientInitCarriesTheEntireResolvedIpv4AndIpv6Address() throws Exception {
        for (String ip : new String[]{"5.180.217.221", "89.124.76.85", "1.2.3.4", "2001:db8::9", "::1"}) {
            try (CapturingSocket socket = new CapturingSocket(ip)) {
                LocalClientHandlerConnecting handler = new LocalClientHandlerConnecting(socket);
                handler.onAssigned();
                handler.handlePacket(incoming(new PacketBody8Init1(ProtocolRole.SERVER, cookie(socket.outgoing.get(0)))));
                handler.handlePacket(incoming(new PacketBody8Init1(ProtocolRole.SERVER, puzzle())));
                assertEquals(socket.endpoint.getAddress().getHostAddress(),
                        initCommand(socket.outgoing.get(2)).get("ip").getValue());
            }
        }
    }

    @Test public void puzzleSolutionIsUnsignedAndLeftPaddedForEveryBigIntegerLength() throws Exception {
        for (int length : new int[]{1, 63, 64, 65}) {
            try (CapturingSocket socket = new CapturingSocket("127.0.0.1")) {
                LocalClientHandlerConnecting handler = new LocalClientHandlerConnecting(socket);
                handler.onAssigned();
                handler.handlePacket(incoming(new PacketBody8Init1(ProtocolRole.SERVER, cookie(socket.outgoing.get(0)))));
                PacketBody8Init1.Step3 challenge = puzzle();
                byte[] expected = new byte[64];
                if (length == 65) expected[0] = (byte) 0x80;
                else expected[64 - length] = 0x40;
                expected[63] = 9;
                challenge.setX(expected); // level=0 gives x^(2^0) mod n = x.
                handler.handlePacket(incoming(new PacketBody8Init1(ProtocolRole.SERVER, challenge)));
                assertArrayEquals(expected, ((PacketBody8Init1.Step4) socket.outgoing.get(2).getStep()).getY());
            }
        }
    }

    @Test public void delayedInitRepliesCannotReplaceANewerCookieOrAlpha() throws Exception {
        try (CapturingSocket socket = new CapturingSocket("127.0.0.1")) {
            LocalClientHandlerConnecting handler = new LocalClientHandlerConnecting(socket);
            handler.onAssigned();
            Packet first = incoming(new PacketBody8Init1(ProtocolRole.SERVER, cookie(socket.outgoing.get(0))));
            Packet challenge = incoming(new PacketBody8Init1(ProtocolRole.SERVER, puzzle()));
            handler.handlePacket(first);
            handler.handlePacket(first);
            assertEquals(2, socket.outgoing.size());
            handler.handlePacket(challenge);
            handler.handlePacket(first);
            handler.handlePacket(challenge);
            assertEquals(3, socket.outgoing.size());
            assertEquals(4, socket.outgoing.get(2).getStep().getNumber());
        }
    }

    private static PacketBody8Init1 receive(DatagramSocket server, DatagramPacket wire) throws Exception {
        wire.setLength(wire.getData().length);
        server.receive(wire);
        ByteBuffer input = ByteBuffer.wrap(wire.getData(), 0, wire.getLength());
        ClientPacketHeader header = new ClientPacketHeader(); header.read(input);
        assertEquals(PacketBodyType.INIT1, header.getType());
        PacketBody8Init1 body = new PacketBody8Init1(ProtocolRole.CLIENT); body.read(input);
        return body;
    }

    private static byte[] bytes(DatagramPacket wire) {
        return java.util.Arrays.copyOf(wire.getData(), wire.getLength());
    }

    private static void send(DatagramSocket server, DatagramPacket peer, PacketBody8Init1.Step step) throws Exception {
        ServerPacketHeader header = new ServerPacketHeader();
        header.setType(PacketBodyType.INIT1); header.setPacketId(101); header.setPacketFlags(0x88);
        header.setMac("TS3INIT1".getBytes(StandardCharsets.US_ASCII));
        Packet reply = new Packet(ProtocolRole.SERVER, header);
        reply.setBody(new PacketBody8Init1(ProtocolRole.SERVER, step));
        ByteBuffer output = ByteBuffer.allocate(reply.getSize()); reply.write(output);
        server.send(new DatagramPacket(output.array(), output.position(), peer.getSocketAddress()));
    }

    @Test public void droppedRepliesAtEveryInitStepAreRetriedWithoutChangingTheExchange() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (DatagramSocket server = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
             LocalTeamspeakClientSocket socket = new LocalTeamspeakClientSocket()) {
            socket.setIdentity(LocalIdentity.load(BigInteger.ONE));
            server.setSoTimeout(2500);
            Future<TimeoutException> connecting = worker.submit(() -> {
                try {
                    socket.connect(new InetSocketAddress("127.0.0.1", server.getLocalPort()), null, 4800);
                    throw new AssertionError("The fixture intentionally stops before authentication");
                } catch (TimeoutException expected) { return expected; }
            });
            DatagramPacket wire = new DatagramPacket(new byte[1024], 1024);
            PacketBody8Init1 first = receive(server, wire);
            assertEquals(0, first.getStep().getNumber());
            byte[] original = bytes(wire);
            receive(server, wire); assertArrayEquals(original, bytes(wire));
            send(server, wire, cookie(first));

            assertEquals(2, receive(server, wire).getStep().getNumber());
            original = bytes(wire);
            receive(server, wire); assertArrayEquals(original, bytes(wire));
            send(server, wire, puzzle());

            PacketBody8Init1 finalInit = receive(server, wire);
            assertEquals(4, finalInit.getStep().getNumber());
            assertEquals("127.0.0.1", initCommand(finalInit).get("ip").getValue());
            original = bytes(wire);
            receive(server, wire); assertArrayEquals(original, bytes(wire));

            TimeoutException timeout = connecting.get(3, TimeUnit.SECONDS);
            assertTrue(timeout.getMessage(), timeout.getMessage().contains("initSent=4 initReceived=3"));
            assertTrue(timeout.getMessage(), timeout.getMessage().contains("remote=/127.0.0.1:"));
            assertEquals(ClientConnectionState.DISCONNECTED, socket.getState());
            // Drain a possible retry already in flight, then ensure cleanup stops further sends.
            server.setSoTimeout(150);
            try { while (true) receive(server, wire); } catch (SocketTimeoutException expected) { }
            server.setSoTimeout(1200);
            try { receive(server, wire); fail("INIT retry after disconnect"); }
            catch (SocketTimeoutException expected) { }
        } finally { worker.shutdownNow(); }
    }
}
