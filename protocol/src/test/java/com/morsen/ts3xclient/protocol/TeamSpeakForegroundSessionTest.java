package com.morsen.ts3xclient.protocol;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class TeamSpeakForegroundSessionTest {
    private static class Host implements TeamSpeakForegroundSession.Host {
        final List<String> calls = new ArrayList<>();
        boolean permission, backgroundRestricted, playbackDenied;
        public boolean hasMicrophonePermission() { return permission; }
        public void startForeground(boolean microphone) {
            calls.add(microphone ? "microphone" : "playback");
            if (microphone && backgroundRestricted || !microphone && playbackDenied)
                throw new SecurityException("Android restriction");
        }
        public void stopCapture() { calls.add("stop capture"); }
    }

    @Test public void deniedPermissionNeverAttemptsAMicrophoneService() throws Exception {
        Host host = new Host();
        assertFalse(TeamSpeakForegroundSession.start(host));
        assertEquals(List.of("stop capture", "playback"), host.calls);
    }

    @Test public void aBackgroundRestartPreservesPlaybackAndStopsCapture() throws Exception {
        Host host = new Host(); host.permission = true; host.backgroundRestricted = true;
        assertFalse(TeamSpeakForegroundSession.start(host));
        assertEquals(List.of("microphone", "stop capture", "playback"), host.calls);
    }

    @Test public void grantingPermissionCanPromoteAnExistingReceiveOnlySession() throws Exception {
        Host host = new Host();
        assertFalse(TeamSpeakForegroundSession.start(host));
        host.permission = true; host.calls.clear();
        assertTrue(TeamSpeakForegroundSession.start(host));
        assertEquals(List.of("microphone"), host.calls);
    }

    @Test public void aPlaybackFailureIsReportedToTheServiceForCleanup() {
        Host host = new Host(); host.playbackDenied = true;
        assertThrows(SecurityException.class, () -> TeamSpeakForegroundSession.start(host));
    }
}
