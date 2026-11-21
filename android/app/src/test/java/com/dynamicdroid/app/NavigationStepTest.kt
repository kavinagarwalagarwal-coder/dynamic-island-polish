package com.dynamicdroid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavigationStepTest {
    @Test fun distanceTitleAndInstructionText() {
        val s = NavigationStep.parse("200 m", "Turn right onto MG Road")
        assertEquals("200 m", s.distance)
        assertEquals("Turn right onto MG Road", s.instruction)
        assertEquals(Turn.RIGHT, s.turn)
    }

    @Test fun instructionTitleAndDistanceText() {
        val s = NavigationStep.parse("Turn left onto Park St", "0.3 mi")
        assertEquals("0.3 mi", s.distance)
        assertEquals("Turn left onto Park St", s.instruction)
        assertEquals(Turn.LEFT, s.turn)
    }

    @Test fun distanceInsideTheInstruction() {
        val s = NavigationStep.parse("In 500 ft, keep right", "")
        assertEquals("500 ft", s.distance)
        assertEquals("Keep right", s.instruction)
        assertEquals(Turn.SLIGHT_RIGHT, s.turn)
    }

    @Test fun commaDecimalsAndKilometres() {
        assertEquals("1,2 km", NavigationStep.parse("1,2 km", "Rechts abbiegen").distance)
    }

    @Test fun noDistanceKeepsBothLines() {
        val s = NavigationStep.parse("Head north", "Main St")
        assertNull(s.distance)
        assertEquals("Head north · Main St", s.instruction)
        assertEquals(Turn.STRAIGHT, s.turn)
    }

    @Test fun turnKinds() {
        assertEquals(Turn.U_TURN, NavigationStep.turnOf("Make a U-turn"))
        assertEquals(Turn.ROUNDABOUT, NavigationStep.turnOf("At the roundabout, take the 2nd exit"))
        assertEquals(Turn.SHARP_LEFT, NavigationStep.turnOf("Sharp left onto A1"))
        assertEquals(Turn.ARRIVE, NavigationStep.turnOf("You have arrived. Destination is on your right"))
        assertEquals(Turn.RIGHT, NavigationStep.turnOf("Turn right; destination ahead"))
        assertNull(NavigationStep.turnOf("Rechts abbiegen"))
    }

    @Test fun streetNamesDoNotChangeTheTurn() {
        assertEquals(Turn.RIGHT, NavigationStep.turnOf("Turn right onto Left Bank Road"))
        assertEquals(Turn.LEFT, NavigationStep.turnOf("Turn left to stay on Right St"))
        assertEquals(Turn.STRAIGHT, NavigationStep.turnOf("Continue on Leftwich Ave"))
        assertEquals(Turn.RIGHT, NavigationStep.turnOf("Use any lane to turn right"))
        assertEquals(Turn.SLIGHT_LEFT, NavigationStep.turnOf("Use the left 2 lanes to keep left onto Right Bank Rd"))
        assertEquals(Turn.RIGHT, NavigationStep.parse("Left Bank Road", "Turn right").turn)
        assertEquals(Turn.ARRIVE, NavigationStep.turnOf("Destination will be on the left"))
    }

    @Test fun ignoresNumbersThatAreNotDistances() {
        assertNull(NavigationStep.parse("Take exit 12", "A4 motorway").distance)
        assertNull(NavigationStep.parse("12 min", "Arrive 11:04").distance)
        val road = NavigationStep.parse("Turn right onto 8 Mile Road", "")
        assertNull(road.distance)
        assertEquals("Turn right onto 8 Mile Road", road.instruction)
        assertEquals("0.5 mi", NavigationStep.parse("Turn right onto 8 Mile Road", "0.5 mi").distance)
        assertEquals("200 m", NavigationStep.parse("Then in 200 m turn left", "").distance)
        assertEquals("300 ft", NavigationStep.parse("Keep left · 300 ft", "").distance)
    }
}
