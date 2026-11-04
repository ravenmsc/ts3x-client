package com.morsen.ts3xclient.protocol;

import java.security.MessageDigest;

/** Own-avatar upload names contain the content MD5, not the UID used for downloads. */
public final class TeamSpeakAvatarPublication {
    private TeamSpeakAvatarPublication() { }

    @FunctionalInterface public interface FlagWriter {
        void update(String hash) throws Exception;
    }

    public static String publish(TeamSpeakFileTransfers transfers, byte[] image, FlagWriter flagWriter)
            throws Exception {
        if (image == null || image.length == 0 || image.length > 512 * 1024)
            throw new IllegalArgumentException("Invalid avatar size");
        // Hash the bytes actually sent. A saved preference can refer to an older image.
        byte[] digest = MessageDigest.getInstance("MD5").digest(image);
        char[] hex = "0123456789abcdef".toCharArray();
        StringBuilder text = new StringBuilder(32);
        for (byte value : digest) {
            text.append(hex[(value & 0xff) >>> 4]).append(hex[value & 15]);
        }
        String hash = text.toString();
        // The server associates this upload with the authenticated client's UID.
        // /avatar_<encoded UID> is a download name; strict servers reject it with 1540.
        transfers.uploadSystemFile("/avatar_" + hash, image);
        flagWriter.update(hash);
        return hash;
    }
}
