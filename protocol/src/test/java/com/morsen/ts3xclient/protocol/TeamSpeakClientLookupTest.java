package com.morsen.ts3xclient.protocol;

import org.junit.Test;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class TeamSpeakClientLookupTest {
    @Test public void uidLookupHandlesAnEarlyNotificationAndIgnoresOtherClientIds() throws Exception {
        String uid = Base64.getEncoder().encodeToString(new byte[32]);
        AtomicReference<TeamSpeakClientLookup> ref = new AtomicReference<>();
        try (TeamSpeakClientLookup lookup = new TeamSpeakClientLookup((command, args) -> {
            assertEquals("clientgetuidfromclid", command);
            ref.get().onNotification("notifyclientuidfromclid", Map.of("clid", "99", "cluid", "wrong"));
            ref.get().onNotification("notifyclientuidfromclid", Map.of("clid", args.get("clid"), "cluid", uid));
            return Map.of();
        })) {
            ref.set(lookup);
            assertEquals(uid, lookup.clientUid(7));
        }
    }

    @Test public void dbidLookupMatchesTheFullUidAndPreserves64BitIds() throws Exception {
        String uid = Base64.getEncoder().encodeToString(new byte[20]);
        AtomicReference<TeamSpeakClientLookup> ref = new AtomicReference<>();
        try (TeamSpeakClientLookup lookup = new TeamSpeakClientLookup((command, args) -> {
            assertEquals("clientgetdbidfromuid", command);
            ref.get().onNotification("notifyclientdbidfromuid", Map.of("cluid", "other", "cldbid", "1"));
            ref.get().onNotification("notifyclientdbidfromuid", Map.of("cluid", uid, "cldbid", "4294967296"));
            return Map.of();
        })) {
            ref.set(lookup);
            assertEquals(4294967296L, lookup.databaseId(uid));
        }
    }
}
