package com.morsen.ts3xclient.protocol;

import org.junit.Test;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

public class TeamSpeakSessionTasksTest {
    @Test public void metadataProbeSkipsSubscriptionAndAvatarButStillWatchesTheTransport() throws Exception {
        TeamSpeakSessionTasks tasks = new TeamSpeakSessionTasks();
        CountDownLatch lost = new CountDownLatch(1);
        AtomicBoolean connected = new AtomicBoolean(true);
        try {
            tasks.start(new TeamSpeakSessionTasks.Session() {
                public boolean isConnected() { return connected.get(); }
                public boolean requiresSubscription() { return false; }
                public void subscribe() { fail("Metadata probe subscribed to all clients"); }
                public void publishAvatar() { fail("Metadata probe uploaded an avatar"); }
            }, error -> fail(error.toString()), lost::countDown);
            assertTrue(tasks.awaitInitialization(100));
            connected.set(false);
            assertTrue(lost.await(1500, TimeUnit.MILLISECONDS));
        } finally { tasks.stop(); }
    }
    static class Session implements TeamSpeakSessionTasks.Session {
        volatile boolean connected=true;
        final CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1);
        final AtomicInteger avatars=new AtomicInteger();
        public boolean isConnected(){return connected;}
        public void subscribe() throws InterruptedException{entered.countDown();release.await();}
        public void publishAvatar(){avatars.incrementAndGet();}
    }
    @Test public void slowSubscriptionDoesNotDelayReadiness() throws Exception {
        TeamSpeakSessionTasks tasks=new TeamSpeakSessionTasks();Session session=new Session();
        long start=System.nanoTime();
        try {
            tasks.start(session,error -> fail(error.toString()),() -> fail("unexpected loss"));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<300);
            assertTrue(session.entered.await(2,TimeUnit.SECONDS));assertEquals(0,session.avatars.get());
            session.release.countDown();
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(session.avatars.get()==0 && System.nanoTime()<until)Thread.sleep(10);
            assertEquals(1,session.avatars.get());
        } finally{tasks.stop();}
    }
    @Test public void deadTransportCannotBeCommittedAsReady() throws Exception {
        TeamSpeakSessionTasks tasks=new TeamSpeakSessionTasks();Session session=new Session();session.connected=false;
        try{tasks.start(session,error -> {},() -> {});fail();}catch(IOException expected){}
        finally{tasks.stop();}
        assertEquals(1,session.entered.getCount());
    }
    @Test public void lossIsDetectedWhileSubscriptionIsStillBlocked() throws Exception {
        TeamSpeakSessionTasks tasks=new TeamSpeakSessionTasks();Session session=new Session();
        CountDownLatch lost=new CountDownLatch(1);
        try {
            tasks.start(session,error -> {},lost::countDown);
            assertTrue(session.entered.await(2,TimeUnit.SECONDS));
            session.connected=false;
            assertTrue("Do not wait for the 25-second app keepalive",lost.await(1500,TimeUnit.MILLISECONDS));
            assertEquals(0,session.avatars.get());
        }finally{tasks.stop();}
    }
    @Test public void replacingASessionCancelsItsOptionalWorkWithoutLosingTheNewSession() throws Exception {
        TeamSpeakSessionTasks tasks=new TeamSpeakSessionTasks();Session first=new Session(),second=new Session();
        AtomicInteger losses=new AtomicInteger();
        try {
            tasks.start(first,error -> {},losses::incrementAndGet);
            assertTrue(first.entered.await(2,TimeUnit.SECONDS));
            tasks.start(second,error -> {},losses::incrementAndGet);
            first.connected=false;first.release.countDown();
            assertTrue(second.entered.await(2,TimeUnit.SECONDS));second.release.countDown();
            Thread.sleep(650);
            assertEquals(0,first.avatars.get());assertEquals(1,second.avatars.get());assertEquals(0,losses.get());
        }finally{tasks.stop();}
    }
    @Test public void deniedOptionalSubscriptionDoesNotBreakHealthyVoiceSession() throws Exception {
        TeamSpeakSessionTasks tasks=new TeamSpeakSessionTasks();AtomicInteger warnings=new AtomicInteger();
        CountDownLatch avatar=new CountDownLatch(1);AtomicInteger losses=new AtomicInteger();
        try {
            tasks.start(new TeamSpeakSessionTasks.Session(){
                public boolean isConnected(){return true;}
                public void subscribe()throws Exception{throw new IOException("permission denied");}
                public void publishAvatar(){avatar.countDown();}
            },error -> warnings.incrementAndGet(),losses::incrementAndGet);
            assertTrue(avatar.await(2,TimeUnit.SECONDS));assertEquals(1,warnings.get());assertEquals(0,losses.get());
        }finally{tasks.stop();}
    }
    @Test public void monitoringWaitsForACompleteSnapshotAndRejectsAnIncompleteOne() throws Exception {
        TeamSpeakSessionTasks tasks=new TeamSpeakSessionTasks();Session session=new Session();
        try {
            tasks.start(session,error -> {},() -> {});
            assertTrue(session.entered.await(2,TimeUnit.SECONDS));
            assertFalse(tasks.awaitInitialization(40));
            session.release.countDown();
            assertTrue(tasks.awaitInitialization(2000));
            tasks.stop();assertFalse(tasks.awaitInitialization(40));
        }finally{tasks.stop();}
    }

}
