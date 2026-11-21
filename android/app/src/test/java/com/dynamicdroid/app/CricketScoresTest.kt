package com.dynamicdroid.app

import org.junit.Assert.*
import org.junit.Test

class CricketScoresTest {
    private fun ev(id: String, state: String, a: String, aScore: String, aBat: Boolean, b: String, bScore: String, summary: String) = """
        {"id":"$id","status":{"type":{"state":"$state"}},"competitions":[{"status":{"summary":"$summary"},"competitors":[
          {"homeAway":"home","score":"$aScore","team":{"abbreviation":"$a"},"linescores":[{"isBatting":$aBat,"isCurrent":1}]},
          {"homeAway":"away","score":"$bScore","team":{"abbreviation":"$b"},"linescores":[{"isBatting":${!aBat},"isCurrent":1}]}]}]}"""
    private val feed = """{"scores":[{"leagues":[{"name":"Test"}],"events":[
        ${ev("1", "in", "CD", "200/2", true, "WELL", "150", "")},
        ${ev("2", "in", "AUS", "33/1 (3.4/20 ov, target 188)", true, "ENG", "187/9", "Australia require 155 runs")},
        ${ev("3", "in", "NZ", "368", false, "IND", "486/4 (125 ov)", "India lead by 118 runs")}]}]}"""

    @Test fun indiaIsShownFirstByDefault() {
        val m = CricketScores.pick(CricketScores.parse(feed), listOf("IND"))!!
        assertEquals("3", m.id)
        assertEquals("IND 486/4", CricketScores.chip(m))
        assertEquals("IND 486/4 (125 ov)  ·  NZ 368", CricketScores.title(m))
    }

    @Test fun withoutTheFavouriteOnlyInternationalMatchesShow() {
        val m = CricketScores.pick(CricketScores.parse(feed), listOf("PAK"))!!
        assertEquals("2", m.id)
        assertEquals("AUS 33/1", CricketScores.chip(m))
        assertEquals("3.4 ov", CricketScores.overs("33/1 (3.4/20 ov, target 188)"))
        assertNull(CricketScores.pick(CricketScores.parse(feed).filter { it.id == "1" }, listOf("PAK")))
    }

    @Test fun secondInningsAndBadFeeds() {
        assertEquals("138/3", CricketScores.current("292 & 138/3 (21.2 ov, target 138)"))
        assertTrue(CricketScores.parse("<html>Access Denied</html>").isEmpty())
    }
}
