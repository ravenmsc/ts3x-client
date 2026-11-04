package com.morsen.ts3xclient.protocol;

import java.util.Map;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;

/** Omitted avatar properties are unknown, including in permission-filtered clientinfo replies. */
public final class TeamSpeakAvatarMetadata {
    private TeamSpeakAvatarMetadata() { }
    private static final Pattern CONTENT_HASH = Pattern.compile("[0-9a-fA-F]{32}");
    public enum Source { SERVER, ACCOUNT, NONE }

    public static String mergeFlag(Map<String, ?> properties, String previous) {
        if (!properties.containsKey("client_flag_avatar")) return previous;
        Object value = properties.get("client_flag_avatar");
        return value == null ? previous : value.toString().trim();
    }

    public static boolean isAbsent(String flag) {
        return flag != null && (flag.trim().isEmpty() || "0".equals(flag.trim()));
    }

    /** A presence bit such as "1" is not a content version and needs periodic revalidation. */
    public static boolean isContentHash(String flag) {
        return flag != null && CONTENT_HASH.matcher(flag.trim()).matches();
    }

    public static String mergeAccountAvatarUrl(Map<String, ?> properties, String previous) {
        String key = properties.containsKey("client_myteamspeak_avatar") ? "client_myteamspeak_avatar"
                : properties.containsKey("client_myts_avatar") ? "client_myts_avatar" : null;
        if (key == null || properties.get(key) == null) return previous;
        return accountAvatarUrl(properties.get(key).toString());
    }

    public static boolean hasAccountAvatarProperty(Map<String, ?> properties) {
        return properties.get("client_myteamspeak_avatar") != null || properties.get("client_myts_avatar") != null;
    }

    /** myTeamSpeak advertises semicolon-separated entries with a label and URL separated by a comma. */
    public static String accountAvatarUrl(String field) {
        if (field == null || field.length() > 16_384) return null;
        String value = field.trim();
        if (value.isEmpty() || value.equals("0")) return null;
        if (value.regionMatches(true, 0, "https://", 0, 8) || value.regionMatches(true, 0, "http://", 0, 7)) return imageUrl(value);
        String[] entries = value.split(";", -1);
        for (int i = entries.length - 1; i >= 0; i--) {
            String entry = entries[i].trim();
            if (entry.isEmpty()) continue;
            int separator = entry.indexOf(',');
            return separator < 0 ? null : imageUrl(entry.substring(separator + 1).trim());
        }
        return null;
    }

    private static String imageUrl(String value) {
        if (value.length() > 4096) return null;
        try {
            URI uri = new URI(value);
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())) && uri.getHost() != null
                    && !uri.getHost().isEmpty() && uri.getRawUserInfo() == null ? value : null;
        } catch (URISyntaxException invalid) { return null; }
    }

    /** A server avatar takes priority; an empty server flag does not remove the account avatar. */
    public static Source source(String serverFlag, boolean cachedServerImage, String accountUrl) {
        if (cachedServerImage && !isAbsent(serverFlag)) return Source.SERVER;
        return accountUrl == null || accountUrl.isEmpty() ? Source.NONE : Source.ACCOUNT;
    }
}
