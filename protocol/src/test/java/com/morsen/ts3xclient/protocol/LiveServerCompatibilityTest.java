package com.morsen.ts3xclient.protocol;

import com.github.manevolent.ts3j.audio.Microphone;
import com.github.manevolent.ts3j.command.*;
import com.github.manevolent.ts3j.command.parameter.CommandSingleParameter;
import com.github.manevolent.ts3j.enums.CodecType;
import com.github.manevolent.ts3j.event.*;
import com.github.manevolent.ts3j.identity.LocalIdentity;
import com.github.manevolent.ts3j.protocol.ProtocolRole;
import com.github.manevolent.ts3j.protocol.socket.client.AbstractTeamspeakClientSocket;
import com.github.manevolent.ts3j.protocol.socket.client.LocalTeamspeakClientSocket;
import org.junit.Assume;
import org.junit.Test;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

/** Opt-in loopback tests against actual, isolated server binaries; no production endpoints. */
public class LiveServerCompatibilityTest {
    private static void await(String description, BooleanSupplier ready) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!ready.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(20);
        assertTrue(description, ready.getAsBoolean());
    }

    @Test public void existingClientOperationsWorkAcrossServerGenerations() throws Exception {
        String configuredPort = System.getProperty("ts3x.test.port", "");
        Assume.assumeFalse("Requires an explicitly configured local test server", configuredPort.isEmpty());
        int port = Integer.parseInt(configuredPort);
        String expected = System.getProperty("ts3x.test.generation");
        LocalIdentity identity = LocalIdentity.generateNew(8);
        String originalWireUid;
        try (Probe admin = new Probe(port, "TS3X integration admin", identity,
                System.getProperty("ts3x.test.token", ""));
             Probe peer = new Probe(port, "TS3X integration peer", LocalIdentity.generateNew(8), "")) {
            assertEquals(ServerProtocolProfile.Generation.valueOf(expected), admin.profile.get().getGeneration());
            await("Own UID is announced", () -> !admin.ownUid().isEmpty());
            originalWireUid = admin.ownUid();
            assertEquals("TS6".equals(expected) ? 32 : 20, Base64.getDecoder().decode(admin.ownUid()).length);
            assertEquals(admin.profile.get().getGeneration(), ServerProtocolProfile.unknown()
                    .mergeVersionResponse(admin.exec("version")).getGeneration());
            assertTrue(admin.socket.listChannels().iterator().hasNext());
            assertTrue(admin.socket.listClients().iterator().hasNext());
            admin.exec("servergetvariables");
            await("Server variables notification", () -> admin.serverVariables.get());
            pass("handshake, server version, UID length, channel/client lists, server info");

            String channelPassword = "Канал | / TS3X";
            admin.exec("channelcreate", "channel_name", "TS3X integration channel",
                    "channel_flag_permanent", "1", "channel_codec", "4",
                    "channel_password", TeamSpeakCredentials.serverPassword(channelPassword),
                    "channel_description", "Описание / \\ | 😀 [b]TS3X[/b]");
            await("New channel notification", () -> admin.createdChannel.get() > 0);
            int channel = admin.createdChannel.get();
            admin.socket.joinChannel(channel, channelPassword);
            peer.socket.joinChannel(channel, channelPassword);
            await("Peer has entered channel", () -> peer.channelId.get() == channel);
            admin.exec("channelgetdescription", "cid", Integer.toString(channel));
            await("Channel description notification", () -> admin.descriptionSeen.get());
            pass("password-protected channel creation/joining, descriptions");

            String text = "Тест / \\ | 😀 [b]TS3X[/b]";
            admin.socket.sendPrivateMessage(peer.socket.getClientId(), "PM " + text);
            admin.socket.sendChannelMessage(channel, "CH " + text);
            await("Private message escaping", () -> peer.messages.contains("PM " + text));
            await("Channel message escaping", () -> peer.messages.contains("CH " + text));
            admin.exec("clientpoke", "clid", Integer.toString(peer.socket.getClientId()), "msg", text);
            await("Incoming poke", () -> peer.pokes.contains(text));
            pass("private/channel chat, UTF-8/escaping, incoming poke");

            admin.exec("clientupdate", "client_away", "1", "client_away_message", "AFK " + text,
                    "client_input_muted", "1");
            await("Remote AFK/mute update", () -> "1".equals(peer.clientValue(admin.socket.getClientId(), "client_away"))
                    && "1".equals(peer.clientValue(admin.socket.getClientId(), "client_input_muted")));
            admin.exec("clientupdate", "client_away", "0", "client_away_message", "", "client_input_muted", "0");
            AtomicInteger voicePackets = new AtomicInteger();
            peer.socket.setVoiceHandler(packet -> {
                if (packet.getClientId() == admin.socket.getClientId() && packet.getCodecData().length > 0)
                    voicePackets.incrementAndGet();
            });
            for (CodecType codec : new CodecType[]{CodecType.OPUS_VOICE, CodecType.OPUS_MUSIC}) {
                AtomicInteger provided = new AtomicInteger();
                int before = voicePackets.get();
                admin.socket.setMicrophone(new Microphone() {
                    public boolean isReady() { return provided.get() < 8; }
                    public CodecType getCodec() { return codec; }
                    public byte[] provide() { provided.incrementAndGet(); return new byte[]{(byte) 0xf8, (byte) 0xff, (byte) 0xfe}; }
                });
                await("Opus packet forwarding: " + codec, () -> voicePackets.get() > before);
                admin.socket.setMicrophone(null);
            }
            admin.exec("getconnectioninfo", "clid", Integer.toString(peer.socket.getClientId()));
            await("Connection statistics notification", () -> admin.connectionInfoSeen.get());
            pass("AFK/mute, Opus voice transport, connection statistics");

            byte[] png = Base64.getDecoder().decode(
                    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a6i8AAAAASUVORK5CYII=");
            String avatar = "/" + TeamSpeakIdentity.avatarFileName(admin.ownUid());
            admin.files.uploadSystemFile(avatar, png);
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(png));
            admin.exec("clientupdate", "client_flag_avatar", hash);
            long missingAvatarStarted = System.nanoTime();
            assertThrows(java.io.IOException.class, () -> peer.files.downloadSystemFile(
                    "/" + TeamSpeakIdentity.avatarFileName(peer.ownUid()), 512 * 1024));
            assertTrue("A missing avatar must fail before the notification timeout",
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - missingAvatarStarted) < 2000);
            assertArrayEquals(png, peer.files.downloadSystemFile(avatar, 512 * 1024));
            assertEquals(hash, admin.exec("clientinfo", "clid", Integer.toString(admin.socket.getClientId()))
                    .get("client_flag_avatar"));
            String icon = "/icon_4045620587";
            admin.files.uploadSystemFile(icon, png);
            assertArrayEquals(png, peer.files.downloadSystemFile(icon, 512 * 1024));
            admin.exec("serveredit", "virtualserver_icon_id", "4045620587");
            admin.exec("ftdeletefile", "cid", "0", "cpw", "", "name", TeamSpeakIdentity.avatarDeletePath(admin.ownUid()));
            assertTrue(Objects.toString(admin.exec("clientinfo", "clid", Integer.toString(admin.socket.getClientId()))
                    .get("client_flag_avatar"), "").isEmpty());
            String peerAvatar = "/" + TeamSpeakIdentity.avatarFileName(peer.ownUid());
            peer.files.uploadSystemFile(peerAvatar, png);
            peer.exec("clientupdate", "client_flag_avatar", hash);
            admin.exec("ftdeletefile", "cid", "0", "cpw", "", "name", TeamSpeakIdentity.avatarDeletePath(peer.ownUid()));
            assertTrue(Objects.toString(admin.exec("clientinfo", "clid", Integer.toString(peer.socket.getClientId()))
                    .get("client_flag_avatar"), "").isEmpty());
            pass("avatar upload/download/flag/deletion, unsigned role/server icon IDs");

            admin.exec("servergrouplist");
            admin.exec("channelgrouplist");
            await("Server/channel group lists", () -> admin.serverGroups.get() && admin.channelGroups.get());
            String dbid = admin.exec("clientinfo", "clid", Integer.toString(peer.socket.getClientId()))
                    .get("client_database_id");
            assertNotNull("Permanent DBID", dbid);
            assertEquals(peer.ownUid(), admin.lookup.clientUid(peer.socket.getClientId()));
            assertEquals(Long.parseLong(dbid), admin.lookup.databaseId(peer.ownUid()));
            String serverGroup = System.getProperty("ts3x.test.servergroup");
            String channelGroup = System.getProperty("ts3x.test.channelgroup");
            assertNotNull("Test server group configured", serverGroup);
            assertNotNull("Test channel group configured", channelGroup);
            admin.exec("servergroupaddclient", "sgid", serverGroup, "cldbid", dbid);
            await("Server group delta", () -> admin.groupEvents.contains("server-add"));
            admin.exec("servergroupdelclient", "sgid", serverGroup, "cldbid", dbid);
            await("Server group removal delta", () -> admin.groupEvents.contains("server-delete"));
            admin.exec("setclientchannelgroup", "cgid", channelGroup, "cid", Integer.toString(channel), "cldbid", dbid);
            await("Channel group delta", () -> admin.groupEvents.contains("channel"));
            assertTrue(admin.profile.get().getDefaultChannelGroupId() > 0);
            admin.exec("setclientchannelgroup", "cgid", Long.toString(admin.profile.get().getDefaultChannelGroupId()),
                    "cid", Integer.toString(channel), "cldbid", dbid);
            pass("server/channel group lists, DBID, assignment/removal, announced default group");

            admin.exec("clientkick", "clid", Integer.toString(peer.socket.getClientId()), "reasonid", "5", "reasonmsg", text);
            await("Kick notification", () -> peer.disconnected.get());
            try (Probe banned = new Probe(port, "TS3X ban test", LocalIdentity.generateNew(8), "")) {
                admin.exec("banclient", "clid", Integer.toString(banned.socket.getClientId()), "time", "1", "banreason", text);
                await("Ban notification", () -> banned.disconnected.get());
            }
            admin.socket.disconnect("integration graceful disconnect");
            await("Graceful disconnect", () -> admin.disconnected.get());
            pass("kick, timed ban, graceful disconnect");
        }
        try (Probe reconnected = new Probe(port, "TS3X reconnect", identity, "")) {
            await("Reconnected UID", () -> !reconnected.ownUid().isEmpty());
            assertEquals(originalWireUid, reconnected.ownUid());
            assertEquals(ServerProtocolProfile.Generation.valueOf(expected), reconnected.profile.get().getGeneration());
            pass("reconnect with the same identity and a new session");
        }
    }

    private static void pass(String features) { System.out.println("PASS " + features); }

    private static final class Probe implements AutoCloseable {
        final LocalTeamspeakClientSocket socket = new LocalTeamspeakClientSocket();
        final AtomicReference<ServerProtocolProfile> profile = new AtomicReference<>(ServerProtocolProfile.unknown());
        final TeamSpeakFileTransfers files;
        final TeamSpeakClientLookup lookup;
        final Map<Integer, Map<String, String>> clients = new ConcurrentHashMap<>();
        final Set<String> messages = ConcurrentHashMap.newKeySet(), pokes = ConcurrentHashMap.newKeySet();
        final Set<String> groupEvents = ConcurrentHashMap.newKeySet();
        final AtomicInteger createdChannel = new AtomicInteger(), channelId = new AtomicInteger();
        final AtomicBoolean disconnected = new AtomicBoolean(), serverVariables = new AtomicBoolean();
        final AtomicBoolean connectionInfoSeen = new AtomicBoolean(), descriptionSeen = new AtomicBoolean();
        final AtomicBoolean serverGroups = new AtomicBoolean(), channelGroups = new AtomicBoolean();

        @SuppressWarnings("unchecked")
        Probe(int port, String nickname, LocalIdentity identity, String token) throws Exception {
            files = new TeamSpeakFileTransfers(this::execMap, "127.0.0.1");
            lookup = new TeamSpeakClientLookup(this::execMap);
            Field field = socket.getClass().getDeclaredField("namedProcessors"); field.setAccessible(true);
            Map<String, CommandProcessor> processors = (Map<String, CommandProcessor>) field.get(socket);
            CommandProcessor original = processors.get("initserver");
            processors.put("initserver", new CommandProcessor() {
                @Override public void process(AbstractTeamspeakClientSocket client, SingleCommand command)
                        throws CommandProcessException {
                    profile.updateAndGet(old -> old.mergeServerProperties(command.toMap()));
                    original.process(client, command);
                }
            });
            socket.addListener(new TS3Listener() {
                public void onClientJoin(ClientJoinEvent event) { mergeClient(event.getMap()); }
                public void onClientChanged(ClientUpdatedEvent event) { mergeClient(event.getMap()); }
                public void onClientMoved(ClientMovedEvent event) { mergeClient(event.getMap()); }
                public void onTextMessage(TextMessageEvent event) { messages.add(event.getMap().get("msg")); }
                public void onClientPoke(ClientPokeEvent event) { pokes.add(event.getMap().get("msg")); }
                public void onChannelCreate(ChannelCreateEvent event) {
                    createdChannel.set(Integer.parseInt(event.getMap().get("cid")));
                }
                public void onChannelDescriptionChanged(ChannelDescriptionEditedEvent event) { descriptionSeen.set(true); }
                public void onServerGroupList(ServerGroupListEvent event) { serverGroups.set(true); }
                public void onChannelGroupList(ChannelGroupListEvent event) { channelGroups.set(true); }
                public void onServerGroupClientAdded(ServerGroupClientAddedEvent event) { groupEvents.add("server-add"); }
                public void onServerGroupClientDeleted(ServerGroupClientDeletedEvent event) { groupEvents.add("server-delete"); }
                public void onClientChannelGroupChanged(ClientChannelGroupChangedEvent event) { groupEvents.add("channel"); }
                public void onDisconnected(DisconnectedEvent event) { disconnected.set(true); }
                public void onUnknownEvent(UnknownTeamspeakEvent event) {
                    files.onNotification(event.getCommand(), event.getMap());
                    lookup.onNotification(event.getCommand(), event.getMap());
                    if (event.getCommand().equals("notifyserverupdated")) serverVariables.set(true);
                    if (event.getCommand().equals("notifyconnectioninfo")) connectionInfoSeen.set(true);
                }
            });
            socket.setIdentity(identity); socket.setNickname(nickname);
            if (!token.isEmpty()) socket.setOption("client.default_token", token);
            try {
                socket.connect(new InetSocketAddress("127.0.0.1", port),
                        TeamSpeakCredentials.serverPassword(System.getProperty("ts3x.test.password", "")), 10000L);
                socket.subscribeAll();
            }
            catch (Exception error) { files.close(); lookup.close(); socket.close(); throw error; }
        }

        private void mergeClient(Map<String, String> values) {
            String rawId = values.get("clid"); if (rawId == null) return;
            int id = Integer.parseInt(rawId);
            clients.compute(id, (key, previous) -> {
                Map<String, String> next = new HashMap<>(previous == null ? Collections.emptyMap() : previous);
                next.putAll(values); return next;
            });
            if (id == socket.getClientId()) {
                String channel = values.containsKey("ctid") ? values.get("ctid") : values.get("cid");
                if (channel != null) channelId.set(Integer.parseInt(channel));
            }
        }

        String ownUid() { return clientValue(socket.getClientId(), "client_unique_identifier"); }
        String clientValue(int id, String key) {
            String value = clients.getOrDefault(id, Collections.emptyMap()).get(key);
            return value == null ? "" : value;
        }

        Map<String, String> exec(String name, String... params) throws Exception {
            Map<String, String> arguments = new LinkedHashMap<>();
            for (int i = 0; i < params.length; i += 2) arguments.put(params[i], params[i + 1]);
            return execMap(name, arguments);
        }

        Map<String, String> execMap(String name, Map<String, String> params) throws Exception {
            SingleCommand command = new SingleCommand(name, ProtocolRole.CLIENT);
            params.forEach((key, value) -> command.add(new CommandSingleParameter(key, value)));
            Map<String, String> result = new LinkedHashMap<>();
            try {
                for (SingleCommand item : socket.executeCommand(command).get(5000)) result.putAll(item.toMap());
            } catch (CommandException error) {
                throw new CommandException(name + " failed (" + error.getErrorId() + "): " + error.getMessage(), error.getErrorId());
            }
            return result;
        }

        @Override public void close() throws Exception {
            files.close(); lookup.close(); socket.setMicrophone(null);
            try {
                if (!disconnected.get()) {
                    socket.disconnect("test finished");
                    await("Disconnect event before closing event executor", () -> disconnected.get());
                }
            }
            finally { socket.close(); }
        }
    }
}
