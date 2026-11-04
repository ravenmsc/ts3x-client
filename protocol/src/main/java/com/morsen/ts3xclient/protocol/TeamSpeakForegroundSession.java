package com.morsen.ts3xclient.protocol;

/** Keeps a receive-only session alive when Android does not currently allow microphone use. */
public final class TeamSpeakForegroundSession {
    private TeamSpeakForegroundSession() { }

    public interface Host {
        boolean hasMicrophonePermission();
        void startForeground(boolean microphone) throws Exception;
        void stopCapture();
    }

    public static boolean start(Host host) throws Exception {
        if (host.hasMicrophonePermission()) {
            try {
                host.startForeground(true);
                return true;
            } catch (SecurityException unavailableWhileInBackground) {
                // A granted while-in-use permission may still be ineligible on a restart.
            }
        }
        host.stopCapture();
        host.startForeground(false);
        return false;
    }
}
