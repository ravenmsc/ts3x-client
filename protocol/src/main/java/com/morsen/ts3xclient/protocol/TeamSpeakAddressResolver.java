package com.morsen.ts3xclient.protocol;

import javax.net.SocketFactory;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** TeamSpeak name discovery, separate from authentication and the voice transport. */
public final class TeamSpeakAddressResolver {
    public static final int DEFAULT_PORT = 9987;
    private final Backend backend;
    private final Random random;

    public interface Backend {
        List<InetAddress> addresses(String host) throws IOException;
        List<Service> services(String name) throws IOException;
        String tsdns(InetSocketAddress server, String query) throws IOException;
    }

    public static final class Service {
        public final int priority, weight, port;
        public final String target;
        public Service(int priority, int weight, int port, String target) {
            this.priority = priority; this.weight = weight; this.port = port; this.target = target;
        }
    }

    public static final class Resolved {
        private final List<InetSocketAddress> endpoints;
        private final String method;
        Resolved(InetSocketAddress endpoint, String method) { this(Collections.singletonList(endpoint), method); }
        Resolved(List<InetSocketAddress> endpoints, String method) {
            if (endpoints.isEmpty()) throw new IllegalArgumentException("No TeamSpeak endpoint");
            this.endpoints = Collections.unmodifiableList(new ArrayList<>(new LinkedHashSet<>(endpoints)));
            this.method = method;
        }
        public InetSocketAddress getEndpoint() { return endpoints.get(0); }
        public List<InetSocketAddress> getEndpoints() { return endpoints; }
        public String getMethod() { return method; }
        public Resolved preferring(InetSocketAddress endpoint) {
            if (!endpoints.contains(endpoint)) throw new IllegalArgumentException("Endpoint was not advertised");
            List<InetSocketAddress> reordered = new ArrayList<>(endpoints);
            reordered.remove(endpoint); reordered.add(0, endpoint);
            return new Resolved(reordered, method);
        }
    }

    public TeamSpeakAddressResolver(Backend backend) { this(backend, new Random()); }
    public TeamSpeakAddressResolver(Backend backend, Random random) {
        this.backend = Objects.requireNonNull(backend); this.random = Objects.requireNonNull(random);
    }

    public Resolved resolve(String input, int requestedPort) throws IOException {
        requirePort(requestedPort);
        String host = normalizeHost(input);
        InetAddress literal = literal(host);
        if (literal != null) return new Resolved(new InetSocketAddress(literal, requestedPort), "IP");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        List<IOException> failures = new ArrayList<>();
        // Preserve existing host:custom-port profiles when normal DNS already works.
        if (requestedPort != DEFAULT_PORT) {
            Resolved direct = dns(host, requestedPort, failures);
            if (direct != null) return direct;
        }
        for (Service service : services("_ts3._udp." + host, failures)) {
            if (System.nanoTime() >= deadline) break;
            Resolved answer = dns(service.target, effectivePort(requestedPort, service.port), failures);
            if (answer != null) return new Resolved(answer.endpoints, "SRV");
        }
        // TSDNS can be advertised by the parent zone while the full name has no A/AAAA record.
        for (String zone : zones(host)) {
            if (System.nanoTime() >= deadline) break;
            for (Service service : services("_tsdns._tcp." + zone, failures)) {
                if (System.nanoTime() >= deadline) break;
                try {
                    for (InetAddress address : backend.addresses(service.target)) {
                        if (System.nanoTime() >= deadline) break;
                        String response = backend.tsdns(new InetSocketAddress(address, service.port), host);
                        List<InetSocketAddress> endpoints = parseTsdns(response, requestedPort);
                        if (!endpoints.isEmpty()) {
                            // Preserve explicit-port compatibility, but retain the service's
                            // fixed destinations as fallbacks instead of discarding them.
                            LinkedHashSet<InetSocketAddress> candidates = new LinkedHashSet<>();
                            for (InetSocketAddress endpoint : endpoints) {
                                if (requestedPort != DEFAULT_PORT)
                                    candidates.add(new InetSocketAddress(endpoint.getAddress(), requestedPort));
                                candidates.add(endpoint);
                            }
                            return new Resolved(new ArrayList<>(candidates), "TSDNS");
                        }
                    }
                } catch (IOException failure) { remember(failures, failure); }
            }
        }
        Resolved direct = dns(host, requestedPort, failures);
        if (direct != null) return direct;
        UnknownHostException failure = new UnknownHostException(
                "Unable to resolve TeamSpeak address " + host + " using DNS, SRV or TSDNS");
        for (IOException cause : failures) failure.addSuppressed(cause);
        throw failure;
    }

