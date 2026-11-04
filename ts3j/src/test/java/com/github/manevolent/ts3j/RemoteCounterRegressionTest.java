package com.github.manevolent.ts3j;

import com.github.manevolent.ts3j.protocol.*;
import com.github.manevolent.ts3j.protocol.header.*;
import com.github.manevolent.ts3j.protocol.packet.*;
import com.github.manevolent.ts3j.protocol.packet.fragment.PacketReassembly;
import com.github.manevolent.ts3j.protocol.packet.transformation.PacketTransformation;
import com.github.manevolent.ts3j.protocol.socket.client.AbstractTeamspeakClientSocket;
import com.github.manevolent.ts3j.protocol.socket.client.AbstractTeamspeakClientSocket.RemoteCounterFull;
import com.github.manevolent.ts3j.protocol.socket.client.LocalTeamspeakClientSocket;
import org.junit.Test;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

public class RemoteCounterRegressionTest {
    @Test public void delayedPacketMustNotAdvanceEncryptionGeneration() {
        RemoteCounterFull counter = new RemoteCounterFull(65536, 100);
        for (int id = 0; id < 300; id++) if (id != 48) counter.put(id);
        assertEquals(0, counter.getGeneration(48));
        assertEquals(0, counter.getCurrentGeneration());
    }

    @Test public void expiredPacketCannotMoveWindowForward() {
        RemoteCounterFull counter = new RemoteCounterFull(65536, 100);
        for (int id = 0; id <= 300; id++) counter.put(id);
        assertFalse(counter.put(48));
        assertEquals(300, counter.getCurrentPacketId());
        assertEquals(0, counter.getGeneration(301));
    }

    @Test public void delayedEncryptedCommandClosesReassemblyGap() throws Exception {
        {
            // An unconnected LocalTeamspeakClientSocket has no network resources to close.
            LocalTeamspeakClientSocket socket = new LocalTeamspeakClientSocket();
            Field field = AbstractTeamspeakClientSocket.class.getDeclaredField("remoteSendCounter");
            field.setAccessible(true);
            Map<PacketBodyType, AbstractTeamspeakClientSocket.RemoteCounter> counters =
                    (Map<PacketBodyType, AbstractTeamspeakClientSocket.RemoteCounter>) field.get(socket);
            for (PacketBodyType type : Arrays.asList(PacketBodyType.COMMAND, PacketBodyType.COMMAND_LOW)) {
                AbstractTeamspeakClientSocket.RemoteCounter counter = counters.get(type);
                PacketReassembly queue = new PacketReassembly();
                PacketTransformation sender = new PacketTransformation(new byte[20], new byte[8]);
                PacketTransformation receiver = new PacketTransformation(new byte[20], new byte[8]);
                List<Integer> delivered = new ArrayList<>();
                for (int id = 0; id < 400; id++) if (id != 48) delivered.add(id);
                delivered.add(48); // retransmission after the original 100-packet history expired
                int count = 0;
                for (int id : delivered) {
                    String text = "notifytest value=" + id;
                    ServerPacketHeader header = new ServerPacketHeader();
                    header.setPacketId(id);
                    header.setType(type);
                    header.setGeneration(0);
                    Packet sent = new Packet(ProtocolRole.SERVER, header);
                    sent.setBody(type == PacketBodyType.COMMAND
                            ? new PacketBody2Command(ProtocolRole.SERVER, text)
                            : new PacketBody3CommandLow(ProtocolRole.SERVER, text));
                    ByteBuffer wire = sender.encrypt(sent);
                    header.setGeneration(counter.getGeneration(id));
                    byte[] clear = receiver.decrypt(header, wire, sent.getBody().getSize());
                    assertEquals(text, new String(clear, StandardCharsets.UTF_8));
                    assertTrue("missing reliable packet " + id, counter.put(id));
                    Packet received = new Packet(ProtocolRole.SERVER, header);
                    received.readBody(ByteBuffer.wrap(clear));
                    queue.put(received);
                    Packet assembled;
                    while ((assembled = queue.next()) != null) {
                        assertEquals(count++, assembled.getHeader().getPacketId());
                    }
                }
                assertEquals(400, count);
                assertFalse("retransmission must not be delivered twice", counter.put(48));
            }
        }
    }

    @Test public void encryptedPayloadTamperingStillFailsAuthentication() throws Exception {
        PacketTransformation crypto = new PacketTransformation(new byte[20], new byte[8]);
        ServerPacketHeader header = new ServerPacketHeader();
        header.setType(PacketBodyType.COMMAND);
        Packet packet = new Packet(ProtocolRole.SERVER, header);
        packet.setBody(new PacketBody2Command(ProtocolRole.SERVER, "notifytest value=hello"));
        ByteBuffer wire = crypto.encrypt(packet);
        wire.array()[wire.array().length - 1] ^= 1;
        try {
            crypto.decrypt(header, wire, packet.getBody().getSize());
            fail("tampered packet accepted");
        } catch (org.bouncycastle.crypto.InvalidCipherTextException expected) { }
    }

    @Test public void sequentialPacketsSpanMultipleCompleteWraps() {
        RemoteCounterFull counter = new RemoteCounterFull(65536, 100);
        for (int absolute = 0; absolute < 150000; absolute++) {
            int id = absolute & 65535;
            assertEquals(absolute / 65536, counter.getGeneration(id));
            assertTrue(counter.put(id));
            assertFalse(counter.put(id));
            assertEquals(absolute / 65536, counter.getCurrentGeneration());
            assertEquals(id, counter.getCurrentPacketId());
        }
    }

    @Test public void previousGenerationPacketIsRecognizedAfterWrap() {
        RemoteCounterFull counter = new RemoteCounterFull(65536, 32768);
        for (int absolute = 0; absolute < 66000; absolute++)
            if (absolute != 65000) counter.put(absolute & 65535);
        assertEquals(0, counter.getGeneration(65000));
        assertTrue(counter.put(65000));
        assertFalse(counter.put(65000));
        assertEquals(1, counter.getCurrentGeneration());
        assertEquals(463, counter.getCurrentPacketId());
    }

    @Test public void shuffledBurstsRetainEveryPacketAcrossWraps() {
        RemoteCounterFull counter = new RemoteCounterFull(65536, 32768);
        Random random = new Random(530);
        for (int start = 0; start < 150000; start += 500) {
            List<Integer> burst = new ArrayList<>();
            for (int id = start; id < start + 500; id++) burst.add(id);
            Collections.shuffle(burst, random);
            for (int absolute : burst) {
                int id = absolute & 65535;
                assertEquals(absolute / 65536, counter.getGeneration(id));
                assertTrue(counter.put(id));
                assertFalse(counter.put(id));
            }
        }
    }

    @Test public void resetClearsHistoryAndGeneration() {
        RemoteCounterFull counter = new RemoteCounterFull(65536, 100);
        for (int i = 0; i < 66000; i++) counter.put(i & 65535);
        counter.reset();
        assertEquals(0, counter.getCurrentGeneration());
        assertEquals(0, counter.getCurrentPacketId());
        assertEquals(0, counter.getGeneration(0));
        assertTrue(counter.put(0));
        assertFalse(counter.put(0));
    }

    @Test public void guessingGenerationDoesNotCommitUnauthenticatedPackets() {
        RemoteCounterFull counter = new RemoteCounterFull(65536, 100);
        counter.put(0);
        for (int i = 1; i < 65536; i++) counter.getGeneration(i);
        assertEquals(0, counter.getCurrentPacketId());
        assertEquals(0, counter.getCurrentGeneration());
        assertTrue(counter.put(1));
    }
}
