package com.morsen.ts3xclient.protocol;

import org.junit.Test;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class TeamSpeakAvatarPublicationTest {
    private static final String KEY = "0123456789abcdef0123456789abcdef";
    private static final byte[] IMAGE = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aC1cAAAAASUVORK5CYII=");

    @Test public void strictServerAcceptsContentHashUploadAndAnotherClientDownloadsItsUidFile() throws Exception {
        String expectedHash = "e26bf868998907f0684107767a194611";
        String uid = Base64.getEncoder().encodeToString(new byte[32]);
        String storedPath = "/" + TeamSpeakIdentity.avatarFileName(uid);
        CompletableFuture<byte[]> stored = new CompletableFuture<>();
        CompletableFuture<Void> served = new CompletableFuture<>();
        AtomicBoolean announced = new AtomicBoolean();
        AtomicReference<TeamSpeakFileTransfers> ref = new AtomicReference<>();
        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(3000);
            Thread peer = new Thread(() -> {
                try {
                    try (Socket upload = server.accept()) {
                        upload.setSoTimeout(3000);
                        assertEquals(KEY, new String(upload.getInputStream().readNBytes(32), StandardCharsets.US_ASCII));
                        stored.complete(upload.getInputStream().readAllBytes());
                    }
                    try (Socket download = server.accept()) {
                        download.setSoTimeout(3000);
                        assertEquals(KEY, new String(download.getInputStream().readNBytes(32), StandardCharsets.US_ASCII));
                        download.getOutputStream().write(stored.get(2, TimeUnit.SECONDS));
                    }
                    served.complete(null);
                } catch (Throwable error) {
                    stored.completeExceptionally(error);
                    served.completeExceptionally(error);
                }
            });
            peer.setDaemon(true);
            peer.start();
            try (TeamSpeakFileTransfers transfers = new TeamSpeakFileTransfers((command, params) -> {
                assertEquals("0", params.get("cid"));
                if (command.equals("ftinitupload")) {
                    // Reproduce the failing server: a UID filename is not a valid upload hash.
                    if (!params.get("name").equals("/avatar_" + expectedHash)) {
                        ref.get().onNotification("notifystatusfiletransfer", Map.of(
                                "clientftfid", params.get("clientftfid"), "status", "1540"));
                        return Map.of();
                    }
                    assertEquals("1", params.get("overwrite"));
                    assertEquals("0", params.get("resume"));
                    assertEquals(Integer.toString(IMAGE.length), params.get("size"));
                } else {
                    assertEquals("ftinitdownload", command);
                    assertTrue(announced.get());
                    assertEquals(storedPath, params.get("name"));
                }
                return Map.of("ftkey", KEY, "port", server.getLocalPort(), "size", IMAGE.length);
            }, "127.0.0.1")) {
                ref.set(transfers);
                assertTrue(assertThrows(IOException.class, () -> transfers.uploadSystemFile(storedPath, IMAGE))
                        .getMessage().contains("1540"));
                String hash = TeamSpeakAvatarPublication.publish(transfers, IMAGE, value -> {
                    assertEquals(expectedHash, value);
                    assertArrayEquals(IMAGE, stored.get(2, TimeUnit.SECONDS));
                    announced.set(true);
                });
                assertEquals(expectedHash, hash);
                assertArrayEquals(IMAGE, transfers.downloadSystemFile(storedPath, 512 * 1024));
            }
            served.get(3, TimeUnit.SECONDS);
        }
    }

    @Test public void serverRejectionDoesNotAdvertiseAnAvatarThatWasNotUploaded() {
        AtomicReference<TeamSpeakFileTransfers> ref = new AtomicReference<>();
        try (TeamSpeakFileTransfers transfers = new TeamSpeakFileTransfers((command, params) -> {
            ref.get().onNotification("notifystatusfiletransfer", Map.of(
                    "clientftfid", params.get("clientftfid"), "status", "2568"));
            return Map.of();
        }, "127.0.0.1")) {
            ref.set(transfers);
            IOException failure = assertThrows(IOException.class, () ->
                    TeamSpeakAvatarPublication.publish(transfers, IMAGE, hash -> fail("Upload was denied")));
            assertTrue(failure.getMessage().contains("2568"));
        }
    }

    @Test public void invalidTransferKeyDoesNotAdvertiseAnAvatar() {
        try (TeamSpeakFileTransfers transfers = new TeamSpeakFileTransfers((command, params) ->
                Map.of("ftkey", "invalid-key", "port", "30033"), "127.0.0.1")) {
            assertThrows(IOException.class, () -> TeamSpeakAvatarPublication.publish(
                    transfers, IMAGE, hash -> fail("The upload never reached TCP")));
        }
    }

    @Test public void invalidImagesAreRejectedBeforeSendingCommands() {
        try (TeamSpeakFileTransfers transfers = new TeamSpeakFileTransfers((command, params) -> {
            fail("No upload for an invalid size"); return Map.of();
        }, "127.0.0.1")) {
            for (byte[] invalid : new byte[][]{null, new byte[0], new byte[512 * 1024 + 1]}) {
                assertThrows(IllegalArgumentException.class, () -> TeamSpeakAvatarPublication.publish(
                        transfers, invalid, hash -> fail("No flag for an invalid image")));
            }
        }
    }
}
