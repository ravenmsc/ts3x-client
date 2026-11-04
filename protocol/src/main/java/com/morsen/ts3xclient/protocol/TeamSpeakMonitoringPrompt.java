package com.morsen.ts3xclient.protocol;

/** Prompt only for an explicit disabled-to-enabled transition while restrictions remain. */
public final class TeamSpeakMonitoringPrompt {
    private TeamSpeakMonitoringPrompt() { }

    public static boolean needsSetup(boolean batteryExempt, boolean backgroundRestricted) {
        return !batteryExempt || backgroundRestricted;
    }

    public static boolean shouldOffer(boolean wasEnabled, boolean enabled,
                                      boolean batteryExempt, boolean backgroundRestricted) {
        return !wasEnabled && enabled && needsSetup(batteryExempt, backgroundRestricted);
    }
}
