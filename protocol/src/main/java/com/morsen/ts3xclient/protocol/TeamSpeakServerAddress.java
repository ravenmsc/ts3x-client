package com.morsen.ts3xclient.protocol;

/** Parses user input without DNS access. A port in Host takes precedence over the Port field. */
public final class TeamSpeakServerAddress {
    private final String host;
    private final int port;
    private final boolean explicitPort;

    private TeamSpeakServerAddress(String host, int port, boolean explicitPort) {
        this.host = host;
        this.port = port;
        this.explicitPort = explicitPort;
    }

    public String getHost() { return host; }
    public int getPort() { return port; }
    public boolean hasExplicitPort() { return explicitPort; }

    public static TeamSpeakServerAddress parse(String input, String portField) {
        String host = input == null ? "" : input.trim();
        if (host.isEmpty()) throw new IllegalArgumentException("Host is required");
        String embeddedPort = null;
        if (host.startsWith("[")) {
            int end = host.indexOf(']');
            if (end < 0 || host.substring(1, end).indexOf(':') < 0)
                throw new IllegalArgumentException("Invalid IPv6 address");
            String tail = host.substring(end + 1);
            if (!tail.isEmpty()) {
                if (!tail.startsWith(":")) throw new IllegalArgumentException("Invalid server address");
                embeddedPort = tail.substring(1);
            }
            host = host.substring(1, end);
        } else {
            int colon = host.indexOf(':');
            // Multiple colons are an unbracketed IPv6 host, never an implicit port separator.
            if (colon >= 0 && colon == host.lastIndexOf(':')) {
                embeddedPort = host.substring(colon + 1);
                host = host.substring(0, colon);
            }
        }
        if (host.isEmpty() || host.length() > 253 || host.chars().anyMatch(c ->
                Character.isWhitespace(c) || Character.isISOControl(c) || "/\\?#@[]".indexOf(c) >= 0))
            throw new IllegalArgumentException("Invalid server address");
        String portText = embeddedPort != null ? embeddedPort : portField == null ? "" : portField.trim();
        if (!portText.matches("[0-9]{1,5}")) throw new IllegalArgumentException("Port must be 1..65535");
        int port = Integer.parseInt(portText);
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Port must be 1..65535");
        return new TeamSpeakServerAddress(host, port, embeddedPort != null);
    }
}
