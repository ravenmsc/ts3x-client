package com.morsen.ts3xclient.protocol;

import org.junit.Test;
import java.util.Map;
import static org.junit.Assert.*;

public class ServerProtocolProfileTest {
    @Test public void identifiesServerWithoutUsingTheConnectingClientsVersion() {
        ServerProtocolProfile profile = ServerProtocolProfile.unknown().mergeServerProperties(Map.of(
                "virtualserver_version", "6.0.0-beta13.1 [Build: 1790080330]",
                "virtualserver_platform", "Linux", "client_version", "3.?.? [Build: 5680278000]"));
        assertEquals(ServerProtocolProfile.Generation.TS6, profile.getGeneration());
        assertEquals("Linux", profile.getPlatform());
        assertEquals(ServerProtocolProfile.Generation.TS3, ServerProtocolProfile.unknown()
                .mergeServerProperties(Map.of("virtualserver_version", "3.13.7 [Build: 1655727713]"))
                .getGeneration());
    }

    @Test public void clientVersionsAndCapabilitiesDoNotImpersonateAServerVersion() {
        ServerProtocolProfile profile = ServerProtocolProfile.unknown().mergeServerProperties(Map.of(
                "client_version", "6.0.0", "version", "6.0.0", "virtualserver_capability_extensions", "s3"));
        assertEquals(ServerProtocolProfile.Generation.UNKNOWN, profile.getGeneration());
        assertTrue(profile.hasS3ChannelStorage());
    }

    @Test public void incrementalUpdatesRetainServerFactsAndUseAnnouncedGroupIds() {
        ServerProtocolProfile profile = ServerProtocolProfile.unknown().mergeServerProperties(Map.of(
                "virtualserver_version", "6.0.0-beta13.1",
                "virtualserver_default_channel_group", "4294967296",
                "virtualserver_default_server_group", "314",
                "virtualserver_capability_extensions", "FILETIME,STREAM_P2P,s3,S3"));
        profile = profile.mergeServerProperties(Map.of("virtualserver_clientsonline", "2"));
        assertEquals(ServerProtocolProfile.Generation.TS6, profile.getGeneration());
        assertEquals(4294967296L, profile.getDefaultChannelGroupId());
        assertEquals(314, profile.getDefaultServerGroupId());
        assertEquals(3, profile.getExtensions().size());
        assertTrue(profile.hasS3ChannelStorage());
        assertTrue(profile.mergeServerProperties(Map.of("virtualserver_capability_extensions", ""))
                .getExtensions().isEmpty());
    }

    @Test public void unrecognizedServersStayUnrecognizedAndKeepTheirVersionText() {
        for (String version : new String[]{"", "custom", "7.0.0", "build 6.0.0", "myserver 3.13.7"}) {
            ServerProtocolProfile profile = ServerProtocolProfile.unknown()
                    .mergeVersionResponse(Map.of("version", version));
            assertEquals(ServerProtocolProfile.Generation.UNKNOWN, profile.getGeneration());
            assertEquals(version, profile.getVersion());
        }
    }

    @Test public void explicitVersionResponseIsAValidFallbackButNotASessionCache() {
        ServerProtocolProfile profile = ServerProtocolProfile.unknown()
                .mergeVersionResponse(Map.of("version", "6.0.0-beta13.1", "platform", "Linux"));
        assertEquals(ServerProtocolProfile.Generation.TS6, profile.getGeneration());
        assertEquals(ServerProtocolProfile.Generation.UNKNOWN, ServerProtocolProfile.unknown().getGeneration());
        assertEquals(0, ServerProtocolProfile.unknown().getDefaultChannelGroupId());
        assertTrue(ServerProtocolProfile.unknown().getExtensions().isEmpty());
        assertEquals(profile.getVersion(), profile.mergeServerProperties(Map.of(
                "virtualserver_version", "null", "virtualserver_default_channel_group", "-1")).getVersion());
    }
}
