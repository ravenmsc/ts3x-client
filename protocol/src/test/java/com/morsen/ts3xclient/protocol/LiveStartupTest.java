package com.morsen.ts3xclient.protocol;

import com.github.manevolent.ts3j.command.*;
import com.github.manevolent.ts3j.identity.LocalIdentity;
import com.github.manevolent.ts3j.event.TS3Listener;
import com.github.manevolent.ts3j.event.DisconnectedEvent;
import com.github.manevolent.ts3j.protocol.socket.client.*;
import org.junit.*;
import java.net.InetSocketAddress;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

/** Opt-in latency regression on a real local server, with a delayed subscription completion. */
public class LiveStartupTest {
    @Test public void metadataProbeReadsNameWithoutSubscriptionOrAvatarUpload() throws Exception {
        String configured = System.getProperty("ts3x.test.port", "");
        Assume.assumeFalse("Requires an explicitly configured local server", configured.isEmpty());
        AtomicReference<String> name = new AtomicReference<>();
        LocalTeamspeakClientSocket socket = new LocalTeamspeakClientSocket() {
            @Override public void process(AbstractTeamspeakClientSocket client, SingleCommand command)
                    throws CommandProcessException {
                if (command.getName().equals("initserver") && command.has("virtualserver_name"))
                    name.set(command.get("virtualserver_name").getValue());
                super.process(client, command);
            }
        };
        TeamSpeakSessionTasks tasks = new TeamSpeakSessionTasks();
        CountDownLatch disconnected = new CountDownLatch(1);
        socket.addListener(new TS3Listener() {
            @Override public void onDisconnected(DisconnectedEvent event) { disconnected.countDown(); }
        });
        socket.setIdentity(LocalIdentity.generateNew(8));
        socket.setNickname("TS3X metadata regression");
        socket.setHandshakeObserver(System.out::println);
        try {
            long start = System.nanoTime();
            socket.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(configured)),
                    TeamSpeakCredentials.serverPassword(System.getProperty("ts3x.test.password", "")), 10000);
            tasks.start(new TeamSpeakSessionTasks.Session() {
                public boolean isConnected() { return socket.isConnected(); }
                public boolean requiresSubscription() { return false; }
                public void subscribe() { fail("Metadata connection subscribed to clients"); }
                public void publishAvatar() { fail("Metadata connection published an avatar"); }
            }, error -> fail(error.toString()), () -> fail("Unexpected metadata transport loss"));
            assertTrue(tasks.awaitInitialization(100));
            assertNotNull("Name comes from initserver, without serverinfo or delays", name.get());
            assertFalse(name.get().trim().isEmpty());
            System.out.println("PASS " + System.getProperty("ts3x.test.generation") + " metadataReadyMs="
                    + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        } finally {
            tasks.stop();
            try { socket.disconnect("TS3X metadata finished"); } catch (Exception ignored) { }
            disconnected.await(1, TimeUnit.SECONDS);
            socket.close();
        }
    }
    private static final class SlowSubscriptionSocket extends LocalTeamspeakClientSocket {
        volatile boolean delay;
        final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(r->{
            Thread t=new Thread(r,"TS3X-test-delay");t.setDaemon(true);return t;
        });
        @Override public void process(AbstractTeamspeakClientSocket socket, SingleCommand command)
                throws CommandProcessException {
            if(delay && command.getName().equals("error")) {
                delay=false;
                timer.schedule(()->{
                    try {super.process(socket,command);}catch(Exception error){throw new RuntimeException(error);}
                },3,TimeUnit.SECONDS);
            }else super.process(socket,command);
        }
    }
    @Test public void sessionIsReadyBeforeSlowSubscriptionCompletesAndKeepaliveRemainsActive() throws Exception {
        String configured=System.getProperty("ts3x.test.port","");
        Assume.assumeFalse("Requires an explicitly configured local server",configured.isEmpty());
        InetSocketAddress endpoint=new InetSocketAddress("127.0.0.1",Integer.parseInt(configured));
        SlowSubscriptionSocket socket=new SlowSubscriptionSocket();
        TeamSpeakSessionTasks tasks=new TeamSpeakSessionTasks();
        CountDownLatch initialized=new CountDownLatch(1),lost=new CountDownLatch(1);
        CountDownLatch disconnected=new CountDownLatch(1);
        socket.addListener(new TS3Listener(){
            @Override public void onDisconnected(DisconnectedEvent event){disconnected.countDown();}
        });
        AtomicReference<Throwable> warning=new AtomicReference<>();
        socket.setIdentity(LocalIdentity.generateNew(8));socket.setNickname("TS3X startup regression");
        try {
            long handshakeStart=System.nanoTime();
            socket.connect(endpoint,TeamSpeakCredentials.serverPassword(System.getProperty("ts3x.test.password","")),10000);
            long handshakeMs=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-handshakeStart);
            socket.delay=true;
            long readyStart=System.nanoTime();
            tasks.start(new TeamSpeakSessionTasks.Session(){
                public boolean isConnected(){return socket.isConnected();}
                public void subscribe()throws Exception{socket.subscribeAll();}
                public void publishAvatar(){initialized.countDown();}
            },warning::set,lost::countDown);
            long readyMs=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-readyStart);
            assertTrue("Startup should not wait for the 3-second subscription",readyMs<500);
            assertFalse(initialized.await(1,TimeUnit.SECONDS));
            assertTrue(socket.isConnected());
            assertTrue(initialized.await(6,TimeUnit.SECONDS));
            assertNull(warning.get());
            long totalMs=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-readyStart);
            assertTrue(totalMs>=2900);
            Thread.sleep(1200);
            assertTrue("Quiet transport still exchanges keepalives",socket.getLastResponseAgeMillis()<2000);
            assertEquals(1,lost.getCount());
            assertNotNull(socket.getClientInfo(socket.getClientId()));
            System.out.println("PASS "+System.getProperty("ts3x.test.generation")+" handshakeMs="+handshakeMs
                    +" readySchedulingMs="+readyMs+" delayedSubscriptionMs="+totalMs+" idleMs="+socket.getLastResponseAgeMillis());
        }finally{
            tasks.stop();socket.timer.shutdownNow();
            try{socket.disconnect("TS3X test finished");}catch(Exception ignored){}
            disconnected.await(1,TimeUnit.SECONDS);
            socket.close();
        }
    }
}
