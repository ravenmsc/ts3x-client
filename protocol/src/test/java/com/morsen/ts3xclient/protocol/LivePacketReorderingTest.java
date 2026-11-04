package com.morsen.ts3xclient.protocol;

import com.github.manevolent.ts3j.event.*;
import com.github.manevolent.ts3j.identity.LocalIdentity;
import com.github.manevolent.ts3j.protocol.NetworkPacket;
import com.github.manevolent.ts3j.protocol.client.ClientConnectionState;
import com.github.manevolent.ts3j.protocol.packet.PacketBodyType;
import com.github.manevolent.ts3j.protocol.socket.client.*;
import org.junit.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Opt-in real-server regression; the artificial impairment is receiver-local. */
public class LivePacketReorderingTest {
    private static final class DelayedSocket extends LocalTeamspeakClientSocket {
        volatile boolean armed;
        volatile boolean released;
        int threshold;
        int maximumDistance;
        private NetworkPacket held;
        private boolean deliverHeld;
        @Override protected NetworkPacket readNetworkPacket(int timeout) throws IOException {
            if (deliverHeld) {
                deliverHeld = false;
                released = true;
                NetworkPacket result = held;
                held = null;
                armed = false;
                return result;
            }
            while (true) {
                NetworkPacket packet = super.readNetworkPacket(timeout);
                if (!armed || packet.getHeader().getType() != PacketBodyType.COMMAND) return packet;
                if (held == null) { held = packet; continue; }
                int distance = (packet.getHeader().getPacketId() - held.getHeader().getPacketId()) & 65535;
                if (distance == 0) continue; // hold retransmissions of the missing command as well
                if (distance < 32768) maximumDistance = Math.max(maximumDistance, distance);
                if (distance >= threshold && distance < 32768) deliverHeld = true;
                return packet;
            }
        }
    }

    @Test public void reliableCommandRecoversAfterPacketReordering() throws Exception {
        String port = System.getProperty("ts3x.test.port", "");
        Assume.assumeFalse("Requires an explicitly configured local test server", port.isEmpty());
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", Integer.parseInt(port));
        DelayedSocket receiver = new DelayedSocket();
        receiver.threshold = "TS3".equals(System.getProperty("ts3x.test.generation")) ? 32 : 160;
        LocalTeamspeakClientSocket sender = new LocalTeamspeakClientSocket();
        Set<String> messages = ConcurrentHashMap.newKeySet();
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        receiver.setExceptionHandler(errors::add);
        sender.setExceptionHandler(errors::add);
        receiver.setIdentity(LocalIdentity.generateNew(8));
        sender.setIdentity(LocalIdentity.generateNew(8));
        receiver.setNickname("TS3X reorder receiver");
        sender.setNickname("TS3X reorder sender");
        receiver.addListener(new TS3Listener() {
            @Override public void onTextMessage(TextMessageEvent event) { messages.add(event.getMessage()); }
        });
        try {
            String password = TeamSpeakCredentials.serverPassword(System.getProperty("ts3x.test.password", ""));
            receiver.connect(address, password, 10000);
            sender.connect(address, password, 10000);
            receiver.armed = true;
            for (int i = 0; i < 400; i++) sender.sendPrivateMessage(receiver.getClientId(), "TS3X burst " + i);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (messages.size() < 400 && System.nanoTime() < deadline) Thread.sleep(20);
            assertTrue("Artificially delayed packet was released; max distance=" + receiver.maximumDistance, receiver.released);
            assertEquals("Every command delivered after the gap closes", 400, messages.size());
            assertTrue("No decryption/transport errors: " + errors, errors.isEmpty());
            // Cover the reported 10-20 second interval and the first keepalive exchanges.
            for (int i = 0; i < 5; i++) {
                Thread.sleep(5000);
                sender.sendPrivateMessage(receiver.getClientId(), "TS3X keepalive " + i);
            }
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (messages.size() < 405 && System.nanoTime() < deadline) Thread.sleep(20);
            assertEquals(405, messages.size());
            assertEquals(ClientConnectionState.CONNECTED, receiver.getState());
            assertTrue("Connection remains free of transport errors: " + errors, errors.isEmpty());
            System.out.println("PASS reordered encrypted command after " + receiver.threshold
                    + " packets; all 405 messages; 25-second keepalive");
        } finally {
            for (LocalTeamspeakClientSocket socket : Arrays.asList(sender, receiver)) {
                try { socket.disconnect("TS3X local test completed"); } catch (Exception ignored) { }
                try { socket.close(); } catch (Exception ignored) { }
            }
        }
    }
}
