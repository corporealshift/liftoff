package com.liftoff.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShellNavTest {

    @Test
    fun startsOnLaunchpadWithMissionControlClosed() {
        val nav = ShellNav()
        assertEquals(Tab.Launchpad, nav.tab)
        assertEquals(false, nav.missionControlOpen)
        assertEquals(null, nav.inFlightSortieId)
    }

    @Test
    fun selectingATabShowsIt() {
        var nav = ShellNav()
        nav = nav.select(Tab.Mission)
        assertEquals(Tab.Mission, nav.tab)
        assertEquals(false, nav.missionControlOpen)

        nav = nav.select(Tab.Landed)
        assertEquals(Tab.Landed, nav.tab)
        assertEquals(false, nav.missionControlOpen)
    }

    @Test
    fun openingMissionControlKeepsTheTab() {
        var nav = ShellNav(tab = Tab.Mission)
        nav = nav.openMissionControl()
        assertEquals(Tab.Mission, nav.tab)
        assertEquals(true, nav.missionControlOpen)

        nav = ShellNav(tab = Tab.Landed).openMissionControl()
        assertEquals(Tab.Landed, nav.tab)
        assertEquals(true, nav.missionControlOpen)
    }

    @Test
    fun backFromMissionOrLandedGoesToLaunchpad() {
        var nav = ShellNav(tab = Tab.Mission)
        nav = nav.openMissionControl()
        nav = nav.back()!! // close Mission Control -> Mission
        nav = nav.back()!! // Mission -> Launchpad
        assertEquals(Tab.Launchpad, nav.tab)
        assertEquals(false, nav.missionControlOpen)

        nav = ShellNav(tab = Tab.Landed).openMissionControl()
        nav = nav.back()!! // close MC -> Landed
        nav = nav.back()!! // Landed -> Launchpad
        assertEquals(Tab.Launchpad, nav.tab)
    }

    @Test
    fun backFromLaunchpadLeavesTheApp() {
        val nav = ShellNav()
        assertNull(nav.back())
    }

    @Test
    fun backFromMissionControlReturnsToTabItWasOpenedFrom() {
        var nav = ShellNav(tab = Tab.Mission).openMissionControl()
        nav = nav.back()!!
        assertEquals(Tab.Mission, nav.tab)
        assertEquals(false, nav.missionControlOpen)

        nav = ShellNav(tab = Tab.Landed).openMissionControl()
        nav = nav.back()!!
        assertEquals(Tab.Landed, nav.tab)
        assertEquals(false, nav.missionControlOpen)
    }

    @Test
    fun secondBackAfterMissionControlGoesToLaunchpad() {
        var nav = ShellNav(tab = Tab.Mission).openMissionControl()
        nav = nav.back()!! // Mission, MC closed
        nav = nav.back()!! // Launchpad
        assertEquals(Tab.Launchpad, nav.tab)
    }

    @Test
    fun reselectingCurrentTabChangesNothing() {
        val nav = ShellNav(tab = Tab.Mission)
        val same = nav.select(Tab.Mission)
        assertEquals(nav, same)
    }

    @Test
    fun stateSurvivesSaveAndRestore() {
        // Mission tab with MC closed
        var nav1 = ShellNav(tab = Tab.Mission)
        assertEquals(nav1, ShellNav.decode(nav1.encode()))

        // Landed tab with MC open
        var nav2 = ShellNav(tab = Tab.Landed, missionControlOpen = true)
        assertEquals(nav2, ShellNav.decode(nav2.encode()))

        // Launchpad with MC open
        var nav3 = ShellNav(tab = Tab.Launchpad, missionControlOpen = true)
        assertEquals(nav3, ShellNav.decode(nav3.encode()))
    }

    @Test
    fun unreadableSavedStateRestoresToLaunchpad() {
        assertEquals(ShellNav(), ShellNav.decode("garbage"))
        assertEquals(ShellNav(), ShellNav.decode("UnknownTab"))
        assertEquals(ShellNav(), ShellNav.decode(""))
        assertEquals(ShellNav(), ShellNav.decode("Mission|invalid"))
    }

    @Test
    fun openInFlightSetsTheSortieId() {
        var nav = ShellNav(tab = Tab.Launchpad)
        nav = nav.openInFlight(42L)
        assertEquals(Tab.Launchpad, nav.tab)
        assertEquals(false, nav.missionControlOpen)
        assertEquals(42L, nav.inFlightSortieId)
    }

    @Test
    fun backFromInFlightClearsTheSortieId() {
        var nav = ShellNav(tab = Tab.Launchpad).openInFlight(7L)
        nav = nav.back()!!
        assertEquals(Tab.Launchpad, nav.tab)
        assertEquals(null, nav.inFlightSortieId)
    }

    @Test
    fun inFlightEncodeDecodesRoundTrip() {
        val nav = ShellNav(tab = Tab.Mission, missionControlOpen = false, inFlightSortieId = 123L)
        val encoded = nav.encode()
        assertEquals("Mission|2:123", encoded)
        assertEquals(nav, ShellNav.decode(encoded))
    }

    @Test
    fun inFlightEncodeWithLaunchpadTab() {
        val nav = ShellNav(tab = Tab.Launchpad, inFlightSortieId = 5L)
        assertEquals("Launchpad|2:5", nav.encode())
        assertEquals(nav, ShellNav.decode(nav.encode()))
    }

    @Test
    fun backFromInFlightWhileOnMissionGoesToLaunchpad() {
        var nav = ShellNav(tab = Tab.Mission).openInFlight(99L)
        // Back clears in-flight -> Launchpad (since select clears tab on back from in-flight)
        nav = nav.back()!!
        assertEquals(Tab.Launchpad, nav.tab)
        assertEquals(null, nav.inFlightSortieId)
    }

    @Test
    fun selectFromInFlightGoesToLaunchpadAndClearsInFlight() {
        val nav = ShellNav(tab = Tab.Mission, inFlightSortieId = 10L)
        val next = nav.select(Tab.Launchpad)
        assertEquals(Tab.Launchpad, next.tab)
        assertEquals(null, next.inFlightSortieId)
    }

    @Test
    fun mcAndInFlightEncodeBackwardsCompatible() {
        // Existing encoding: "Mission|1" should still decode as before.
        val oldDecoded = ShellNav.decode("Mission|1")
        assertEquals(Tab.Mission, oldDecoded.tab)
        assertEquals(true, oldDecoded.missionControlOpen)
        assertEquals(null, oldDecoded.inFlightSortieId)

        // New encoding: "Launchpad|2:3" should decode with in-flight only.
        val newDecoded = ShellNav.decode("Launchpad|2:3")
        assertEquals(Tab.Launchpad, newDecoded.tab)
        assertEquals(false, newDecoded.missionControlOpen)
        assertEquals(3L, newDecoded.inFlightSortieId)

        // Both flags + in-flight round-trips correctly.
        assertEquals(ShellNav(Tab.Launchpad, true, 3L), ShellNav.decode("Launchpad|1|2:3"))

        // Genuinely bad input decodes to default.
        assertEquals(ShellNav(), ShellNav.decode("Launchpad|2:x"))
    }
}
