package com.dynamicdroid.app

/**
 * Whether the island window takes touches right now. One rule, used both when the island
 * renders and when it settles, so the two can never disagree.
 */
object IslandTouchPolicy {
    /**
     * @param showing an activity is on the island
     * @param idlePill the resting pill is on
     * @param homeCard tapping the resting pill opens the home card
     * @param concealed the island is hidden (full-screen app, paused, screenshot)
     * @param flying an app is flying into the island
     * @param passing touches pass through for a moment (a pop-up being swiped away under it)
     */
    fun takesTouches(showing: Boolean, idlePill: Boolean, homeCard: Boolean,
                     concealed: Boolean, flying: Boolean, passing: Boolean): Boolean =
        (showing || (idlePill && homeCard)) && !concealed && !flying && !passing
}
