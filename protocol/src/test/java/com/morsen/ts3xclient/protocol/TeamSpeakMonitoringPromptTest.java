package com.morsen.ts3xclient.protocol;

import org.junit.Test;
import static org.junit.Assert.*;

public class TeamSpeakMonitoringPromptTest {
    @Test public void firstAndLaterActivationsOfferSetupOnlyWhileARestrictionRemains() {
        for (boolean exempt : new boolean[]{false, true}) {
            for (boolean restricted : new boolean[]{false, true}) {
                boolean needed = !exempt || restricted;
                assertEquals(needed, TeamSpeakMonitoringPrompt.shouldOffer(false, true, exempt, restricted));
                assertEquals(needed, TeamSpeakMonitoringPrompt.needsSetup(exempt, restricted));
                assertFalse(TeamSpeakMonitoringPrompt.shouldOffer(true, true, exempt, restricted));
                assertFalse(TeamSpeakMonitoringPrompt.shouldOffer(true, false, exempt, restricted));
                assertFalse(TeamSpeakMonitoringPrompt.shouldOffer(false, false, exempt, restricted));
            }
        }
    }
    @Test public void reEnablingAnotherServerDoesNotNagAfterBothSystemSettingsAreGranted() {
        assertTrue(TeamSpeakMonitoringPrompt.shouldOffer(false, true, false, true));
        assertFalse(TeamSpeakMonitoringPrompt.shouldOffer(false, true, true, false));
        assertTrue(TeamSpeakMonitoringPrompt.shouldOffer(false, true, true, true));
        assertTrue(TeamSpeakMonitoringPrompt.shouldOffer(false, true, false, false));
    }
}
