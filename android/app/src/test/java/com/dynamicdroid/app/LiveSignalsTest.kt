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

    @Test fun scoreboardsSplitIntoBoxesStillRead() {
        // Google's pinned score: team, runs and overs in separate text boxes.
        val google = listOf("Australia", "AUS", "245/3", "(42.1 ov)", "England", "ENG", "Yet to bat")
        assertEquals("AUS 245/3", LiveSignals.cricketScore(google))
        assertTrue(LiveSignals.isLiveScore(LiveSignals.GOOGLE_APP, true, false, google))
        assertTrue(LiveSignals.isLiveScore("com.cricbuzz.android", true, false, listOf("Live", "198-7")))
    }

    @Test fun cricketNotificationsBecomeATwoTeamScoreboard() {
        val b = LiveSignals.cricketBoard(listOf("IND 245/3 (42.1 ov) vs AUS 274/8", "IND need 30 runs from 47 balls"))!!
        assertEquals("IND", b.home.abbr); assertEquals("245/3", b.home.score); assertEquals("42.1 ov", b.home.overs)
        assertTrue(b.home.batting)
        assertEquals("AUS", b.away.abbr); assertEquals("274/8", b.away.score); assertFalse(b.away.batting)
        assertEquals("IND need 30 runs from 47 balls", b.summary)
        // Google's pinned score in separate boxes, with the other side yet to bat.
        val g = LiveSignals.cricketBoard(listOf("Australia", "AUS", "245/3", "(42.1 ov)", "England", "ENG", "Yet to bat"))!!
        assertEquals("AUS", g.home.abbr); assertEquals("ENG", g.away.abbr); assertEquals("", g.away.score)
        // One team alone, or numbers without a cricket score, are not a scoreboard.
        assertNull(LiveSignals.cricketBoard(listOf("IND 245/3 (42.1 ov)")))
        assertNull(LiveSignals.cricketBoard(listOf("ETA 5 min", "PNR 1234")))
    }

    @Test fun scoreboardNotificationsKeepTheCurrentInningsAndOneBatter() {
        val multi = LiveSignals.cricketBoard(listOf("NZ 368 & 75/1 (22 ov) vs IND 486/4"))!!
        assertEquals("75/1", multi.home.score); assertEquals("368", multi.home.previous); assertTrue(multi.home.batting)
        assertFalse(multi.away.batting)
        val both = LiveSignals.cricketBoard(listOf("IND 245/3 (42.1 ov) vs AUS 274/8 (50 ov)"))!!
        assertTrue(both.home.batting); assertFalse(both.away.batting)
        val named = LiveSignals.cricketBoard(listOf("AUS 274/8 (50 ov) vs IND 245/3 (42 ov)", "IND need 30 runs from 48 balls"))!!
        assertFalse(named.home.batting); assertTrue(named.away.batting)
        val done = LiveSignals.cricketBoard(listOf("IND 275/4 (48.2 ov) vs AUS 274/8 (50 ov)", "IND won by 6 wickets"))!!
        assertFalse(done.live); assertFalse(done.inPlay); assertEquals("Result", done.status)
        assertFalse(done.home.batting || done.away.batting)
        val split = LiveSignals.cricketBoard(listOf("AUS 245/3", "(42.1 ov)", "ENG", "Yet to bat"))!!
        assertEquals("42.1 ov", split.home.overs); assertTrue(split.home.batting)
    }
}
