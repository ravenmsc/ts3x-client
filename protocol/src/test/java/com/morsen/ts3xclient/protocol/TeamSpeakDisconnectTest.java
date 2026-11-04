package com.morsen.ts3xclient.protocol;
import org.junit.Test;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;
public class TeamSpeakDisconnectTest {
    @Test public void confirmedLeaveClosesLocally() throws Exception {
        AtomicBoolean closed=new AtomicBoolean();
        assertTrue(TeamSpeakDisconnect.close(new TeamSpeakDisconnect.Transport(){
            public boolean isConnected(){return true;}
            public void requestDisconnect(){}
            public void close(){closed.set(true);}
        },100));assertTrue(closed.get());
    }
    @Test public void missingNotificationCannotDelayLocalClose() throws Exception {
        AtomicBoolean closed=new AtomicBoolean();CountDownLatch released=new CountDownLatch(1);
        long start=System.nanoTime();
        assertFalse(TeamSpeakDisconnect.close(new TeamSpeakDisconnect.Transport(){
            public boolean isConnected(){return true;}
            public void requestDisconnect()throws Exception{released.await();}
            public void close(){closed.set(true);released.countDown();}
        },60));assertTrue(closed.get());assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<1000);
    }
    @Test public void remoteFailureStillAllowsLocalDisconnect() throws Exception {
        AtomicBoolean closed=new AtomicBoolean();
        assertFalse(TeamSpeakDisconnect.close(new TeamSpeakDisconnect.Transport(){
            public boolean isConnected(){return true;}
            public void requestDisconnect()throws Exception{throw new IOException("remote left first");}
            public void close(){closed.set(true);}
        },100));assertTrue(closed.get());
    }
    @Test public void cleanupFailureIsNotHidden() throws Exception {
        try {TeamSpeakDisconnect.close(new TeamSpeakDisconnect.Transport(){
            public boolean isConnected(){return false;}
            public void requestDisconnect(){fail();}
            public void close()throws Exception{throw new IOException("close failed");}
        },100);fail();}catch(IOException expected){assertEquals("close failed",expected.getMessage());}
    }
}