    private Resolved dns(String host, int port, List<IOException> failures) {
        try {
            List<InetSocketAddress> endpoints = new ArrayList<>();
            for (InetAddress address : backend.addresses(host)) {
                endpoints.add(new InetSocketAddress(address, port));
                if (endpoints.size() == 4) break;
            }
            if (!endpoints.isEmpty()) return new Resolved(endpoints, "DNS");
        } catch (IOException failure) { remember(failures, failure); }
        return null;
    }

    private List<Service> services(String name, List<IOException> failures) {
        List<Service> records = new ArrayList<>();
        try {
            for (Service record : backend.services(name)) {
                if (record == null || record.target == null || record.target.equals(".")
                        || record.port < 1 || record.port > 65535 || record.priority < 0 || record.priority > 65535
                        || record.weight < 0 || record.weight > 65535) continue;
                try {
                    records.add(new Service(record.priority, record.weight, record.port, normalizeHost(record.target)));
                } catch (IllegalArgumentException ignored) { /* malformed record, try the remaining records */ }
            }
        } catch (IOException failure) { remember(failures, failure); }
        records.sort(Comparator.comparingInt((Service service) -> service.priority)
                .thenComparingInt(service -> service.weight == 0 ? 0 : 1));
        if (records.size() > 16) records = new ArrayList<>(records.subList(0, 16));
        List<Service> ordered = new ArrayList<>();
        while (!records.isEmpty()) {
            int priority = records.get(0).priority, total = 0;
            for (Service record : records) if (record.priority == priority) total += record.weight;
            int roll = total == 0 ? 0 : random.nextInt(total + 1), cumulative = 0, selected = 0;
            if (total == 0) {
                int count = 0;
                while (count < records.size() && records.get(count).priority == priority) count++;
                selected = random.nextInt(count);
            } else {
                for (int i = 0; i < records.size() && records.get(i).priority == priority; i++) {
                    cumulative += records.get(i).weight;
                    if (cumulative >= roll) { selected = i; break; }
                }
            }
            ordered.add(records.remove(selected));
        }
        return ordered;
    }

    private static void remember(List<IOException> failures, IOException failure) {
        if (failures.size() < 8) failures.add(failure);
    }

    private static List<String> zones(String host) {
        String[] labels = host.split("\\.");
        LinkedHashSet<String> result = new LinkedHashSet<>();
        // Never query a top-level domain or walk an unbounded number of labels.
        for (int start = 0; start < labels.length - 1 && start < 4; start++)
            result.add(String.join(".", Arrays.copyOfRange(labels, start, labels.length)));
        if (labels.length >= 2) result.add(labels[labels.length - 2] + "." + labels[labels.length - 1]);
        return new ArrayList<>(result);
    }

    private static int effectivePort(int requested, int discovered) {
        return requested == DEFAULT_PORT ? discovered : requested;
    }

    private static void requirePort(int port) {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid TeamSpeak port");
    }

