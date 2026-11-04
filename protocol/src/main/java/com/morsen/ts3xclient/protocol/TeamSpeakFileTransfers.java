package com.morsen.ts3xclient.protocol;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared full-client file transport for TS3 and TS6 system files (cid=0).
 * TS6's optional S3 channel storage is a separate capability: it does not turn
 * an avatar/icon ftkey into an HTTP URL. No HTTP endpoints or keys are guessed.
 * A new instance belongs to one connection and must be closed on disconnect.
 */
public final class TeamSpeakFileTransfers implements AutoCloseable {
    @FunctionalInterface public interface CommandSender {
        Map<String, ?> execute(String command, Map<String, String> arguments) throws Exception;
    }

    private static final class Pending {
        final int id;
        final String event;
        final CompletableFuture<Map<String, ?>> reply = new CompletableFuture<>();
        Pending(int id, String event) { this.id = id; this.event = event; }
    }

    private final CommandSender sender;
    private final String fallbackHost;
    private final AtomicInteger sequence = new AtomicInteger();
    private final AtomicReference<Pending> pending = new AtomicReference<>();
    private final AtomicReference<Socket> activeSocket = new AtomicReference<>();
    private volatile boolean closed;

    public TeamSpeakFileTransfers(CommandSender sender, String fallbackHost) {
        this.sender = sender;
        this.fallbackHost = fallbackHost;
    }

    /** Called on the event thread; never blocks it waiting for commands or TCP I/O. */
    public void onNotification(String command, Map<String, ?> values) {
        Pending request = pending.get();
        if (request == null || closed || number(values, "clientftfid", -1) != request.id) return;
        if (request.event.equals(command)) request.reply.complete(new LinkedHashMap<>(values));
        else if ("notifystatusfiletransfer".equals(command) || "notifyfiletransferstatus".equals(command)) {
            long status = number(values, "status", 0);
            if (status != 0) request.reply.completeExceptionally(
                    new IOException("TeamSpeak file transfer failed (status " + status + ")"));
        }
    }

    private Map<String, ?> start(String command, Map<String, String> arguments) throws Exception {
        if (closed) throw new IOException("TeamSpeak file transfer session is closed");
        int id = sequence.updateAndGet(value -> value >= 65535 ? 1 : value + 1);
        Pending request = new Pending(id,
                "ftinitupload".equals(command) ? "notifystartupload" : "notifystartdownload");
        if (!pending.compareAndSet(null, request)) throw new IOException("File transfer already pending");
        Map<String, String> params = new LinkedHashMap<>(arguments);
        params.put("clientftfid", Integer.toString(id));
        // Proto 0 is supported by both generations. Proto 1 is not HTTP/S3.
        params.put("proto", "0");
        try {
            if (closed) throw new IOException("TeamSpeak file transfer session is closed");
            Map<String, ?> immediate = sender.execute(command, params);
            if (immediate != null && immediate.containsKey("ftkey")) {
                Object replyId = immediate.get("clientftfid");
                if (replyId == null || number(immediate, "clientftfid", -1) == id)
                    request.reply.complete(new LinkedHashMap<>(immediate));
            }
            return request.reply.get(5, TimeUnit.SECONDS);
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw error;
        } finally { pending.compareAndSet(request, null); }
    }

    public synchronized void uploadSystemFile(String path, byte[] bytes) throws Exception {
        Map<String, String> params = systemFile(path);
        params.put("size", Integer.toString(bytes.length));
        params.put("overwrite", "1");
        params.put("resume", "0");
        Map<String, ?> init = start("ftinitupload", params);
        long seek = number(init, "seekpos", 0);
        if (seek < 0 || seek > bytes.length) throw new IOException("Invalid upload offset");
        try (Socket socket = open(init)) {
            OutputStream output = socket.getOutputStream();
            output.write(key(init));
            output.write(bytes, (int) seek, bytes.length - (int) seek);
            output.flush();
            socket.shutdownOutput();
        } finally { activeSocket.set(null); }
    }

    public synchronized byte[] downloadSystemFile(String path, int maximumBytes) throws Exception {
        if (maximumBytes <= 0) throw new IllegalArgumentException("Invalid download limit");
        Map<String, String> params = systemFile(path);
        params.put("seekpos", "0");
        Map<String, ?> init = start("ftinitdownload", params);
        long size = number(init, "size", -1);
        if (size < -1 || size > maximumBytes) throw new IOException("File exceeds download limit");
        if (number(init, "seekpos", 0) != 0) throw new IOException("Unexpected download offset");
        try (Socket socket = open(init)) {
            OutputStream output = socket.getOutputStream();
            output.write(key(init));
            output.flush();
            // Keep output open: closing it also closes the socket on Android.
            InputStream input = socket.getInputStream();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            while (size < 0 || bytes.size() < size) {
                int count = size >= 0 ? (int) Math.min(buffer.length, size - bytes.size())
                        : Math.min(buffer.length, maximumBytes - bytes.size() + 1);
                int read = input.read(buffer, 0, count);
                if (read < 0) {
                    if (size >= 0 && bytes.size() != size) throw new EOFException("Incomplete TeamSpeak file");
                    break;
                }
                if (read == 0) continue;
                if ((long) bytes.size() + read > maximumBytes) throw new IOException("File exceeds download limit");
                bytes.write(buffer, 0, read);
            }
            return bytes.toByteArray();
        } finally { activeSocket.set(null); }
    }

    private static Map<String, String> systemFile(String path) {
        if (path == null || !path.startsWith("/") || path.contains("..") || path.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Invalid TeamSpeak system file path");
        Map<String, String> params = new LinkedHashMap<>();
        params.put("cid", "0");
        params.put("cpw", "");
        params.put("name", path);
        return params;
    }

    private Socket open(Map<String, ?> init) throws IOException {
        key(init); // Validate before opening a connection, and never log the ftkey.
        long port = number(init, "port", 30033);
        if (port < 1 || port > 65535) throw new IOException("Invalid file transfer port");
        long proto = number(init, "proto", 0);
        if (proto != 0 && proto != 1) throw new IOException("Unsupported file transfer protocol: " + proto);
        String host = text(init, "ip");
        if (host.isEmpty() || "0.0.0.0".equals(host) || "::".equals(host)) host = fallbackHost;
        if (host == null || host.isEmpty()) throw new IOException("File transfer host unavailable");
        Socket socket = new Socket();
        activeSocket.set(socket);
        try {
            if (closed) throw new IOException("TeamSpeak file transfer session is closed");
            socket.connect(new InetSocketAddress(host, (int) port), 8000);
            socket.setSoTimeout(8000);
            return socket;
        } catch (IOException | RuntimeException error) {
            socket.close();
            activeSocket.compareAndSet(socket, null);
            throw error;
        }
    }

    private static byte[] key(Map<String, ?> init) throws IOException {
        String key = text(init, "ftkey");
        if (!key.matches("[\\x21-\\x7e]{32}")) throw new IOException("Invalid TeamSpeak file transfer key");
        return key.getBytes(StandardCharsets.US_ASCII);
    }

    private static String text(Map<String, ?> values, String key) {
        Object value = values.get(key);
        return value == null ? "" : value.toString().trim();
    }

    private static long number(Map<String, ?> values, String key, long fallback) {
        try { return Long.parseLong(text(values, key)); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    @Override public void close() {
        closed = true;
        Pending request = pending.getAndSet(null);
        if (request != null) request.reply.completeExceptionally(new IOException("TeamSpeak disconnected"));
        Socket socket = activeSocket.getAndSet(null);
        if (socket != null) try { socket.close(); } catch (IOException ignored) { }
    }
}
