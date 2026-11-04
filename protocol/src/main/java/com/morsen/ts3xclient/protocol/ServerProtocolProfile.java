package com.morsen.ts3xclient.protocol;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Immutable, session-local facts announced by the server, never by other clients. */
public final class ServerProtocolProfile {
    public enum Generation { UNKNOWN, TS3, TS6 }

    private static final Pattern VERSION = Pattern.compile(
            "^(?:(?:TeamSpeak|TS)\\s*(?:Server\\s*)?)?(\\d+)\\.\\d+(?:\\.\\d+)?(?:[-+\\s\\[].*)?$",
            Pattern.CASE_INSENSITIVE);

    private final String version;
    private final String platform;
    private final Generation generation;
    private final long defaultChannelGroupId;
    private final long defaultServerGroupId;
    private final Set<String> extensions;

    private ServerProtocolProfile(String version, String platform, long channelGroup,
                                  long serverGroup, Set<String> extensions) {
        this.version = version;
        this.platform = platform;
        Matcher matcher = VERSION.matcher(version);
        String major = matcher.matches() ? matcher.group(1) : "";
        this.generation = "3".equals(major) ? Generation.TS3
                : "6".equals(major) ? Generation.TS6 : Generation.UNKNOWN;
        this.defaultChannelGroupId = channelGroup;
        this.defaultServerGroupId = serverGroup;
        this.extensions = Collections.unmodifiableSet(new LinkedHashSet<>(extensions));
    }

    public static ServerProtocolProfile unknown() {
        return new ServerProtocolProfile("", "", 0, 0, Collections.emptySet());
    }

    /** Only virtualserver_* keys are accepted here; client_version is never evidence. */
    public ServerProtocolProfile mergeServerProperties(Map<String, ?> properties) {
        String rawExtensions = string(properties, "virtualserver_capability_extensions");
        Set<String> nextExtensions = extensions;
        if (rawExtensions != null) {
            nextExtensions = new LinkedHashSet<>();
            for (String item : rawExtensions.split(",")) {
                String extension = item.trim().toLowerCase(Locale.ROOT);
                if (!extension.isEmpty()) nextExtensions.add(extension);
            }
        }
        return new ServerProtocolProfile(
                nonEmpty(string(properties, "virtualserver_version"), version),
                nonEmpty(string(properties, "virtualserver_platform"), platform),
                positiveId(properties.get("virtualserver_default_channel_group"), defaultChannelGroupId),
                positiveId(properties.get("virtualserver_default_server_group"), defaultServerGroupId),
                nextExtensions);
    }

    /** The explicit full-client `version` response is a fallback when initserver omits it. */
    public ServerProtocolProfile mergeVersionResponse(Map<String, ?> response) {
        return new ServerProtocolProfile(nonEmpty(string(response, "version"), version),
                nonEmpty(string(response, "platform"), platform), defaultChannelGroupId,
                defaultServerGroupId, extensions);
    }

    private static String string(Map<String, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? null : value.toString().trim();
    }

    private static String nonEmpty(String value, String fallback) {
        return value == null || value.isEmpty() || "null".equalsIgnoreCase(value) ? fallback : value;
    }

    private static long positiveId(Object value, long fallback) {
        try {
            long id = Long.parseLong(String.valueOf(value));
            return id > 0 ? id : fallback;
        } catch (NumberFormatException ignored) { return fallback; }
    }

    public String getVersion() { return version; }
    public String getPlatform() { return platform; }
    public Generation getGeneration() { return generation; }
    public long getDefaultChannelGroupId() { return defaultChannelGroupId; }
    public long getDefaultServerGroupId() { return defaultServerGroupId; }
    public Set<String> getExtensions() { return extensions; }
    public boolean hasS3ChannelStorage() { return extensions.contains("s3"); }

    @Override public String toString() {
        return generation + " version=" + (version.isEmpty() ? "unknown" : version)
                + " platform=" + platform + " extensions=" + extensions
                + " defaultChannelGroup=" + defaultChannelGroupId
                + " defaultServerGroup=" + defaultServerGroupId;
    }
}
