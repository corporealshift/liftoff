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
}
