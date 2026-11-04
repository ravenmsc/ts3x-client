package com.morsen.ts3xclient.protocol;

import org.junit.Test;
import static org.junit.Assert.*;

public class TeamSpeakServerAddressTest {
    @Test public void embeddedPortOverridesEvenAnEmptyOrInvalidPortField() {
        for (String field : new String[]{"9987", "9999", "", "invalid", "0"}) {
            TeamSpeakServerAddress address = TeamSpeakServerAddress.parse(" copper.cleanvoice.ru:9738 ", field);
            assertEquals("copper.cleanvoice.ru", address.getHost());
            assertEquals(9738, address.getPort());
            assertTrue(address.hasExplicitPort());
        }
    }
    @Test public void plainHostUsesTheSeparatePortField() {
        TeamSpeakServerAddress address = TeamSpeakServerAddress.parse("example.org", " 9994 ");
        assertEquals(9994, address.getPort());
        assertFalse(address.hasExplicitPort());
    }
    @Test public void supportsIpv4AndBracketedIpv6WithPorts() {
        assertEquals("5.180.217.221", TeamSpeakServerAddress.parse("5.180.217.221:9994", "9987").getHost());
        TeamSpeakServerAddress address = TeamSpeakServerAddress.parse("[2001:db8::1]:9738", "9987");
        assertEquals("2001:db8::1", address.getHost());
        assertEquals(9738, address.getPort());
    }
    @Test public void neverMistakesTheLastIpv6SegmentForAPort() {
        for (String host : new String[]{"2001:db8::9738", "::1", "[2001:db8::1]"}) {
            TeamSpeakServerAddress address = TeamSpeakServerAddress.parse(host, "9994");
            assertEquals(9994, address.getPort());
            assertFalse(address.hasExplicitPort());
        }
    }
    @Test public void rejectsMalformedAddressesAndPortsInsteadOfFallingBack() {
        for (String host : new String[]{"", ":9738", "host:", "host:0", "host:65536", "host:-1",
                "host:abc", "host:9999999999999", "[::1", "[::1]junk", "[::1]:", "[host]:42",
                "host name", "host\nname", "https://host:9738", "user@host:9738", "host/path"}) {
            assertThrows(host, IllegalArgumentException.class, () -> TeamSpeakServerAddress.parse(host, "9987"));
        }
        assertThrows(IllegalArgumentException.class, () -> TeamSpeakServerAddress.parse("host", "0"));
    }
}
