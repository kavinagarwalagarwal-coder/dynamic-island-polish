package com.dynamicdroid.app

import org.junit.Assert.assertEquals
import org.junit.Test

class CricketScoreTest {
    private fun match(id: String, vararg teams: String) = CricketScore(
        id = id,
        name = teams.joinToString(" vs "),
        teams = teams.toList(),
        status = "In progress",
        matchType = "t20",
        scores = listOf("${teams.first()} 102/3 (12.4 ov)"),
    )

    @Test
    fun preferredTeamMatchesAreSortedFirst() {
        val other = match("other", "New Zealand", "Pakistan")
        val preferred = match("preferred", "India", "Australia")

        assertEquals(
            listOf("preferred", "other"),
            CricketTeamPreferences.prioritized(listOf(other, preferred), setOf("india")).map { it.id },
        )
    }

    @Test
    fun allMatchesRemainAvailableWhenNoTeamsArePreferred() {
        val matches = listOf(match("first", "England", "South Africa"), match("second", "Sri Lanka", "Bangladesh"))

        assertEquals(matches, CricketTeamPreferences.prioritized(matches, emptySet()))
    }
}
