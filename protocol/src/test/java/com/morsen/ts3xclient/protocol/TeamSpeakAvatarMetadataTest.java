package com.morsen.ts3xclient.protocol;

import org.junit.Test;
import java.util.Map;
import java.util.HashMap;
import static org.junit.Assert.*;

public class TeamSpeakAvatarMetadataTest {
    private static final String ACCOUNT_URL = "https://avatars.example.test/available.png?v=2";

    @Test public void accountAvatarRemainsAvailableWhenTheServerAvatarIsEmptyOrRemoved() {
        String account = TeamSpeakAvatarMetadata.mergeAccountAvatarUrl(Map.of(
                "client_flag_avatar", "", "client_myteamspeak_avatar", "0," + ACCOUNT_URL), null);
        assertEquals(ACCOUNT_URL, account);
        assertEquals(TeamSpeakAvatarMetadata.Source.ACCOUNT, TeamSpeakAvatarMetadata.source("", true, account));
        assertEquals(TeamSpeakAvatarMetadata.Source.ACCOUNT, TeamSpeakAvatarMetadata.source("0", true, account));
        assertEquals(TeamSpeakAvatarMetadata.Source.ACCOUNT, TeamSpeakAvatarMetadata.source("hash", false, account));
        assertEquals(TeamSpeakAvatarMetadata.Source.SERVER, TeamSpeakAvatarMetadata.source("hash", true, account));
        assertEquals(TeamSpeakAvatarMetadata.Source.NONE, TeamSpeakAvatarMetadata.source("", true, null));
    }

    @Test public void readsTheLastAdvertisedAccountAvatarAndPreservesUrlQueryCharacters() {
        assertEquals(ACCOUNT_URL, TeamSpeakAvatarMetadata.accountAvatarUrl(
                "0,https://avatars.example.test/old.png;1," + ACCOUNT_URL + ";"));
        assertEquals(ACCOUNT_URL, TeamSpeakAvatarMetadata.accountAvatarUrl(ACCOUNT_URL));
        assertEquals(ACCOUNT_URL + ",x", TeamSpeakAvatarMetadata.accountAvatarUrl("2," + ACCOUNT_URL + ",x"));
        assertEquals("http://avatars.example.test/a.png", TeamSpeakAvatarMetadata.accountAvatarUrl("0,http://avatars.example.test/a.png"));
    }

    @Test public void partialClientUpdatesPreserveAccountArtworkWhileExplicitUpdatesReplaceOrRemoveIt() {
        assertEquals(ACCOUNT_URL, TeamSpeakAvatarMetadata.mergeAccountAvatarUrl(Map.of("client_away", "1"), ACCOUNT_URL));
        assertEquals(ACCOUNT_URL, TeamSpeakAvatarMetadata.mergeAccountAvatarUrl(Map.of("client_flag_avatar", ""), ACCOUNT_URL));
        assertNull(TeamSpeakAvatarMetadata.mergeAccountAvatarUrl(Map.of("client_myteamspeak_avatar", ""), ACCOUNT_URL));
        assertTrue(TeamSpeakAvatarMetadata.hasAccountAvatarProperty(Map.of("client_myteamspeak_avatar", "")));
        assertFalse(TeamSpeakAvatarMetadata.hasAccountAvatarProperty(Map.of("client_nickname", "User")));
        assertEquals(ACCOUNT_URL, TeamSpeakAvatarMetadata.mergeAccountAvatarUrl(Map.of("client_myts_avatar", "0," + ACCOUNT_URL), null));
    }

    @Test public void invalidAccountSourcesCannotBecomeImageRequests() {
        for (String value : new String[]{"", "0", "not-a-url", "0,javascript:alert(1)",
                "0,file:///data/private.png", "0,data:image/png;base64,AA==", "0,https://user:secret@example.test/a", "0,https:///a"})
            assertNull(value, TeamSpeakAvatarMetadata.accountAvatarUrl(value));
        assertNull(TeamSpeakAvatarMetadata.accountAvatarUrl("a".repeat(16_385)));
    }

    @Test public void nullValuesAreUnknownAndBooleanPresenceIsNotAReusableContentHash() {
        Map<String, Object> values = new HashMap<>(); values.put("client_flag_avatar", null);
        assertEquals("old hash", TeamSpeakAvatarMetadata.mergeFlag(values, "old hash"));
        assertFalse(TeamSpeakAvatarMetadata.isContentHash("1"));
        assertFalse(TeamSpeakAvatarMetadata.isContentHash("0"));
        assertFalse(TeamSpeakAvatarMetadata.isContentHash(null));
        assertTrue(TeamSpeakAvatarMetadata.isContentHash("0123456789abcdef0123456789ABCDEF"));
    }
    @Test public void filteredClientInfoMustNotTurnUnknownAvatarIntoAnAbsence() {
        String presence = TeamSpeakAvatarMetadata.mergeFlag(Map.of("client_nickname", "User"), null);
        String details = TeamSpeakAvatarMetadata.mergeFlag(Map.of("client_database_id", "42"), presence);
        assertNull(details);
        assertFalse("The UID-based download must still be attempted", TeamSpeakAvatarMetadata.isAbsent(details));
    }

    @Test public void missingPropertiesPreserveAnAlreadyAdvertisedAvatar() {
        String hash = "0123456789abcdef0123456789abcdef";
        assertEquals(hash, TeamSpeakAvatarMetadata.mergeFlag(Map.of("client_away", "1"), hash));
        assertEquals(hash, TeamSpeakAvatarMetadata.mergeFlag(Map.of("client_nickname", "User"), hash));
    }

    @Test public void explicitRemovalStopsDownloadsAndInvalidatesTheOldAvatar() {
        for (String removal : new String[]{"", "0", " 0 "}) {
            String flag = TeamSpeakAvatarMetadata.mergeFlag(Map.of("client_flag_avatar", removal), "old hash");
            assertTrue(TeamSpeakAvatarMetadata.isAbsent(flag));
        }
    }

    @Test public void presenceFlagsAndNewHashesRemainDownloadable() {
        for (String value : new String[]{"1", "0123456789abcdef0123456789abcdef"}) {
            String flag = TeamSpeakAvatarMetadata.mergeFlag(Map.of("client_flag_avatar", value), "");
            assertEquals(value, flag);
            assertFalse(TeamSpeakAvatarMetadata.isAbsent(flag));
        }
    }
}