    private static String normalizeHost(String input) {
        if (input == null) throw new IllegalArgumentException("Empty TeamSpeak host");
        String host = input.trim();
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        if (host.isEmpty() || host.length() > 253 || host.chars().anyMatch(c -> c <= 32 || c == 127))
            throw new IllegalArgumentException("Invalid TeamSpeak host");
        if (host.indexOf(':') >= 0) {
            if (literal(host) == null) throw new IllegalArgumentException("Invalid IPv6 address");
            return host;
        }
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        String ascii = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        if (ascii.isEmpty() || ascii.length() > 253) throw new IllegalArgumentException("Invalid TeamSpeak host");
        return ascii;
    }

    private static InetAddress literal(String host) {
        try {
            if (host.indexOf(':') >= 0) {
                InetAddress address = InetAddress.getByName(host);
                return address;
            }
            String[] parts = host.split("\\.", -1);
            if (parts.length != 4) return null;
            byte[] bytes = new byte[4];
            for (int i = 0; i < 4; i++) {
                if (!parts[i].matches("[0-9]{1,3}")) return null;
                int value = Integer.parseInt(parts[i]);
                if (value > 255) return null;
                bytes[i] = (byte) value;
            }
            return InetAddress.getByAddress(bytes);
        } catch (IOException | IllegalArgumentException ignored) { return null; }
    }

    /** Only numeric IP addresses are accepted: TSDNS is not a recursive redirect protocol. */
    public static List<InetSocketAddress> parseTsdns(String response, int requestedPort) throws IOException {
        requirePort(requestedPort);
        if (response == null || response.length() > 1024) throw new IOException("Invalid TSDNS reply size");
        String text = response.trim();
        if (text.isEmpty() || text.equals("404") || text.equalsIgnoreCase("NORESPONSE"))
            throw new IOException("TSDNS name not found");
        List<InetSocketAddress> result = new ArrayList<>();
        for (String token : text.split("\\s+")) {
            if (result.size() == 4) break;
            String host = token, portText = Integer.toString(DEFAULT_PORT);
            if (token.startsWith("[")) {
                int end = token.indexOf(']');
                if (end < 0) continue;
                host = token.substring(1, end);
                String tail = token.substring(end + 1);
                if (!tail.isEmpty()) { if (!tail.startsWith(":")) continue; portText = tail.substring(1); }
            } else if (token.indexOf(':') >= 0 && token.indexOf(':') == token.lastIndexOf(':')) {
                host = token.substring(0, token.indexOf(':')); portText = token.substring(token.indexOf(':') + 1);
            }
            InetAddress address = literal(host);
            if (address == null) continue;
            try {
                int port = portText.equals("$PORT") ? requestedPort : Integer.parseInt(portText);
                requirePort(port);
                // Keep fixed destinations intact. $PORT alone substitutes the
                // input port; a bare IP has the protocol's default voice port.
                result.add(new InetSocketAddress(address, port));
            } catch (IllegalArgumentException ignored) { /* try the next address in a multi-address reply */ }
        }
        if (result.isEmpty()) throw new IOException("TSDNS returned no valid IP endpoint");
        return result;
    }

    /** One bounded TSDNS exchange; the query contains a hostname, never credentials. */
    public static String queryTsdns(SocketFactory factory, InetSocketAddress endpoint, String query) throws IOException {
        String host = normalizeHost(query);
        try (Socket socket = factory.createSocket()) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            socket.connect(endpoint, 1500);
            socket.setSoTimeout(1500);
            OutputStream output = socket.getOutputStream();
            output.write(host.getBytes(StandardCharsets.US_ASCII));
            output.flush();
            ByteArrayOutputStream reply = new ByteArrayOutputStream();
            InputStream input = socket.getInputStream();
            while (true) {
                long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (remaining <= 0) throw new SocketTimeoutException("TSDNS response deadline exceeded");
                socket.setSoTimeout((int) Math.max(1L, Math.min(1500L, remaining)));
                int value = input.read();
                if (value == -1 || value == '\n') break;
                if (reply.size() >= 1024) throw new IOException("TSDNS reply exceeds limit");
                reply.write(value);
            }
            return reply.toString(StandardCharsets.US_ASCII.name());
        }
    }
}
