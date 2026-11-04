package com.morsen.ts3xclient.protocol;

import java.util.Base64;

/** Wire identifiers are server-specific; the saved key pair is shared by TS3/TS6. */
public final class TeamSpeakIdentity {
    private TeamSpeakIdentity() { }

    public static String avatarFileName(String serverUid) {
        byte[] uid;
        try { uid = Base64.getDecoder().decode(serverUid.trim()); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException("Invalid TeamSpeak client UID", error); }
        if (uid.length != 20 && uid.length != 32)
            throw new IllegalArgumentException("Unsupported TeamSpeak UID length: " + uid.length);
        String alphabet = "abcdefghijklmnop";
        StringBuilder filename = new StringBuilder("avatar_");
        // TS3 uses a 20-byte UID, TS6 announces a 32-byte UID. Encode every byte;
        // truncating a TS6 identifier addresses a different file.
        for (byte b : uid) {
            int value = b & 0xff;
            filename.append(alphabet.charAt(value >>> 4)).append(alphabet.charAt(value & 15));
        }
        return filename.toString();
    }

    /** ftdeletefile takes the virtual avatar path with the original Base64 UID. */
    public static String avatarDeletePath(String serverUid) {
        avatarFileName(serverUid); // Validate the identifier without altering it.
        return "/avatar_" + serverUid.trim();
    }
}
