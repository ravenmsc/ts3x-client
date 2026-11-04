package com.morsen.ts3xclient.protocol;

import org.junit.Test;
import java.io.EOFException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class TeamSpeakFileTransfersTest {
    private static final String KEY = "0123456789abcdef0123456789abcdef";

    private static Map<String, Object> start(ServerSocket server, long size) {
        Map<String, Object> reply = new HashMap<>();
        reply.put("ftkey", KEY); reply.put("port", server.getLocalPort()); reply.put("size", size);
        return reply;
    }

    @Test public void notificationBeforeAcknowledgementAndStaleTransferIdAreHandled() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            byte[] content = "avatar bytes".getBytes(StandardCharsets.UTF_8);
            CompletableFuture<Void> sent = serve(server, content);
            AtomicReference<TeamSpeakFileTransfers> ref = new AtomicReference<>();
            TeamSpeakFileTransfers transport = new TeamSpeakFileTransfers((command, params) -> {
                assertEquals("ftinitdownload", command);
                assertEquals("0", params.get("cid"));
                assertEquals("0", params.get("proto"));
                Map<String, Object> stale = start(server, 1000000);
                stale.put("clientftfid", "65535");
                ref.get().onNotification("notifystartdownload", stale);
                Map<String, Object> correct = start(server, content.length);
                correct.put("clientftfid", params.get("clientftfid"));
                ref.get().onNotification("notifystartdownload", correct);
                return Map.of();
            }, "127.0.0.1");
            ref.set(transport);
            try (transport) { assertArrayEquals(content, transport.downloadSystemFile("/avatar_test", 1024)); }
            sent.get(3, TimeUnit.SECONDS);
        }
    }

    @Test public void uploadsTheKeyThenTheEntireBody() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<byte[]> received = new CompletableFuture<>();
            Thread peer = new Thread(() -> {
                try (Socket socket = server.accept()) { received.complete(socket.getInputStream().readAllBytes()); }
                catch (Throwable error) { received.completeExceptionally(error); }
            });
            peer.setDaemon(true); peer.start();
            try (TeamSpeakFileTransfers transport = new TeamSpeakFileTransfers(
                    (command, params) -> {
                        assertEquals("ftinitupload", command);
                        assertEquals("0", params.get("resume"));
                        return start(server, 4);
                    }, "127.0.0.1")) {
                transport.uploadSystemFile("/avatar_test", new byte[]{1, 2, 3, 4});
            }
            byte[] actual = received.get(3, TimeUnit.SECONDS);
            assertEquals(KEY, new String(actual, 0, 32, StandardCharsets.US_ASCII));
            assertArrayEquals(new byte[]{1, 2, 3, 4}, java.util.Arrays.copyOfRange(actual, 32, actual.length));
        }
    }

    @Test public void truncatedDownloadsFailInsteadOfReturningACorruptAvatar() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Void> sent = serve(server, new byte[]{1, 2});
            try (TeamSpeakFileTransfers transport = new TeamSpeakFileTransfers(
                    (command, params) -> start(server, 4), "127.0.0.1")) {
                assertThrows(EOFException.class, () -> transport.downloadSystemFile("/icon_1", 32));
            }
            sent.get(3, TimeUnit.SECONDS);
        }
    }

    @Test public void missingSizeStillHasAHardMemoryLimit() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Void> sent = serve(server, new byte[128]);
            try (TeamSpeakFileTransfers transport = new TeamSpeakFileTransfers(
                    (command, params) -> start(server, -1), "127.0.0.1")) {
                assertThrows(IOException.class, () -> transport.downloadSystemFile("/icon_1", 32));
            }
            sent.get(3, TimeUnit.SECONDS);
        }
    }

    @Test public void announcedOversizeIsRejectedBeforeOpeningASocket() throws Exception {
        try (TeamSpeakFileTransfers transport = new TeamSpeakFileTransfers(
                (command, params) -> Map.of("size", "999999999999", "ftkey", KEY, "port", "1"), "127.0.0.1")) {
            assertThrows(IOException.class, () -> transport.downloadSystemFile("/icon_1", 1024));
        }
    }

    @Test public void commandDenialsArePreservedWithoutRetriesOrProtocolSwitches() throws Exception {
        IOException denied = new IOException("insufficient client permissions");
        try (TeamSpeakFileTransfers transport = new TeamSpeakFileTransfers(
                (command, params) -> { throw denied; }, "127.0.0.1")) {
            assertSame(denied, assertThrows(IOException.class, () -> transport.downloadSystemFile("/icon_1", 32)));
        }
    }

    @Test public void transferFailureNotificationIsNotMisreportedAsATimeout() throws Exception {
        AtomicReference<TeamSpeakFileTransfers> ref = new AtomicReference<>();
        try (TeamSpeakFileTransfers transport = new TeamSpeakFileTransfers((command, params) -> {
            ref.get().onNotification("notifystatusfiletransfer", Map.of(
                    "clientftfid", params.get("clientftfid"), "status", "2051"));
            return Map.of();
        }, "127.0.0.1")) {
            ref.set(transport);
            assertTrue(assertThrows(IOException.class, () -> transport.downloadSystemFile("/icon_1", 32))
                    .getMessage().contains("2051"));
        }
    }

    @Test public void missingAvatarDoesNotDelayTheNextValidDownload() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            byte[] content = "present avatar".getBytes(StandardCharsets.UTF_8);
            CompletableFuture<Void> sent = serve(server, content);
            AtomicReference<TeamSpeakFileTransfers> ref = new AtomicReference<>();
            try (TeamSpeakFileTransfers transport = new TeamSpeakFileTransfers((command, params) -> {
                if (params.get("name").equals("/avatar_missing")) {
                    ref.get().onNotification("notifystatusfiletransfer", Map.of(
                            "clientftfid", params.get("clientftfid"), "status", "2054"));
                    return Map.of();
                }
                // A late error from the preceding transfer must not cancel this one.
                ref.get().onNotification("notifystatusfiletransfer", Map.of("clientftfid", "1", "status", "2054"));
                return start(server, content.length);
            }, "127.0.0.1")) {
                ref.set(transport);
                long began = System.nanoTime();
                assertTrue(assertThrows(IOException.class,
                        () -> transport.downloadSystemFile("/avatar_missing", 1024)).getMessage().contains("2054"));
                assertArrayEquals(content, transport.downloadSystemFile("/avatar_present", 1024));
                assertTrue("An absent avatar must not consume the 5-second notification timeout",
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began) < 2000);
            }
            sent.get(3, TimeUnit.SECONDS);
        }
    }

    @Test public void closedSessionRejectsNewTransfers() {
        TeamSpeakFileTransfers transport = new TeamSpeakFileTransfers((command, params) -> {
            fail("A closed connection must not send commands"); return Map.of();
        }, "127.0.0.1");
        transport.close();
        assertThrows(IOException.class, () -> transport.downloadSystemFile("/icon_1", 32));
    }

    @Test public void disconnectCancelsAPendingNotificationWait() throws Exception {
        java.util.concurrent.CountDownLatch commandSent = new java.util.concurrent.CountDownLatch(1);
        TeamSpeakFileTransfers transport = new TeamSpeakFileTransfers((command, params) -> {
            commandSent.countDown(); return Map.of();
        }, "127.0.0.1");
        CompletableFuture<Throwable> result = new CompletableFuture<>();
        Thread worker = new Thread(() -> {
            try { transport.downloadSystemFile("/icon_1", 32); result.complete(null); }
            catch (Throwable error) { result.complete(error); }
        });
        worker.setDaemon(true); worker.start();
        assertTrue(commandSent.await(1, TimeUnit.SECONDS));
        transport.close();
        assertTrue(result.get(1, TimeUnit.SECONDS) instanceof IOException);
    }

    @Test public void invalidNegotiatedCoordinatesAreRejected() {
        for (Map<String, ?> invalid : java.util.List.of(
                Map.of("ftkey", "bad", "port", 1),
                Map.of("ftkey", KEY, "port", 65536),
                Map.of("ftkey", KEY, "port", 1, "proto", 2))) {
            try (TeamSpeakFileTransfers transport = new TeamSpeakFileTransfers(
                    (command, params) -> invalid, "127.0.0.1")) {
                assertThrows(IOException.class, () -> transport.downloadSystemFile("/icon_1", 32));
            }
        }
    }

    private static CompletableFuture<Void> serve(ServerSocket server, byte[] bytes) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        Thread peer = new Thread(() -> {
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(3000);
                assertEquals(KEY, new String(socket.getInputStream().readNBytes(32), StandardCharsets.US_ASCII));
                socket.getOutputStream().write(bytes);
                result.complete(null);
            } catch (Throwable error) { result.completeExceptionally(error); }
        });
        peer.setDaemon(true); peer.start();
        return result;
    }
}
