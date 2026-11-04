package com.github.manevolent.ts3j;

import com.github.manevolent.ts3j.protocol.*;
import com.github.manevolent.ts3j.protocol.client.ClientConnectionState;
import com.github.manevolent.ts3j.protocol.header.*;
import com.github.manevolent.ts3j.protocol.packet.*;
import com.github.manevolent.ts3j.protocol.packet.handler.PacketHandler;
import com.github.manevolent.ts3j.protocol.socket.client.*;
import org.junit.Test;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class HeartbeatRegressionTest {
    public static class IdleHandler extends PacketHandler {
        public IdleHandler(TeamspeakClientSocket socket) { super(socket); }
    }
    private static class QuietSocket extends LocalTeamspeakClientSocket {
        final BlockingQueue<NetworkPacket> incoming = new LinkedBlockingQueue<>();
        final CountDownLatch firstRead = new CountDownLatch(1);
        final AtomicInteger pings = new AtomicInteger();
        final ExecutorService events = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r); t.setDaemon(true); return t;
        });
        volatile boolean answerPings;
        volatile int firstReadTimeout;
        QuietSocket(boolean answer) { answerPings = answer; setCommandExecutorService(events); }
        @Override protected Class<? extends PacketHandler> getHandlerClass(ClientConnectionState state) { return IdleHandler.class; }
        @Override protected NetworkPacket readNetworkPacket(int timeout) throws IOException {
            if (firstRead.getCount() > 0) { firstReadTimeout = timeout; firstRead.countDown(); }
            try {
                NetworkPacket packet = incoming.poll(timeout, TimeUnit.MILLISECONDS);
                if (packet == null) throw new SocketTimeoutException();
                return packet;
            } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new EOFException(); }
        }
        @Override protected void writeNetworkPacket(NetworkPacket packet) throws IOException {
            if (packet.getHeader().getType() == PacketBodyType.PING) {
                pings.incrementAndGet();
                if (answerPings) inject(new PacketBody5Pong(ProtocolRole.SERVER,packet.getHeader().getPacketId()),
                        packet.getHeader().getPacketId(),false);
            }
        }
        void inject(PacketBody body, int id, boolean encrypted) throws IOException { inject(body,id,encrypted,false); }
        void inject(PacketBody body, int id, boolean encrypted, boolean corrupt) throws IOException {
            ServerPacketHeader header = new ServerPacketHeader(); header.setPacketId(id); header.setType(body.getType());
            header.setPacketFlag(HeaderFlag.UNENCRYPTED,!encrypted);
            Packet sent = new Packet(ProtocolRole.SERVER,header);sent.setBody(body);
            ByteBuffer wire = encrypted ? getTransformation().encrypt(sent) : sent.write(ByteBuffer.allocate(sent.getSize()));
            if (corrupt) wire.array()[wire.array().length - 1] ^= 1;
            ByteBuffer input = ByteBuffer.wrap(wire.array());
            ServerPacketHeader received = new ServerPacketHeader();received.read(input);
            incoming.add(new NetworkPacket(new DatagramPacket(wire.array(),wire.array().length),received,input));
        }
        void begin() throws Exception {
            setState(ClientConnectionState.CONNECTING);
            assertTrue(firstRead.await(2,TimeUnit.SECONDS));
            setState(ClientConnectionState.CONNECTED);
        }
        @Override public void close() throws IOException {
            try { setState(ClientConnectionState.DISCONNECTED); }
            catch (TimeoutException error) { throw new IOException(error); }
            events.shutdownNow();
        }
    }
    @Test public void stateTransitionOutOfRetrievingDoesNotLeaveAnInfiniteReceive() throws Exception {
        try (QuietSocket socket = new QuietSocket(true)) {
            socket.begin();
            assertTrue("Receive must periodically observe connection-state changes", socket.firstReadTimeout <= 1000);
        }
    }
    @Test public void quietNewConnectionSendsPingsBeforeAnyNewIncomingPacket() throws Exception {
        try (QuietSocket socket = new QuietSocket(true)) {
            socket.begin();
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(socket.pings.get()==0 && System.nanoTime()<until)Thread.sleep(10);
            assertTrue("First keepalive must not depend on another server notification",socket.pings.get()>0);
            assertTrue(socket.isConnected());
        }
    }
    @Test public void authenticatedStreamingTrafficKeepsTheTransportAliveWithoutPongs() throws Exception {
        long oldTimeout=AbstractTeamspeakClientSocket.TIMEOUT;
        AbstractTeamspeakClientSocket.TIMEOUT=700;
        try (QuietSocket socket=new QuietSocket(false)) {
            socket.begin();
            for(int id=0;id<18;id++) {
                socket.inject(new PacketBody2Command(ProtocolRole.SERVER,"notifytest value="+id),id,true);
                Thread.sleep(100);
            }
            assertTrue("Valid subscription traffic is evidence of liveness",socket.isConnected());
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(socket.isConnected()&&System.nanoTime()<until)Thread.sleep(20);
            assertFalse("Silent unreachable peers must still time out",socket.isConnected());
        } finally {AbstractTeamspeakClientSocket.TIMEOUT=oldTimeout;}
    }
    @Test public void invalidCiphertextDoesNotRefreshTheLivenessDeadline() throws Exception {
        long oldTimeout=AbstractTeamspeakClientSocket.TIMEOUT;
        AbstractTeamspeakClientSocket.TIMEOUT=700;
        try (QuietSocket socket=new QuietSocket(false)) {
            socket.setExceptionHandler(error -> {});
            socket.begin();
            for(int id=0;id<12 && socket.isConnected();id++) {
                socket.inject(new PacketBody2Command(ProtocolRole.SERVER,"notifytest value="+id),id,true,true);
                Thread.sleep(100);
            }
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(socket.isConnected()&&System.nanoTime()<until)Thread.sleep(20);
            assertFalse(socket.isConnected());
        } finally {AbstractTeamspeakClientSocket.TIMEOUT=oldTimeout;}
    }
    @Test public void replayedAuthenticatedPacketsCannotKeepASilentPeerAlive() throws Exception {
        long oldTimeout=AbstractTeamspeakClientSocket.TIMEOUT;
        AbstractTeamspeakClientSocket.TIMEOUT=700;
        try (QuietSocket socket=new QuietSocket(false)) {
            socket.begin();
            for(int i=0;i<12 && socket.isConnected();i++) {
                socket.inject(new PacketBody2Command(ProtocolRole.SERVER,"notifytest value=original"),0,true);
                Thread.sleep(100);
            }
            assertFalse("Duplicate packets must not extend the idle deadline",socket.isConnected());
        }finally{AbstractTeamspeakClientSocket.TIMEOUT=oldTimeout;}
    }

}
