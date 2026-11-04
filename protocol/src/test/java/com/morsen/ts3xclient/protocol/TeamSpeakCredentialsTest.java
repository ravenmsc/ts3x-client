package com.morsen.ts3xclient.protocol;

import org.junit.Test;
import static org.junit.Assert.*;

public class TeamSpeakCredentialsTest {
    @Test public void noPasswordStaysAbsent() {
        assertEquals("", TeamSpeakCredentials.serverPassword(""));
        assertEquals("", TeamSpeakCredentials.serverPassword(null));
    }
    @Test public void usesTheTeamSpeakPasswordWireFormat() {
        assertEquals("W6ph5Mm5Pz8GgiULbPgzG37mj9g=", TeamSpeakCredentials.serverPassword("password"));
        assertNotEquals(TeamSpeakCredentials.serverPassword("password"), TeamSpeakCredentials.serverPassword(" password "));
    }
}
