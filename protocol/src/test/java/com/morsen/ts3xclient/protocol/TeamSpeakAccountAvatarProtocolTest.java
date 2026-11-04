package com.morsen.ts3xclient.protocol;

import com.github.manevolent.ts3j.command.SingleCommand;
import com.github.manevolent.ts3j.command.parameter.CommandSingleParameter;
import com.github.manevolent.ts3j.event.ClientJoinEvent;
import com.github.manevolent.ts3j.event.ClientUpdatedEvent;
import com.github.manevolent.ts3j.protocol.ProtocolRole;
import com.github.manevolent.ts3j.protocol.packet.PacketBody2Command;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

/** Use the actual wire parser and event maps, including TS3 and TS6 UID sizes. */
public class TeamSpeakAccountAvatarProtocolTest {
    private static Map<String, String> wire(String name, Map<String, String> values) {
        SingleCommand command = new SingleCommand(name, ProtocolRole.SERVER);
        values.forEach((key, value) -> command.add(new CommandSingleParameter(key, value)));
        return new PacketBody2Command(ProtocolRole.SERVER, command.build()).parse().simplifyOne().toMap();
    }
    @Test public void emptyServerAvatarDoesNotHideAccountArtworkInEitherGeneration() {
        String url = "https://avatars.example.test/image.png?version=1&size=80";
        for (int uidLength : new int[]{20, 32}) {
            String uid = Base64.getEncoder().encodeToString(new byte[uidLength]);
            Map<String, String> properties = new LinkedHashMap<>();
            properties.put("clid", "42"); properties.put("client_unique_identifier", uid);
            properties.put("client_nickname", "User | / Имя"); properties.put("client_flag_avatar", "");
            properties.put("client_myteamspeak_avatar", "0,https://avatars.example.test/old;1," + url);
            ClientJoinEvent event = new ClientJoinEvent(wire("notifycliententerview", properties));
            String flag = TeamSpeakAvatarMetadata.mergeFlag(event.getMap(), null);
            String account = TeamSpeakAvatarMetadata.mergeAccountAvatarUrl(event.getMap(), null);
            assertEquals("", flag); assertEquals(url, account);
            assertEquals(TeamSpeakAvatarMetadata.Source.ACCOUNT, TeamSpeakAvatarMetadata.source(flag, false, account));
            assertEquals(7 + 2 * uidLength, TeamSpeakIdentity.avatarFileName(event.getUniqueClientIdentifier()).length());
            ClientUpdatedEvent away = new ClientUpdatedEvent(wire("notifyclientupdated", Map.of("clid", "42", "client_away", "1")));
            assertEquals(account, TeamSpeakAvatarMetadata.mergeAccountAvatarUrl(away.getMap(), account));
            ClientUpdatedEvent removed = new ClientUpdatedEvent(wire("notifyclientupdated", Map.of("clid", "42", "client_myteamspeak_avatar", "")));
            assertNull(TeamSpeakAvatarMetadata.mergeAccountAvatarUrl(removed.getMap(), account));
        }
    }
}
