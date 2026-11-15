package com.dynamicdroid.app

import org.junit.Assert.*
import org.junit.Test

class LiveSignalsTest {
    @Test fun liveUpdatesComeFromAndroid16OrFromKnownTripOrderAndScoreApps() {
        assertTrue(LiveSignals.isLiveUpdate(LiveSignals.PROGRESS_STYLE, 0, false, "x.y", true, false))
        assertTrue(LiveSignals.isLiveUpdate(null, LiveSignals.FLAG_PROMOTED_ONGOING, false, "x.y", true, false))
        assertTrue(LiveSignals.isLiveUpdate(null, 0, true, "x.y", true, false))
        assertTrue(LiveSignals.isLiveUpdate(null, 0, false, "in.swiggy.android", true, false))
        assertTrue(LiveSignals.isLiveUpdate(null, 0, false, "x.y", true, hasShortText = true))
        // An ordinary ongoing notification (a sync, a VPN) is not a live update.
        assertFalse(LiveSignals.isLiveUpdate(null, 0, false, "x.y", true, false))
        // A known app's ordinary, dismissable notification is a message, not a live update.
        assertFalse(LiveSignals.isLiveUpdate(null, 0, false, "com.ubercab", false, false))
    }

    @Test fun recordingsAreScreenOrVoiceRecordingInProgress() {
        assertTrue(LiveSignals.isRecording("com.android.systemui", "screen_record", "Recording screen", "", true, true))
        assertTrue(LiveSignals.isRecording("com.google.android.apps.recorder", null, "Recording", "", true, true))
        assertTrue(LiveSignals.isRecording("some.app", "recording_channel", "Recorder", "", true, true))
        assertFalse(LiveSignals.isRecording("com.android.systemui", "alerts", "USB debugging connected", "", true, false))
        assertFalse(LiveSignals.isRecording("some.app", "general", "Workout", "", true, true))
        assertFalse(LiveSignals.isRecording("com.google.android.apps.recorder", null, "Recording saved", "", false, false))
        assertTrue(LiveSignals.isScreenRecording("com.android.systemui", "screen_record", "Recording"))
        assertFalse(LiveSignals.isScreenRecording("com.google.android.apps.recorder", "rec", "Recording"))
    }

    @Test fun hotspotIsTheOngoingTetheringNotification() {
        assertTrue(LiveSignals.isHotspot("com.android.networkstack.tethering", null, true))
        assertTrue(LiveSignals.isHotspot("com.android.settings", "hotspot_channel", true))
        assertFalse(LiveSignals.isHotspot("com.android.settings", "hotspot_channel", false))
        assertFalse(LiveSignals.isHotspot("com.whatsapp", "messages", true))
    }

    @Test fun theSmallIslandShowsTheFewCharactersThatSayTheMost() {
        assertEquals("ETA 4", LiveSignals.shortText("ETA 4", "", ""))
        assertEquals("5 min", LiveSignals.shortText(null, "Arriving in 5 mins", ""))
        assertEquals("12 min", LiveSignals.shortText(null, "Order on the way", "Reaching in 12 minutes"))
        assertEquals("IND 245/3", LiveSignals.shortText(null, "IND 245/3 (38.2)", "AUS yet to bat"))
        assertEquals("2-1", LiveSignals.shortText(null, "Arsenal 2 - 1 Chelsea", ""))
        assertEquals("10:52", LiveSignals.shortText(null, "Your flight", "Departs 10:52"))
        assertNull(LiveSignals.shortText(null, "Looking for a driver", "Hang tight"))
        assertEquals(10, LiveSignals.shortText("A very long status chip", "", "")!!.length)
    }

    @Test fun scoreAppsSilentNotificationsAndTeamNamesCount() {
        // Cricbuzz-style: not ongoing, but silent and updated in place.
        assertTrue(LiveSignals.isLiveScore("com.cricbuzz.android", false, false, listOf("India 245/3"), quiet = true))
        // The same text from an app that isn't a score app is not enough.
        assertFalse(LiveSignals.isLiveScore("com.example.app", false, false, listOf("India 245/3"), quiet = true))
        assertFalse(LiveSignals.isLiveScore("com.example.app", true, false, listOf("Due 12/3")))
    }
}
