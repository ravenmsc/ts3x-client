package com.morsen.ts3xclient.protocol;

import org.junit.Test;
import java.util.Base64;
import static org.junit.Assert.*;

public class TeamSpeakIdentityTest {
    @Test public void legacyUidKeepsTheEstablishedAvatarFilename() {
        assertEquals("avatar_" + "ab".repeat(20), TeamSpeakIdentity.avatarFileName(
                Base64.getEncoder().encodeToString(filled(20, (byte) 1))));
    }
    @Test public void ts6UidIsEncodedWithoutTruncation() {
        byte[] uid = filled(32, (byte) 0xff);
        assertEquals("avatar_" + "pp".repeat(32), TeamSpeakIdentity.avatarFileName(
                Base64.getEncoder().encodeToString(uid)));
        String first = TeamSpeakIdentity.avatarFileName(Base64.getEncoder().encodeToString(uid));
        uid[31] = 0;
        assertNotEquals(first, TeamSpeakIdentity.avatarFileName(Base64.getEncoder().encodeToString(uid)));
    }
    @Test public void malformedAndUnknownUidLengthsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> TeamSpeakIdentity.avatarFileName("not base64"));
        assertThrows(IllegalArgumentException.class, () -> TeamSpeakIdentity.avatarFileName(""));
        assertThrows(IllegalArgumentException.class, () -> TeamSpeakIdentity.avatarFileName(
                Base64.getEncoder().encodeToString(new byte[21])));
    }
    @Test public void deletionUsesTheOriginalWireUidRatherThanTheDiskFilename() {
        String uid = Base64.getEncoder().encodeToString(filled(32, (byte) 0xff));
        assertEquals("/avatar_" + uid, TeamSpeakIdentity.avatarDeletePath(uid));
        assertNotEquals("/" + TeamSpeakIdentity.avatarFileName(uid), TeamSpeakIdentity.avatarDeletePath(uid));
    }
    private static byte[] filled(int size, byte value) {
        byte[] bytes = new byte[size]; java.util.Arrays.fill(bytes, value); return bytes;
    }
}
