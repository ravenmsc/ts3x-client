package com.morsen.ts3xclient.protocol;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

public final class TeamSpeakCredentials {
    private TeamSpeakCredentials() { }

    /**
     * clientinit expects Base64(SHA-1(UTF-8 password)) on TS3 and TS6. ts3j's
     * connect() copies this argument verbatim; its joinChannel() hashes its own
     * argument separately. Keep saved/user-facing passwords as their original text.
     * This is a protocol encoding, not an encrypted password store.
     */
    public static String serverPassword(String password) {
        if (password == null || password.isEmpty()) return "";
        try {
            return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                    .digest(password.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-1 is required by the TeamSpeak protocol", impossible);
        }
    }
}
